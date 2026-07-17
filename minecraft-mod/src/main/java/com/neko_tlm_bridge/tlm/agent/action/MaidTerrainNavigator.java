package com.neko_tlm_bridge.tlm.agent.action;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.network.debug.MaidPathDebugService;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.MaidActionContext;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainPath;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainWorldEvaluator;
import com.neko_tlm_bridge.tlm.agent.runtime.HandLease;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Executes an already-computed terrain path on the server thread. Each terrain
 * edge first clears its declared obstacles progressively, then delegates the
 * adjacent movement to the maid's native TLM navigation.
 */
public final class MaidTerrainNavigator {
    private static final double MIN_SPEED = 0.4D;
    private static final double MAX_SPEED = 1.0D;
    private static final long STUCK_WINDOW_TICKS = 40L;
    private static final double REQUIRED_STEP_PROGRESS = 0.25D;
    private static final long ARRIVAL_SETTLE_TIMEOUT_TICKS = 40L;
    private static final double MIN_CONTROLLED_DESCEND_SPEED = 0.10D;
    private static final double MAX_CONTROLLED_DESCEND_SPEED = 0.18D;
    private static final double DESCEND_CENTER_TOLERANCE = 0.12D;
    private static final long CONTROLLED_DESCEND_UPWARD_RECOVERY_TICKS = 10L;
    private static final int FALLING_CLEARANCE_STABLE_TICKS = 3;
    private static final long FALLING_CLEARANCE_SETTLE_TIMEOUT_TICKS = 80L;
    private static final int FALLING_ENTITY_SCAN_HEIGHT = 16;
    private static final int MAX_CONTINUOUS_FLAT_STEPS = 6;
    private static final double DIRECT_TURN_CENTER_TOLERANCE = 0.20D;
    private static final double DIRECT_RIGHT_ANGLE_TURN_TOLERANCE = 0.32D;
    private static final double CONSTRUCTION_CENTER_TOLERANCE = 0.10D;
    private static final long CONSTRUCTION_CENTER_TIMEOUT_TICKS = 40L;
    private static final long PLAYER_WORK_ZONE_WAIT_TIMEOUT_TICKS = 200L;

    private final MaidTerrainPath terrainPath;
    private final HandLease handLease;
    private final double speed;
    private final boolean requireCorrectTool;
    private final boolean allowConstruction;
    private final int maxPlacements;
    private final Map<BlockPos, BlockState> plannedBreakStates = new HashMap<>();
    private final Set<BlockPos> clearedBlocks = new LinkedHashSet<>();
    private final ArrayDeque<ClearedBlock> clearedEvents = new ArrayDeque<>();
    private final ArrayDeque<PlacedBlock> placedEvents = new ArrayDeque<>();
    private final ArrayDeque<BlockPos> completedStepEvents = new ArrayDeque<>();

    private Path debugPath;
    private int stepIndex;
    private List<BlockPos> pendingBreaks = List.of();
    private int breakIndex;
    private MaidProgressiveBlockBreaker breaker;
    private boolean movementStarted;
    private boolean directFlatMovement;
    private double movementStartDistance;
    private double windowStartDistance;
    private long windowStartedAt;
    private boolean started;
    private boolean terminal;
    private long arrivalSettleStartedAt = Long.MIN_VALUE;
    private long controlledDescendRecoveryStartedAt = Long.MIN_VALUE;
    private long fallingClearanceWaitStartedAt = Long.MIN_VALUE;
    private int fallingClearanceStableTicks;
    private int fallingBlocksCleared;
    private boolean fallingClearanceObserved;
    private boolean fallingStabilizationRequired;
    private int placementsUsed;
    private int nativePathStarts;
    private int hardStops;
    private int chainedTraverseSteps;
    private int directWaypointStarts;
    private int activeFlatRunEndExclusive = -1;
    private long playerWaitStartedAt = Long.MIN_VALUE;
    private BlockPos playerBlockedTarget;
    private MaidTerrainInteractionSafety.Conflict playerConflict;
    private java.util.UUID blockingPlayerId;
    private BlockPos constructionCenterTarget;
    private long constructionCenterStartedAt = Long.MIN_VALUE;
    private String phase = "pending";
    private ActionEndReason lastFailure;

    public MaidTerrainNavigator(MaidTerrainPath terrainPath, HandLease handLease,
                                double speed, boolean requireCorrectTool) {
        this(terrainPath, handLease, speed, requireCorrectTool, false, 0);
    }

    public MaidTerrainNavigator(MaidTerrainPath terrainPath, HandLease handLease,
                                double speed, boolean requireCorrectTool,
                                boolean allowConstruction, int maxPlacements) {
        this.terrainPath = Objects.requireNonNull(terrainPath, "terrainPath");
        this.handLease = Objects.requireNonNull(handLease, "handLease");
        if (!Double.isFinite(speed)) {
            throw new IllegalArgumentException("speed must be finite");
        }
        this.speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        this.requireCorrectTool = requireCorrectTool;
        this.allowConstruction = allowConstruction;
        if (maxPlacements < 0) {
            throw new IllegalArgumentException("maxPlacements must not be negative");
        }
        this.maxPlacements = maxPlacements;
    }

    public void start(MaidActionContext context) {
        Objects.requireNonNull(context, "context");
        if (started) {
            return;
        }
        started = true;
        // A terrain route owns locomotion from this point on. Do not let a
        // path left by follow/work AI move the maid while the first passage
        // cells are being cleared.
        stopNativeNavigation(context);
        if (context.maid().onGround()) {
            context.maid().setDeltaMovement(Vec3.ZERO);
        }
        for (MaidTerrainStep step : terrainPath.steps()) {
            for (BlockPos pos : step.toBreak()) {
                plannedBreakStates.putIfAbsent(pos, context.level().getBlockState(pos));
            }
        }
        debugPath = createDebugPath(terrainPath);
        if (debugPath != null) {
            // The first debug node is the path origin and is already occupied.
            debugPath.advance();
            MaidPathDebugService.publishIfNeeded(context.maid(), debugPath, context.gameTime(), true);
        }
    }

    public TickResult tick(MaidActionContext context) {
        Objects.requireNonNull(context, "context");
        if (!started) {
            start(context);
        }
        if (terminal) {
            return failed(lastFailure == null ? ActionEndReason.INTERNAL_ERROR : lastFailure,
                    "terrain_navigator_ticked_after_terminal_state", false);
        }
        if (handLease.validate(context.maid()) != HandLease.LeaseHealth.HEALTHY) {
            return fail(context, ActionEndReason.HAND_CONFLICT, "held_tool_changed_during_terrain_path", false);
        }
        if (stepIndex >= terrainPath.steps().size()) {
            return arrive(context);
        }
        // Native navigation can cross multiple cells between server ticks.
        // Synchronize the logical cursor before applying support, clearance or
        // construction effects to what may already be a cell behind the maid.
        synchronizeReachedFlatSteps(context);
        if (stepIndex >= terrainPath.steps().size()) {
            return arrive(context);
        }
        if (movementStarted && activeFlatRunEndExclusive > stepIndex + 1
                && !activeFlatRunStillValid(context)) {
            stopLocomotion(context);
            movementStarted = false;
            activeFlatRunEndExclusive = -1;
            phase = "flat_run_invalidated";
        }

        MaidTerrainStep step = terrainPath.steps().get(stepIndex);
        if (!movementStarted && breaker == null && breakIndex == 0
                && !context.maid().blockPosition().equals(step.from())
                && !context.maid().blockPosition().equals(step.to())) {
            return fail(context, ActionEndReason.TARGET_CHANGED,
                    "maid_is_no_longer_at_terrain_step_origin", true);
        }

        LinkedHashSet<BlockPos> workZone = new LinkedHashSet<>(step.clearance());
        // The support cell is not part of the maid's body clearance, but
        // modifying it while it carries a player's feet is just as dangerous.
        workZone.add(step.to().below().immutable());
        TickResult playerWait = waitForPlayers(
                context, step, workZone, "player_blocking_route");
        if (playerWait != null) {
            publishDebug(context, false);
            return playerWait;
        }

        TickResult support = ensureDestinationSupport(context, step);
        if (support != null) {
            publishDebug(context, false);
            return support;
        }

        if (pendingBreaks.isEmpty() && breakIndex == 0 && !step.toBreak().isEmpty()) {
            pendingBreaks = new ArrayList<>(step.toBreak());
            sortPendingBreaks(context, pendingBreaks);
        }

        if (fallingStabilizationRequired) {
            TickResult fallingClearance = stabilizeFallingClearance(context, step);
            if (fallingClearance != null) {
                publishDebug(context, false);
                return fallingClearance;
            }
            fallingStabilizationRequired = false;
        }

        TickResult clearing = clearNextObstacle(context, step);
        if (clearing != null) {
            publishDebug(context, false);
            return clearing;
        }


        TickResult waterSeal = sealWaterForClearance(context, step);
        if (waterSeal != null) {
            publishDebug(context, false);
            return waterSeal;
        }

        TickResult fallingClearance = stabilizeFallingClearance(context, step);
        if (fallingClearance != null) {
            publishDebug(context, false);
            return fallingClearance;
        }

        TickResult movement = moveToStepDestination(context, step);
        publishDebug(context, false);
        return movement;
    }

    /** Stops native movement, block cracks and all Agent-owned movement memories. */
    public void stop(MaidActionContext context) {
        Objects.requireNonNull(context, "context");
        if (breaker != null) {
            breaker.stop(context);
            breaker = null;
        }
        stopNativeNavigation(context);
        stopHorizontalMovement(context);
        MaidPathDebugService.clear(context.maid().getUUID());
        terminal = true;
        phase = "stopped";
    }

    /**
     * Drains successful route-clear commits. A position is emitted at most
     * once, even if a path happens to mention it in more than one step.
     */
    public List<ClearedBlock> drainClearedBlocks() {
        List<ClearedBlock> drained = new ArrayList<>(clearedEvents.size());
        while (!clearedEvents.isEmpty()) {
            drained.add(clearedEvents.removeFirst());
        }
        return List.copyOf(drained);
    }

    public List<PlacedBlock> drainPlacedBlocks() {
        List<PlacedBlock> drained = new ArrayList<>(placedEvents.size());
        while (!placedEvents.isEmpty()) {
            drained.add(placedEvents.removeFirst());
        }
        return List.copyOf(drained);
    }

    /** Logical terrain cells crossed since the previous action tick. */
    public List<BlockPos> drainCompletedStepPositions() {
        List<BlockPos> drained = new ArrayList<>(completedStepEvents.size());
        while (!completedStepEvents.isEmpty()) {
            drained.add(completedStepEvents.removeFirst());
        }
        return List.copyOf(drained);
    }

    public boolean cleared(BlockPos pos) {
        return clearedBlocks.contains(pos);
    }

    public Set<BlockPos> clearedBlocks() {
        return Set.copyOf(clearedBlocks);
    }

    public MaidTerrainPath terrainPath() {
        return terrainPath;
    }

    public JsonObject diagnostics() {
        JsonObject detail = new JsonObject();
        detail.addProperty("phase", phase);
        detail.addProperty("steps_total", terrainPath.steps().size());
        detail.addProperty("steps_completed", stepIndex);
        detail.addProperty("current_step", Math.min(stepIndex, terrainPath.steps().size()));
        detail.addProperty("cleared_blocks", clearedBlocks.size());
        detail.addProperty("falling_blocks_cleared", fallingBlocksCleared);
        detail.addProperty("placements_used", placementsUsed);
        detail.addProperty("native_path_starts", nativePathStarts);
        detail.addProperty("hard_stops", hardStops);
        detail.addProperty("chained_traverse_steps", chainedTraverseSteps);
        detail.addProperty("direct_waypoint_starts", directWaypointStarts);
        detail.addProperty("path_cost", terrainPath.totalCost());
        detail.addProperty("expanded_nodes", terrainPath.expandedNodes());
        detail.addProperty("target_x", terrainPath.target().getX());
        detail.addProperty("target_y", terrainPath.target().getY());
        detail.addProperty("target_z", terrainPath.target().getZ());
        if (lastFailure != null) {
            detail.addProperty("failure_reason", lastFailure.name());
        }
        return detail;
    }

    private TickResult clearNextObstacle(MaidActionContext context, MaidTerrainStep step) {
        while (breakIndex < pendingBreaks.size()) {
            stopLocomotion(context);
            BlockPos pos = pendingBreaks.get(breakIndex);
            BlockState expected = plannedBreakStates.get(pos);
            BlockState current = context.level().getBlockState(pos);

            TickResult waterSeal = sealWaterAround(
                    context, step, pos, current, true);
            if (waterSeal != null) {
                return waterSeal;
            }

            if (current.getFluidState().isEmpty()
                    && current.getCollisionShape(context.level(), pos).isEmpty()) {
                breakIndex++;
                continue;
            }
            if (expected == null || !current.equals(expected)) {
                return fail(context, ActionEndReason.TARGET_CHANGED,
                        "terrain_obstacle_changed_before_clear", true);
            }
            if (breaker == null) {
                breaker = new MaidProgressiveBlockBreaker(
                        pos, expected, handLease, requireCorrectTool);
            }

            phase = "clearing";
            MaidProgressiveBlockBreaker.TickResult result = breaker.tick(context);
            if (result.outcome() == MaidProgressiveBlockBreaker.Outcome.RUNNING) {
                return running(result.detail());
            }
            if (result.outcome() == MaidProgressiveBlockBreaker.Outcome.FAILED) {
                boolean replan = result.reason() == ActionEndReason.TARGET_CHANGED
                        || result.reason() == ActionEndReason.PATH_NOT_FOUND;
                return fail(context, result.reason(),
                        result.detail().has("message")
                                ? result.detail().get("message").getAsString()
                                : "terrain_clear_failed",
                        replan);
            }

            breaker = null;
            breakIndex++;
            clearedBlocks.add(pos.immutable());
            // A sand/gravel column can place several physical blocks at the
            // same position. Emit every committed break for durability/drop
            // accounting; clearedBlocks remains the unique-position view used
            // by route diagnostics.
            clearedEvents.addLast(new ClearedBlock(pos, expected));
            if (isFallingBlockState(expected)) {
                fallingClearanceObserved = true;
                fallingClearanceWaitStartedAt = context.gameTime();
                fallingClearanceStableTicks = 0;
                fallingBlocksCleared++;
                fallingStabilizationRequired = true;
            }
            JsonObject detail = stepDetail(step);
            detail.addProperty("cleared_x", pos.getX());
            detail.addProperty("cleared_y", pos.getY());
            detail.addProperty("cleared_z", pos.getZ());
            return running(detail);
        }
        return null;
    }

    private TickResult ensureDestinationSupport(
            MaidActionContext context, MaidTerrainStep step) {
        BlockPos supportPos = step.to().below();
        if (!isLoadedBuildPosition(context, supportPos)) {
            return null;
        }
        BlockState support = context.level().getBlockState(supportPos);
        MaidTerrainWorldEvaluator.SupportAssessment assessment =
                MaidTerrainWorldEvaluator.assessStandSupport(
                        context.level(), supportPos, support);
        if (assessment == MaidTerrainWorldEvaluator.SupportAssessment.SAFE
                || !allowConstruction) {
            return null;
        }
        if (assessment == MaidTerrainWorldEvaluator.SupportAssessment.LAVA_HAZARD
                || !support.canBeReplaced()) {
            return null;
        }
        MaidTerrainBuilder.Purpose purpose =
                assessment == MaidTerrainWorldEvaluator.SupportAssessment.WATER_HAZARD
                        ? MaidTerrainBuilder.Purpose.SEAL_FLUID
                        : MaidTerrainBuilder.Purpose.BRIDGE_SUPPORT;
        return placeConstructionBlock(context, supportPos, purpose,
                purpose == MaidTerrainBuilder.Purpose.SEAL_FLUID
                        ? "water_seal_failed" : "support_placement_failed");
    }

    private TickResult sealWaterForClearance(
            MaidActionContext context, MaidTerrainStep step) {
        if (!allowConstruction || isStepClearanceOpen(context, step)) {
            return null;
        }
        for (BlockPos pos : step.clearance()) {
            if (!isLoadedBuildPosition(context, pos)) {
                continue;
            }
            BlockState state = context.level().getBlockState(pos);
            TickResult result = sealWaterAround(
                    context, step, pos, state, false);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private TickResult sealWaterAround(
            MaidActionContext context, MaidTerrainStep step,
            BlockPos obstacle, BlockState state, boolean updateExpectedObstacle) {
        if (!allowConstruction) {
            return null;
        }
        MaidTerrainWorldEvaluator.ClearanceAssessment assessment =
                MaidTerrainWorldEvaluator.assessClearance(
                        context.level(), obstacle, state);
        boolean directFluid = !state.getFluidState().isEmpty();
        boolean directWater = state.getFluidState()
                .is(net.minecraft.tags.FluidTags.WATER);
        if (!requiresWaterSeal(assessment, directFluid, directWater)) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        if (!state.getFluidState().isEmpty()) {
            candidates.add(obstacle.immutable());
        }
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = obstacle.relative(direction);
            if (isLoadedBuildPosition(context, adjacent)
                    && !context.level().getBlockState(adjacent)
                    .getFluidState().isEmpty()) {
                candidates.add(adjacent.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(
                pos -> context.maid().getEyePosition()
                        .distanceToSqr(Vec3.atCenterOf(pos))));
        int occupiedWaterCandidates = 0;
        for (BlockPos target : candidates) {
            BlockState fluid = context.level().getBlockState(target);
            if (!fluid.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                continue;
            }
            if (target.equals(step.from()) || target.equals(step.from().above())) {
                occupiedWaterCandidates++;
                continue;
            }
            TickResult placement = placeConstructionBlock(
                    context, target, MaidTerrainBuilder.Purpose.SEAL_FLUID,
                    "water_seal_failed");
            if (placement != null && !placement.detail().has("message")) {
                if (target.equals(obstacle) && updateExpectedObstacle) {
                    plannedBreakStates.put(obstacle.immutable(),
                            context.level().getBlockState(obstacle));
                } else if (step.clearance().contains(target)) {
                    plannedBreakStates.put(target.immutable(),
                            context.level().getBlockState(target));
                    List<BlockPos> remaining = new ArrayList<>();
                    for (int index = breakIndex; index < pendingBreaks.size(); index++) {
                        remaining.add(pendingBreaks.get(index));
                    }
                    if (!remaining.contains(target)) {
                        remaining.add(target.immutable());
                    }
                    sortPendingBreaks(context, remaining);
                    pendingBreaks = remaining;
                    breakIndex = 0;
                }
            }
            return placement;
        }
        if (candidates.isEmpty()) {
            // The planner may have observed adjacent water which flowed away
            // before execution. With no live water cell there is nothing to
            // seal; let the breaker revalidate the now-dry obstacle.
            return null;
        }
        TickResult failure = fail(context, ActionEndReason.PATH_NOT_FOUND,
                occupiedWaterCandidates > 0
                        ? "water_seal_requires_dry_start"
                        : "water_seal_failed",
                true);
        failure.detail().addProperty("water_candidates", candidates.size());
        failure.detail().addProperty(
                "occupied_water_candidates", occupiedWaterCandidates);
        return failure;
    }

    static boolean requiresWaterSeal(
            MaidTerrainWorldEvaluator.ClearanceAssessment assessment,
            boolean directFluid, boolean directWater) {
        if (directFluid) {
            return directWater;
        }
        return assessment
                == MaidTerrainWorldEvaluator.ClearanceAssessment.WATER_HAZARD;
    }

    private TickResult placeConstructionBlock(
            MaidActionContext context, BlockPos target,
            MaidTerrainBuilder.Purpose purpose, String failureMessage) {
        MaidTerrainStep activeStep = stepIndex < terrainPath.steps().size()
                ? terrainPath.steps().get(stepIndex) : null;
        TickResult centering = centerForConstruction(
                context, activeStep, target);
        if (centering != null) {
            return centering;
        }
        stopLocomotion(context);
        TickResult playerWait = waitForPlayers(
                context, activeStep, List.of(target),
                "player_blocking_construction");
        if (playerWait != null) {
            return playerWait;
        }
        if (maxPlacements == 0 || placementsUsed >= maxPlacements) {
            return fail(context, ActionEndReason.PATH_NOT_FOUND,
                    "placement_budget_exhausted", true);
        }
        MaidTerrainBuilder.PlacementResult placement = MaidTerrainBuilder.place(
                context.maid(), target, purpose);
        if (placement.placed()) {
            clearPlayerWait();
            placementsUsed++;
            placedEvents.addLast(new PlacedBlock(
                    target, placement.blockId(), purpose));
            phase = purpose == MaidTerrainBuilder.Purpose.SEAL_FLUID
                    ? "sealing_water" : "placing_support";
            JsonObject detail = stepIndex < terrainPath.steps().size()
                    ? stepDetail(terrainPath.steps().get(stepIndex))
                    : new JsonObject();
            detail.addProperty("construction", purpose.name().toLowerCase());
            detail.addProperty("placed_x", target.getX());
            detail.addProperty("placed_y", target.getY());
            detail.addProperty("placed_z", target.getZ());
            detail.addProperty("placed_block", String.valueOf(placement.blockId()));
            detail.addProperty("placements_used", placementsUsed);
            return running(detail);
        }
        if (placement.status() == MaidTerrainBuilder.Status.PLAYER_BODY_CONFLICT
                || placement.status()
                == MaidTerrainBuilder.Status.PLAYER_SUPPORT_CONFLICT) {
            // Builder is the final transaction guard. If it sees a player that
            // entered after the executor's preflight, wait instead of turning
            // temporary occupancy into a permanent path failure.
            return waitForPlayers(context, activeStep, List.of(target),
                    "player_blocking_construction");
        }
        ActionEndReason reason = switch (placement.status()) {
            case NO_SAFE_MATERIAL -> ActionEndReason.TOOL_NOT_FOUND;
            case PLACE_REJECTED -> ActionEndReason.BLOCK_PROTECTED;
            case PLACEMENT_OBSTRUCTED -> ActionEndReason.STUCK;
            default -> ActionEndReason.PATH_NOT_FOUND;
        };
        String message = switch (placement.status()) {
            case NO_SAFE_MATERIAL -> "no_building_material";
            case PLACE_REJECTED -> "placement_protected";
            case PLACEMENT_OBSTRUCTED -> "placement_space_obstructed";
            case FEATURE_DISABLED -> "placement_feature_disabled";
            case CONTEXT_CANNOT_PLACE -> "placement_context_cannot_place";
            case PLACEMENT_STATE_INVALID -> "placement_state_invalid";
            default -> failureMessage;
        };
        TickResult failure = fail(context, reason, message, true);
        failure.detail().addProperty(
                "placement_status", placement.status().name());
        failure.detail().addProperty("placement_detail", placement.detail());
        failure.detail().addProperty("placement_purpose", purpose.name());
        failure.detail().addProperty("placement_x", target.getX());
        failure.detail().addProperty("placement_y", target.getY());
        failure.detail().addProperty("placement_z", target.getZ());
        return failure;
    }

    private TickResult centerForConstruction(
            MaidActionContext context, MaidTerrainStep step, BlockPos target) {
        if (step == null || !context.maid().blockPosition().equals(step.from())) {
            constructionCenterTarget = null;
            constructionCenterStartedAt = Long.MIN_VALUE;
            return null;
        }
        boolean alreadyCentering = target.equals(constructionCenterTarget);
        boolean maidIntersectsTarget = intersectsConstructionTarget(
                context.maid().getBoundingBox(), target);
        if (!alreadyCentering && !maidIntersectsTarget) {
            return null;
        }
        boolean stableAtOrigin = isCenteredAtOrigin(
                context.maid().getX(), context.maid().getZ(),
                step.from(), CONSTRUCTION_CENTER_TOLERANCE)
                && context.maid().onGround()
                && Math.abs(context.maid().getY() - step.from().getY()) <= 0.05D;
        if (stableAtOrigin) {
            stopLocomotion(context);
            Vec3 velocity = context.maid().getDeltaMovement();
            context.maid().setDeltaMovement(0.0D, velocity.y, 0.0D);
            constructionCenterTarget = null;
            constructionCenterStartedAt = Long.MIN_VALUE;
            return null;
        }
        if (!target.equals(constructionCenterTarget)) {
            constructionCenterTarget = target.immutable();
            constructionCenterStartedAt = context.gameTime();
        } else if (context.gameTime() - constructionCenterStartedAt
                >= CONSTRUCTION_CENTER_TIMEOUT_TICKS) {
            constructionCenterTarget = null;
            constructionCenterStartedAt = Long.MIN_VALUE;
            return fail(context, ActionEndReason.STUCK,
                    "maid_could_not_center_before_construction", true);
        }

        phase = "centering_for_construction";
        clearNativePathOwnership(context);
        context.maid().getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                new BlockPosTracker(target));
        context.maid().getMoveControl().setWantedPosition(
                step.from().getX() + 0.5D, step.from().getY(),
                step.from().getZ() + 0.5D, Math.min(speed, 0.55D));
        JsonObject detail = stepDetail(step);
        detail.addProperty("message", "centering_for_construction");
        detail.addProperty("construction_x", target.getX());
        detail.addProperty("construction_y", target.getY());
        detail.addProperty("construction_z", target.getZ());
        detail.addProperty("center_distance", Math.sqrt(
                horizontalDistanceSquared(context.maid().getX(),
                        context.maid().getZ(), step.from())));
        return running(detail);
    }

    static boolean intersectsConstructionTarget(AABB bounds, BlockPos target) {
        if (bounds == null || target == null) {
            return false;
        }
        return bounds.intersects(new AABB(target));
    }

    static boolean isCenteredAtOrigin(
            double x, double z, BlockPos origin, double tolerance) {
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || origin == null || !Double.isFinite(tolerance)
                || tolerance < 0.0D) {
            return false;
        }
        return horizontalDistanceSquared(x, z, origin)
                <= tolerance * tolerance;
    }

    private static double horizontalDistanceSquared(
            double x, double z, BlockPos origin) {
        double dx = origin.getX() + 0.5D - x;
        double dz = origin.getZ() + 0.5D - z;
        return dx * dx + dz * dz;
    }

    private TickResult waitForPlayers(
            MaidActionContext context, MaidTerrainStep step,
            java.util.Collection<BlockPos> positions, String message) {
        MaidTerrainInteractionSafety.Assessment assessment =
                MaidTerrainInteractionSafety.assessWorkZone(
                        context.level(), positions);
        if (assessment.safe()) {
            if (playerBlockedTarget != null
                    && positions.contains(playerBlockedTarget)) {
                clearPlayerWait();
            }
            return null;
        }

        boolean sameConflict = assessment.target().equals(playerBlockedTarget)
                && assessment.conflict() == playerConflict
                && assessment.playerId().equals(blockingPlayerId);
        if (!sameConflict || playerWaitStartedAt == Long.MIN_VALUE) {
            playerWaitStartedAt = context.gameTime();
            playerBlockedTarget = assessment.target().immutable();
            playerConflict = assessment.conflict();
            blockingPlayerId = assessment.playerId();
        }

        if (breaker != null) {
            breaker.stop(context);
            breaker = null;
        }
        stopLocomotion(context);
        stopHorizontalMovement(context);
        // A stopped native/direct move cannot be resumed from its old flag;
        // restart the same validated terrain edge after the player leaves.
        movementStarted = false;
        directFlatMovement = false;
        activeFlatRunEndExclusive = -1;
        arrivalSettleStartedAt = Long.MIN_VALUE;
        controlledDescendRecoveryStartedAt = Long.MIN_VALUE;

        long waited = Math.max(0L, context.gameTime() - playerWaitStartedAt);
        if (waited >= PLAYER_WORK_ZONE_WAIT_TIMEOUT_TICKS) {
            TickResult failure = fail(context, ActionEndReason.STUCK,
                    message, false);
            addPlayerWaitDetail(failure.detail(), assessment, waited);
            return failure;
        }

        phase = "waiting_for_player";
        JsonObject detail = step == null ? new JsonObject() : stepDetail(step);
        detail.addProperty("stage", "waiting_for_player");
        detail.addProperty("message", message);
        addPlayerWaitDetail(detail, assessment, waited);
        return running(detail);
    }

    private static void addPlayerWaitDetail(
            JsonObject detail,
            MaidTerrainInteractionSafety.Assessment assessment,
            long waited) {
        detail.addProperty("player_conflict",
                assessment.conflict().wireName());
        detail.addProperty("blocking_player_id",
                assessment.playerId().toString());
        addPosition(detail, "blocked", assessment.target());
        detail.addProperty("player_wait_ticks", waited);
        detail.addProperty("player_wait_timeout_ticks",
                PLAYER_WORK_ZONE_WAIT_TIMEOUT_TICKS);
    }

    private void clearPlayerWait() {
        playerWaitStartedAt = Long.MIN_VALUE;
        playerBlockedTarget = null;
        playerConflict = null;
        blockingPlayerId = null;
    }

    /**
     * Keeps the maid at the terrain-step origin while gravity blocks settle.
     * Only a FallingBlock that belongs to an observed fall may be appended to
     * the existing clearance work; arbitrary player/world changes still flow
     * into the ordinary TARGET_CHANGED guard below.
     */
    private TickResult stabilizeFallingClearance(
            MaidActionContext context, MaidTerrainStep step) {
        if (movementStarted) {
            return null;
        }

        boolean fallingNow = hasFallingEntityAboveClearance(context, step)
                || hasUnsupportedFallingSource(context, step);
        if (fallingNow) {
            fallingClearanceObserved = true;
            fallingClearanceStableTicks = 0;
            stopLocomotion(context);
            if (fallingClearanceWaitStartedAt == Long.MIN_VALUE) {
                fallingClearanceWaitStartedAt = context.gameTime();
            }
            if (context.gameTime() - fallingClearanceWaitStartedAt
                    >= FALLING_CLEARANCE_SETTLE_TIMEOUT_TICKS) {
                return fail(context, ActionEndReason.STUCK,
                        "falling_block_settle_timeout", true);
            }
            phase = "waiting_for_falling_clearance";
            JsonObject detail = stepDetail(step);
            detail.addProperty("settle_ticks", Math.max(0L,
                    context.gameTime() - fallingClearanceWaitStartedAt));
            detail.addProperty("falling_blocks_cleared", fallingBlocksCleared);
            return running(detail);
        }

        List<BlockPos> fallenObstacles = new ArrayList<>();
        for (BlockPos pos : step.clearance()) {
            if (!isLoadedBuildPosition(context, pos)) {
                return null;
            }
            BlockState state = context.level().getBlockState(pos);
            if (state.getFluidState().isEmpty()
                    && state.getCollisionShape(context.level(), pos).isEmpty()) {
                continue;
            }
            if (!fallingClearanceObserved || !isFallingBlockState(state)) {
                return null;
            }
            MaidTerrainWorldEvaluator.ClearanceAssessment assessment =
                    MaidTerrainWorldEvaluator.assessClearance(
                            context.level(), pos, state);
            if (assessment != MaidTerrainWorldEvaluator.ClearanceAssessment.BREAKABLE) {
                return fail(context, ActionEndReason.PATH_NOT_FOUND,
                        "falling_clearance_became_unsafe", true);
            }
            if (requireCorrectTool && state.requiresCorrectToolForDrops()
                    && !context.maid().getMainHandItem()
                    .isCorrectToolForDrops(state)) {
                return fail(context, ActionEndReason.TOOL_NOT_FOUND,
                        "held_tool_cannot_clear_falling_block", false);
            }
            fallenObstacles.add(pos.immutable());
            plannedBreakStates.put(pos.immutable(), state);
        }

        if (!fallenObstacles.isEmpty()) {
            stopLocomotion(context);
            List<BlockPos> combined = new ArrayList<>(fallenObstacles);
            for (int index = breakIndex; index < pendingBreaks.size(); index++) {
                BlockPos remaining = pendingBreaks.get(index);
                BlockState expected = plannedBreakStates.get(remaining);
                BlockState current = context.level().getBlockState(remaining);
                if (expected != null && current.equals(expected)
                        && (!current.getFluidState().isEmpty()
                        || !current.getCollisionShape(
                        context.level(), remaining).isEmpty())
                        && !combined.contains(remaining)) {
                    combined.add(remaining.immutable());
                }
            }
            sortPendingBreaks(context, combined);
            pendingBreaks = combined;
            breakIndex = 0;
            breaker = null;
            fallingStabilizationRequired = false;
            fallingClearanceStableTicks = 0;
            fallingClearanceWaitStartedAt = context.gameTime();
            phase = "clearing_fallen_blocks";
            JsonObject detail = stepDetail(step);
            detail.addProperty("fallen_blocks_pending", fallenObstacles.size());
            detail.addProperty("falling_blocks_cleared", fallingBlocksCleared);
            return running(detail);
        }

        fallingClearanceWaitStartedAt = Long.MIN_VALUE;
        if (fallingClearanceStableTicks < FALLING_CLEARANCE_STABLE_TICKS) {
            stopLocomotion(context);
            fallingClearanceStableTicks++;
            phase = "stabilizing_clearance";
            JsonObject detail = stepDetail(step);
            detail.addProperty("stable_ticks", fallingClearanceStableTicks);
            detail.addProperty("required_stable_ticks",
                    FALLING_CLEARANCE_STABLE_TICKS);
            return running(detail);
        }
        fallingClearanceObserved = false;
        fallingClearanceStableTicks = 0;
        return null;
    }

    private void sortPendingBreaks(
            MaidActionContext context, List<BlockPos> positions) {
        positions.sort((first, second) -> {
            BlockState firstState = plannedBreakStates.get(first);
            BlockState secondState = plannedBreakStates.get(second);
            if (sameHorizontalColumn(first, second)
                    && isFallingBlockState(firstState)
                    && isFallingBlockState(secondState)
                    && first.getY() != second.getY()) {
                // Clear gravity stacks top-down so breaking a lower cell does
                // not invalidate another pending target in the same column.
                return Integer.compare(second.getY(), first.getY());
            }
            return Double.compare(
                    context.maid().getEyePosition()
                            .distanceToSqr(Vec3.atCenterOf(first)),
                    context.maid().getEyePosition()
                            .distanceToSqr(Vec3.atCenterOf(second)));
        });
    }

    private TickResult moveToStepDestination(MaidActionContext context, MaidTerrainStep step) {
        if (!isStepClearanceOpen(context, step)) {
            return fail(context, ActionEndReason.TARGET_CHANGED,
                    "terrain_step_clearance_is_not_two_blocks_high", true);
        }
        if (!isDestinationStillUsable(context, step.to())) {
            return fail(context, ActionEndReason.TARGET_CHANGED,
                    "terrain_step_destination_changed", true);
        }

        double distance = distance(context.maid(), step.to());
        // Adjacent node centres are only one block apart. A distance-only
        // threshold previously completed a step after roughly 0.15 blocks of
        // travel, so the logical path could run ahead into an uncleared wall.
        // Completing only after the entity really occupies the destination
        // also verifies the correct elevation for ascend/descend steps.
        if (context.maid().blockPosition().equals(step.to())
                && directTurnIsCentered(context, step)
                && (step.kind() != MaidTerrainStep.Kind.DESCEND
                || horizontalDistance(context.maid(), step.to())
                <= DESCEND_CENTER_TOLERANCE)) {
            boolean continueFlatNavigation = canContinueFlatNavigation(context);
            if (!continueFlatNavigation) {
                TickResult settling = settleAtDestination(context, step);
                if (settling != null) {
                    return settling;
                }
            }
            completeStep(context, continueFlatNavigation);
            if (stepIndex >= terrainPath.steps().size()) {
                return arrive(context);
            }
            if (continueFlatNavigation && directFlatMovement) {
                commandDirectWaypoint(context,
                        terrainPath.steps().get(stepIndex).to());
            }
            return running(stepDetail(step));
        }

        // TLM/vanilla path finding does not reliably expose either vertical
        // or diagonal-down adjacent edges. Execute those two terrain edges
        // directly while leaving all vertical motion to normal gravity.
        if (step.kind() == MaidTerrainStep.Kind.DIG_DOWN) {
            return descendByGravity(context, step, distance);
        }
        if (step.kind() == MaidTerrainStep.Kind.DESCEND) {
            return descendDiagonallyControlled(context, step, distance);
        }
        if (isContinuousFlatStep(context, step)) {
            return moveDirectFlatWaypoint(context, step, distance);
        }

        if (!movementStarted) {
            phase = "moving";
            BlockPos nativeTarget = continuousFlatTarget(context, stepIndex);
            Path nativePath = context.maid().getNavigation().createPath(nativeTarget, 0);
            if (!nativeTarget.equals(step.to())
                    && !isStraightCorridorPath(nativePath, step.from(), nativeTarget)) {
                activeFlatRunEndExclusive = stepIndex + 1;
                nativeTarget = step.to();
                nativePath = context.maid().getNavigation().createPath(nativeTarget, 0);
            }
            if (nativePath == null || nativePath.getNodeCount() == 0 || !nativePath.canReach()) {
                return fail(context, ActionEndReason.PATH_NOT_FOUND,
                        "native_navigation_cannot_reach_terrain_step", true);
            }
            context.maid().getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                    new BlockPosTracker(step.to()));
            if (!context.maid().getNavigation().moveTo(nativePath, speed)) {
                return fail(context, ActionEndReason.PATH_NOT_FOUND,
                        "native_navigation_rejected_terrain_step", true);
            }
            nativePathStarts++;
            movementStarted = true;
            movementStartDistance = distance;
            windowStartDistance = distance;
            windowStartedAt = context.gameTime();
            return running(stepDetail(step));
        }

        context.maid().getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                new BlockPosTracker(step.to()));
        if (context.maid().getNavigation().isDone()) {
            return fail(context, ActionEndReason.PATH_NOT_FOUND,
                    "native_navigation_finished_before_terrain_step", true);
        }
        if (context.gameTime() - windowStartedAt >= STUCK_WINDOW_TICKS) {
            if (windowStartDistance - distance < REQUIRED_STEP_PROGRESS) {
                return fail(context, ActionEndReason.STUCK,
                        "terrain_step_made_no_progress", true);
            }
            windowStartDistance = distance;
            windowStartedAt = context.gameTime();
        }

        JsonObject detail = stepDetail(step);
        detail.addProperty("distance", distance);
        detail.addProperty("step_progress", progress(distance));
        return running(detail);
    }

    /**
     * Follows the already validated adjacent terrain waypoints directly. TLM's
     * MaidMoveControl consumes MOVE_TO once per tick, so the target is renewed
     * every tick. This avoids asking vanilla A* to re-approve each adjacent
     * clear cell and avoids a stop/restart cycle at turns.
     */
    private TickResult moveDirectFlatWaypoint(
            MaidActionContext context, MaidTerrainStep step, double distance) {
        if (!movementStarted || !directFlatMovement) {
            stopLocomotion(context);
            movementStarted = true;
            directFlatMovement = true;
            directWaypointStarts++;
            movementStartDistance = distance;
            windowStartDistance = distance;
            windowStartedAt = context.gameTime();
            activeFlatRunEndExclusive = -1;
        }
        phase = "moving_direct_waypoint";
        clearNativePathOwnership(context);
        commandDirectWaypoint(context, step.to());
        if (context.gameTime() - windowStartedAt >= STUCK_WINDOW_TICKS) {
            if (windowStartDistance - distance < REQUIRED_STEP_PROGRESS) {
                return fail(context, ActionEndReason.STUCK,
                        "direct_waypoint_made_no_progress", true);
            }
            windowStartDistance = distance;
            windowStartedAt = context.gameTime();
        }
        JsonObject detail = stepDetail(step);
        detail.addProperty("movement_controller", "maid_direct_waypoint");
        detail.addProperty("distance", distance);
        detail.addProperty("step_progress", progress(distance));
        return running(detail);
    }

    private void commandDirectWaypoint(
            MaidActionContext context, BlockPos target) {
        context.maid().getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                new BlockPosTracker(target));
        context.maid().getMoveControl().setWantedPosition(
                target.getX() + 0.5D, target.getY(),
                target.getZ() + 0.5D, speed);
    }

    private boolean directTurnIsCentered(
            MaidActionContext context, MaidTerrainStep current) {
        if (!directFlatMovement) {
            return true;
        }
        boolean arrived = directWaypointReached(
                context.maid().getX(), context.maid().getZ(), current,
                DIRECT_TURN_CENTER_TOLERANCE);
        if (stepIndex + 1 >= terrainPath.steps().size()) {
            return arrived;
        }
        MaidTerrainStep next = terrainPath.steps().get(stepIndex + 1);
        if (isDirectFlatStepGeometry(next)
                && sameHorizontalDirection(current, next)) {
            return true;
        }
        double turnTolerance = DIRECT_TURN_CENTER_TOLERANCE;
        if (isRightAngleFlatTurn(current, next)
                && isContinuousFlatStep(context, next)
                && isRightAngleTurnSweepOpen(context, current, next)) {
            turnTolerance = DIRECT_RIGHT_ANGLE_TURN_TOLERANCE;
        }
        return directTurnWaypointReached(
                context.maid().getX(), context.maid().getZ(), current,
                turnTolerance);
    }

    static boolean isRightAngleFlatTurn(
            MaidTerrainStep current, MaidTerrainStep next) {
        if (!isDirectFlatStepGeometry(current)
                || !isDirectFlatStepGeometry(next)
                || !current.to().equals(next.from())) {
            return false;
        }
        int currentX = current.to().getX() - current.from().getX();
        int currentZ = current.to().getZ() - current.from().getZ();
        int nextX = next.to().getX() - next.from().getX();
        int nextZ = next.to().getZ() - next.from().getZ();
        return currentX * nextX + currentZ * nextZ == 0;
    }

    static BlockPos rightAngleInnerCorner(
            MaidTerrainStep current, MaidTerrainStep next) {
        if (!isRightAngleFlatTurn(current, next)) {
            return null;
        }
        int nextX = next.to().getX() - next.from().getX();
        int nextZ = next.to().getZ() - next.from().getZ();
        return current.from().offset(nextX, 0, nextZ);
    }

    private static boolean isRightAngleTurnSweepOpen(
            MaidActionContext context,
            MaidTerrainStep current,
            MaidTerrainStep next) {
        BlockPos innerCorner = rightAngleInnerCorner(current, next);
        return innerCorner != null
                && isTurnSweepCellOpen(context, innerCorner)
                && isTurnSweepCellOpen(context, innerCorner.above());
    }

    private static boolean isTurnSweepCellOpen(
            MaidActionContext context, BlockPos pos) {
        if (!isLoadedBuildPosition(context, pos)) {
            return false;
        }
        BlockState state = context.level().getBlockState(pos);
        return state.getFluidState().isEmpty()
                && state.getCollisionShape(context.level(), pos).isEmpty();
    }

    static boolean directWaypointReached(
            double maidX, double maidZ, MaidTerrainStep step, double tolerance) {
        if (!Double.isFinite(maidX) || !Double.isFinite(maidZ)
                || !Double.isFinite(tolerance) || tolerance < 0.0D
                || !isDirectFlatStepGeometry(step)) {
            return false;
        }
        int dx = step.to().getX() - step.from().getX();
        int dz = step.to().getZ() - step.from().getZ();
        double remainingX = step.to().getX() + 0.5D - maidX;
        double remainingZ = step.to().getZ() + 0.5D - maidZ;
        double forwardRemaining = remainingX * dx + remainingZ * dz;
        double lateralError = Math.abs(remainingX * dz - remainingZ * dx);
        return forwardRemaining <= tolerance && lateralError <= tolerance;
    }

    static boolean directTurnWaypointReached(
            double maidX, double maidZ, MaidTerrainStep step, double tolerance) {
        if (!Double.isFinite(maidX) || !Double.isFinite(maidZ)
                || !Double.isFinite(tolerance) || tolerance < 0.0D
                || !isDirectFlatStepGeometry(step)) {
            return false;
        }
        int dx = step.to().getX() - step.from().getX();
        int dz = step.to().getZ() - step.from().getZ();
        double remainingX = step.to().getX() + 0.5D - maidX;
        double remainingZ = step.to().getZ() + 0.5D - maidZ;
        double forwardRemaining = remainingX * dx + remainingZ * dz;
        double lateralError = Math.abs(remainingX * dz - remainingZ * dx);
        double overshootTolerance = Math.min(
                tolerance, DIRECT_TURN_CENTER_TOLERANCE);
        return forwardRemaining <= tolerance
                && forwardRemaining >= -overshootTolerance
                && lateralError <= tolerance;
    }

    /**
     * Crossing a block boundary is not a stable arrival: native navigation
     * can leave horizontal velocity on the entity, and a descending maid may
     * still be airborne. Stabilize for at least one server tick before the
     * next terrain segment captures its origin.
     */
    private TickResult settleAtDestination(MaidActionContext context, MaidTerrainStep step) {
        stopNativeNavigation(context);
        Vec3 velocity = context.maid().getDeltaMovement();
        context.maid().setDeltaMovement(0.0D, velocity.y, 0.0D);
        if (arrivalSettleStartedAt == Long.MIN_VALUE) {
            arrivalSettleStartedAt = context.gameTime();
            phase = "settling";
            return running(settlingDetail(context, step));
        }
        if (!context.maid().onGround()) {
            if (context.gameTime() - arrivalSettleStartedAt >= ARRIVAL_SETTLE_TIMEOUT_TICKS) {
                return fail(context, ActionEndReason.STUCK,
                        "maid_did_not_settle_at_terrain_step_destination", true);
            }
            phase = "settling";
            return running(settlingDetail(context, step));
        }
        context.maid().setDeltaMovement(Vec3.ZERO);
        return null;
    }

    private JsonObject settlingDetail(MaidActionContext context, MaidTerrainStep step) {
        JsonObject detail = stepDetail(step);
        detail.addProperty("on_ground", context.maid().onGround());
        detail.addProperty("settle_ticks", Math.max(0L,
                context.gameTime() - arrivalSettleStartedAt));
        return detail;
    }

    private TickResult descendByGravity(MaidActionContext context, MaidTerrainStep step, double distance) {
        phase = "descending";
        if (!movementStarted) {
            stopNativeNavigation(context);
            movementStarted = true;
            movementStartDistance = distance;
            windowStartDistance = distance;
            windowStartedAt = context.gameTime();
        } else {
            context.maid().getNavigation().stop();
        }

        // Remove residual horizontal steering while preserving gravity/fall
        // velocity, keeping the maid above the planned one-block shaft.
        Vec3 velocity = context.maid().getDeltaMovement();
        context.maid().setDeltaMovement(0.0D, velocity.y, 0.0D);
        context.maid().getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                new BlockPosTracker(step.to()));

        if (context.gameTime() - windowStartedAt >= STUCK_WINDOW_TICKS) {
            if (windowStartDistance - distance < REQUIRED_STEP_PROGRESS) {
                return fail(context, ActionEndReason.STUCK,
                        "maid_did_not_descend_after_digging_down", true);
            }
            windowStartDistance = distance;
            windowStartedAt = context.gameTime();
        }
        JsonObject detail = stepDetail(step);
        detail.addProperty("distance", distance);
        detail.addProperty("step_progress", progress(distance));
        return running(detail);
    }

    /**
     * Walks off a one-block ledge without asking native navigation to create
     * a path for an edge it commonly rejects. Only X/Z velocity is controlled;
     * gravity and collision remain authoritative for the one-block fall.
     */
    private TickResult descendDiagonallyControlled(
            MaidActionContext context, MaidTerrainStep step, double distance) {
        if (!isControlledDescendGeometry(step)) {
            return fail(context, ActionEndReason.INTERNAL_ERROR,
                    "invalid_controlled_descend_geometry", false);
        }
        BlockPos live = context.maid().blockPosition();
        if (isRecoverableControlledDescendUpwardDisplacement(
                step, live, context.maid().onGround(), movementStarted)) {
            if (controlledDescendRecoveryStartedAt == Long.MIN_VALUE) {
                controlledDescendRecoveryStartedAt = context.gameTime();
            }
            if (context.gameTime() - controlledDescendRecoveryStartedAt
                    >= CONTROLLED_DESCEND_UPWARD_RECOVERY_TICKS) {
                return fail(context, ActionEndReason.STUCK,
                        "controlled_descend_upward_recovery_timed_out", true);
            }
            stopNativeNavigation(context);
            Vec3 velocity = context.maid().getDeltaMovement();
            // Cancel a residual jump/MoveControl impulse and let collision plus
            // gravity return the maid to the validated source cell. Never
            // steer horizontally from the unplanned elevation.
            context.maid().setDeltaMovement(
                    0.0D, Math.min(0.0D, velocity.y), 0.0D);
            phase = "recovering_above_descend_origin";
            JsonObject detail = stepDetail(step);
            detail.addProperty("movement_controller",
                    "controlled_descend_upward_recovery");
            detail.addProperty("recovery_ticks", Math.max(0L,
                    context.gameTime() - controlledDescendRecoveryStartedAt));
            return running(detail);
        }
        if (live.equals(step.from())) {
            controlledDescendRecoveryStartedAt = Long.MIN_VALUE;
        }
        if (sameHorizontalColumn(live, step.from())) {
            if (live.getY() != step.from().getY()) {
                return fail(context, ActionEndReason.STUCK,
                        live.getY() < step.from().getY()
                                ? "maid_fell_below_controlled_descend_origin"
                                : "maid_moved_above_controlled_descend_origin",
                        true);
            }
            BlockPos sourceSupportPos = step.from().below();
            if (!isLoadedBuildPosition(context, sourceSupportPos)
                    || !MaidTerrainWorldEvaluator.isSafeStandSupport(
                    context.level(), sourceSupportPos,
                    context.level().getBlockState(sourceSupportPos))) {
                return fail(context, ActionEndReason.TARGET_CHANGED,
                        "controlled_descend_origin_support_changed", true);
            }
        }
        if (!isControlledDescendPosition(step, live)) {
            return fail(context, ActionEndReason.STUCK,
                    live.getY() < step.to().getY()
                            ? "maid_fell_below_controlled_descend_destination"
                            : "maid_left_controlled_descend_columns",
                    true);
        }

        phase = "descending_diagonally";
        if (!movementStarted) {
            if (!live.equals(step.from()) || !context.maid().onGround()) {
                return fail(context, ActionEndReason.STUCK,
                        "maid_not_grounded_at_controlled_descend_origin", true);
            }
            stopNativeNavigation(context);
            movementStarted = true;
            movementStartDistance = distance;
            windowStartDistance = distance;
            windowStartedAt = context.gameTime();
        } else {
            context.maid().getNavigation().stop();
        }

        Vec3 target = Vec3.atBottomCenterOf(step.to());
        double dx = target.x - context.maid().getX();
        double dz = target.z - context.maid().getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        Vec3 velocity = context.maid().getDeltaMovement();
        if (horizontalDistance <= DESCEND_CENTER_TOLERANCE) {
            context.maid().setDeltaMovement(0.0D, velocity.y, 0.0D);
        } else {
            double controlledSpeed = Math.max(MIN_CONTROLLED_DESCEND_SPEED,
                    Math.min(MAX_CONTROLLED_DESCEND_SPEED, 0.08D + speed * 0.10D));
            double horizontalSpeed = Math.min(controlledSpeed, horizontalDistance);
            context.maid().setDeltaMovement(
                    dx / horizontalDistance * horizontalSpeed,
                    velocity.y,
                    dz / horizontalDistance * horizontalSpeed);
        }
        context.maid().getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                new BlockPosTracker(step.to()));

        if (context.gameTime() - windowStartedAt >= STUCK_WINDOW_TICKS) {
            if (windowStartDistance - distance < REQUIRED_STEP_PROGRESS) {
                return fail(context, ActionEndReason.STUCK,
                        "controlled_descend_made_no_progress", true);
            }
            windowStartDistance = distance;
            windowStartedAt = context.gameTime();
        }
        JsonObject detail = stepDetail(step);
        detail.addProperty("movement_controller", "controlled_descend");
        detail.addProperty("distance", distance);
        detail.addProperty("horizontal_distance", horizontalDistance);
        detail.addProperty("step_progress", progress(distance));
        return running(detail);
    }

    static boolean isControlledDescendGeometry(MaidTerrainStep step) {
        int dx = Math.abs(step.to().getX() - step.from().getX());
        int dy = step.to().getY() - step.from().getY();
        int dz = Math.abs(step.to().getZ() - step.from().getZ());
        return step.kind() == MaidTerrainStep.Kind.DESCEND
                && dy == -1
                && dx + dz == 1;
    }

    static boolean isRecoverableControlledDescendUpwardDisplacement(
            MaidTerrainStep step, BlockPos live, boolean onGround,
            boolean movementStarted) {
        return movementStarted
                && !onGround
                && sameHorizontalColumn(live, step.from())
                && live.getY() == step.from().getY() + 1;
    }

    private static boolean isControlledDescendPosition(MaidTerrainStep step, BlockPos live) {
        if (live.getY() < step.to().getY() || live.getY() > step.from().getY()) {
            return false;
        }
        boolean inFromColumn = sameHorizontalColumn(live, step.from());
        boolean inToColumn = sameHorizontalColumn(live, step.to());
        return inFromColumn || inToColumn;
    }

    private static boolean sameHorizontalColumn(BlockPos first, BlockPos second) {
        return first.getX() == second.getX() && first.getZ() == second.getZ();
    }

    private void completeStep(MaidActionContext context,
                              boolean continueFlatNavigation) {
        MaidTerrainStep completed = terrainPath.steps().get(stepIndex);
        if (!continueFlatNavigation) {
            context.maid().getNavigation().stop();
            context.maid().setDeltaMovement(Vec3.ZERO);
            hardStops++;
            activeFlatRunEndExclusive = -1;
            directFlatMovement = false;
        } else {
            chainedTraverseSteps++;
        }
        if (debugPath != null && !debugPath.isDone()) {
            debugPath.advance();
        }
        completedStepEvents.addLast(completed.to().immutable());
        stepIndex++;
        pendingBreaks = List.of();
        breakIndex = 0;
        breaker = null;
        movementStarted = continueFlatNavigation;
        movementStartDistance = 0.0D;
        if (continueFlatNavigation && stepIndex < terrainPath.steps().size()) {
            double nextDistance = distance(
                    context.maid(), terrainPath.steps().get(stepIndex).to());
            movementStartDistance = nextDistance;
            windowStartDistance = nextDistance;
            windowStartedAt = context.gameTime();
        }
        arrivalSettleStartedAt = Long.MIN_VALUE;
        controlledDescendRecoveryStartedAt = Long.MIN_VALUE;
        fallingClearanceWaitStartedAt = Long.MIN_VALUE;
        fallingClearanceStableTicks = 0;
        fallingClearanceObserved = false;
        fallingStabilizationRequired = false;
        phase = "step_complete";
    }

    /**
     * Extends one native path across consecutive clear, supported level cells.
     * Terrain semantics still advance one logical edge at a time; only the
     * stop/zero/restart cycle is removed.
     */
    private BlockPos continuousFlatTarget(MaidActionContext context, int fromIndex) {
        MaidTerrainStep first = terrainPath.steps().get(fromIndex);
        activeFlatRunEndExclusive = fromIndex + 1;
        if (!isContinuousFlatStep(context, first)) {
            return first.to();
        }
        BlockPos target = first.to();
        MaidTerrainStep previous = first;
        int limit = Math.min(terrainPath.steps().size(),
                fromIndex + MAX_CONTINUOUS_FLAT_STEPS);
        for (int index = fromIndex + 1; index < limit; index++) {
            MaidTerrainStep next = terrainPath.steps().get(index);
            if (!canChainFlatSteps(previous, next)
                    || !isContinuousFlatStep(context, next)) {
                break;
            }
            target = next.to();
            previous = next;
            activeFlatRunEndExclusive = index + 1;
        }
        return target;
    }

    private boolean canContinueFlatNavigation(MaidActionContext context) {
        if (stepIndex + 1 >= terrainPath.steps().size()) {
            return false;
        }
        MaidTerrainStep current = terrainPath.steps().get(stepIndex);
        MaidTerrainStep next = terrainPath.steps().get(stepIndex + 1);
        if (directFlatMovement) {
            return current.kind() == MaidTerrainStep.Kind.TRAVERSE
                    && current.to().equals(next.from())
                    && isContinuousFlatStep(context, next);
        }
        if (stepIndex + 1 >= activeFlatRunEndExclusive
                || context.maid().getNavigation().isDone()) {
            return false;
        }
        return current.kind() == MaidTerrainStep.Kind.TRAVERSE
                && canChainFlatSteps(current, next)
                && isContinuousFlatStep(context, next);
    }

    static boolean canChainFlatSteps(
            MaidTerrainStep current, MaidTerrainStep next) {
        return current.kind() == MaidTerrainStep.Kind.TRAVERSE
                && next.kind() == MaidTerrainStep.Kind.TRAVERSE
                && current.to().equals(next.from())
                && sameHorizontalDirection(current, next)
                && current.from().getY() == current.to().getY()
                && next.from().getY() == next.to().getY()
                && current.toBreak().isEmpty()
                && next.toBreak().isEmpty();
    }

    private static boolean sameHorizontalDirection(
            MaidTerrainStep first, MaidTerrainStep second) {
        int firstX = first.to().getX() - first.from().getX();
        int firstZ = first.to().getZ() - first.from().getZ();
        int secondX = second.to().getX() - second.from().getX();
        int secondZ = second.to().getZ() - second.from().getZ();
        return Math.abs(firstX) + Math.abs(firstZ) == 1
                && Math.abs(secondX) + Math.abs(secondZ) == 1
                && firstX == secondX && firstZ == secondZ;
    }

    private int synchronizeReachedFlatSteps(MaidActionContext context) {
        if (!movementStarted || stepIndex >= terrainPath.steps().size()) {
            return 0;
        }
        BlockPos live = context.maid().blockPosition();
        int reachedIndex = -1;
        MaidTerrainStep previous = terrainPath.steps().get(stepIndex);
        int runEnd = Math.min(terrainPath.steps().size(),
                activeFlatRunEndExclusive < 0
                        ? stepIndex + 1 : activeFlatRunEndExclusive);
        for (int index = stepIndex; index < runEnd; index++) {
            MaidTerrainStep candidate = terrainPath.steps().get(index);
            if (index > stepIndex && !canChainFlatSteps(previous, candidate)) {
                break;
            }
            if (!isContinuousFlatStep(context, candidate)) {
                break;
            }
            if (index > stepIndex && candidate.to().equals(live)) {
                reachedIndex = index;
            }
            previous = candidate;
        }
        int completed = 0;
        // Catch up only the cells strictly before the one currently occupied.
        // The occupied cell still goes through the normal arrival/grounded
        // checks below, especially at a run end or before a turn.
        while (stepIndex < reachedIndex) {
            completeStep(context, true);
            completed++;
        }
        return completed;
    }

    private boolean isContinuousFlatStep(
            MaidActionContext context, MaidTerrainStep step) {
        return isDirectFlatStepGeometry(step)
                && isStepClearanceOpen(context, step)
                && isDestinationStillUsable(context, step.to());
    }

    static boolean isDirectFlatStepGeometry(MaidTerrainStep step) {
        int dx = Math.abs(step.to().getX() - step.from().getX());
        int dz = Math.abs(step.to().getZ() - step.from().getZ());
        return step.kind() == MaidTerrainStep.Kind.TRAVERSE
                && step.from().getY() == step.to().getY()
                && dx + dz == 1
                && step.toBreak().isEmpty();
    }

    private boolean activeFlatRunStillValid(MaidActionContext context) {
        int runEnd = Math.min(terrainPath.steps().size(), activeFlatRunEndExclusive);
        for (int index = stepIndex; index < runEnd; index++) {
            if (!isContinuousFlatStep(context, terrainPath.steps().get(index))) {
                return false;
            }
        }
        return true;
    }

    static boolean isStraightCorridorPath(Path path, BlockPos from, BlockPos target) {
        if (path == null || path.getNodeCount() == 0
                || from.getY() != target.getY()) {
            return false;
        }
        int dx = Integer.compare(target.getX(), from.getX());
        int dz = Integer.compare(target.getZ(), from.getZ());
        if ((dx == 0) == (dz == 0)) {
            return false;
        }
        int previous = dx != 0 ? from.getX() : from.getZ();
        for (int index = 0; index < path.getNodeCount(); index++) {
            Node node = path.getNode(index);
            if (node.y != from.getY()
                    || (dx != 0 && node.z != from.getZ())
                    || (dz != 0 && node.x != from.getX())) {
                return false;
            }
            int coordinate = dx != 0 ? node.x : node.z;
            int direction = dx != 0 ? dx : dz;
            if ((coordinate - previous) * direction < 0
                    || (coordinate - (dx != 0 ? target.getX() : target.getZ()))
                    * direction > 0) {
                return false;
            }
            previous = coordinate;
        }
        Node end = path.getNode(path.getNodeCount() - 1);
        return end.x == target.getX() && end.y == target.getY()
                && end.z == target.getZ();
    }

    private TickResult arrive(MaidActionContext context) {
        stopNativeNavigation(context);
        MaidPathDebugService.clear(context.maid().getUUID());
        terminal = true;
        phase = "arrived";
        return new TickResult(Outcome.ARRIVED, null, false, diagnostics());
    }

    private TickResult fail(MaidActionContext context, ActionEndReason reason,
                            String message, boolean replanRecommended) {
        if (breaker != null) {
            breaker.stop(context);
            breaker = null;
        }
        stopNativeNavigation(context);
        stopHorizontalMovement(context);
        MaidPathDebugService.clear(context.maid().getUUID());
        terminal = true;
        phase = "failed";
        lastFailure = reason == null ? ActionEndReason.INTERNAL_ERROR : reason;
        TickResult failure = failed(lastFailure, message, replanRecommended);
        addPosition(failure.detail(), "actual", context.maid().blockPosition());
        failure.detail().addProperty("actual_x_exact", context.maid().getX());
        failure.detail().addProperty("actual_y_exact", context.maid().getY());
        failure.detail().addProperty("actual_z_exact", context.maid().getZ());
        return failure;
    }

    private TickResult failed(ActionEndReason reason, String message, boolean replanRecommended) {
        JsonObject detail = diagnostics();
        detail.addProperty("message", message);
        detail.addProperty("replan_recommended", replanRecommended);
        if (stepIndex < terrainPath.steps().size()) {
            MaidTerrainStep step = terrainPath.steps().get(stepIndex);
            detail.addProperty("step_kind", step.kind().name());
            addPosition(detail, "from", step.from());
            addPosition(detail, "to", step.to());
        }
        return new TickResult(Outcome.FAILED, reason, replanRecommended, detail);
    }

    private TickResult running(JsonObject detail) {
        JsonObject combined = diagnostics();
        if (detail != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : detail.entrySet()) {
                combined.add(entry.getKey(), entry.getValue());
            }
        }
        return new TickResult(Outcome.RUNNING, null, false, combined);
    }

    private JsonObject stepDetail(MaidTerrainStep step) {
        JsonObject detail = new JsonObject();
        detail.addProperty("step_kind", step.kind().name());
        addPosition(detail, "from", step.from());
        addPosition(detail, "to", step.to());
        detail.addProperty("clearance_cells", step.clearance().size());
        detail.addProperty("obstacles_total", step.toBreak().size());
        detail.addProperty("obstacles_processed", breakIndex);
        return detail;
    }

    private void publishDebug(MaidActionContext context, boolean force) {
        if (debugPath != null && !debugPath.isDone()) {
            MaidPathDebugService.publishIfNeeded(context.maid(), debugPath, context.gameTime(), force);
        }
    }

    private void stopNativeNavigation(MaidActionContext context) {
        stopLocomotion(context);
        context.maid().getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        context.maid().setSwingingArms(false);
    }

    private static void stopLocomotion(MaidActionContext context) {
        clearNativePathOwnership(context);
        context.maid().getMoveControl().setWantedPosition(
                context.maid().getX(), context.maid().getY(),
                context.maid().getZ(), 0.0D);
        context.maid().setSpeed(0.0F);
        context.maid().setXxa(0.0F);
        context.maid().setZza(0.0F);
    }

    private static void clearNativePathOwnership(MaidActionContext context) {
        context.maid().getNavigation().stop();
        context.maid().getBrain().eraseMemory(MemoryModuleType.PATH);
        context.maid().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    private static void stopHorizontalMovement(MaidActionContext context) {
        Vec3 velocity = context.maid().getDeltaMovement();
        context.maid().setDeltaMovement(0.0D, velocity.y, 0.0D);
    }

    private static Path createDebugPath(MaidTerrainPath path) {
        if (path.steps().isEmpty()) {
            return null;
        }
        List<Node> nodes = new ArrayList<>(path.steps().size() + 1);
        BlockPos from = path.steps().getFirst().from();
        nodes.add(new Node(from.getX(), from.getY(), from.getZ()));
        for (MaidTerrainStep step : path.steps()) {
            BlockPos to = step.to();
            nodes.add(new Node(to.getX(), to.getY(), to.getZ()));
        }
        return new Path(nodes, path.target(), true);
    }

    private static boolean isDestinationStillUsable(MaidActionContext context, BlockPos destination) {
        if (!isLoadedBuildPosition(context, destination)
                || !isLoadedBuildPosition(context, destination.above())
                || !isLoadedBuildPosition(context, destination.below())) {
            return false;
        }
        BlockState feet = context.level().getBlockState(destination);
        BlockState head = context.level().getBlockState(destination.above());
        BlockPos supportPos = destination.below();
        BlockState support = context.level().getBlockState(supportPos);
        return feet.getCollisionShape(context.level(), destination).isEmpty()
                && head.getCollisionShape(context.level(), destination.above()).isEmpty()
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && MaidTerrainWorldEvaluator.isSafeStandSupport(
                context.level(), supportPos, support);
    }

    private static boolean isStepClearanceOpen(MaidActionContext context, MaidTerrainStep step) {
        for (BlockPos pos : step.clearance()) {
            if (!isLoadedBuildPosition(context, pos)) {
                return false;
            }
            BlockState state = context.level().getBlockState(pos);
            if (!state.getFluidState().isEmpty()
                    || !state.getCollisionShape(context.level(), pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    static boolean isFallingBlockState(BlockState state) {
        return state != null && state.getBlock() instanceof FallingBlock;
    }

    private static boolean hasUnsupportedFallingSource(
            MaidActionContext context, MaidTerrainStep step) {
        for (BlockPos top : clearanceColumnTops(step)) {
            BlockPos source = top.above();
            if (!isLoadedBuildPosition(context, source)) {
                continue;
            }
            BlockState state = context.level().getBlockState(source);
            if (isFallingBlockState(state)
                    && FallingBlock.isFree(context.level().getBlockState(source.below()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFallingEntityAboveClearance(
            MaidActionContext context, MaidTerrainStep step) {
        int maxY = Math.min(context.level().getMaxBuildHeight(),
                step.clearance().stream().mapToInt(BlockPos::getY).max()
                        .orElse(step.to().getY()) + FALLING_ENTITY_SCAN_HEIGHT + 1);
        int minY = step.clearance().stream().mapToInt(BlockPos::getY).min()
                .orElse(step.to().getY());
        for (BlockPos top : clearanceColumnTops(step)) {
            AABB column = new AABB(
                    top.getX() - 0.05D, minY, top.getZ() - 0.05D,
                    top.getX() + 1.05D, maxY, top.getZ() + 1.05D);
            if (!context.level().getEntitiesOfClass(
                    FallingBlockEntity.class, column).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> clearanceColumnTops(MaidTerrainStep step) {
        List<BlockPos> tops = new ArrayList<>();
        for (BlockPos candidate : step.clearance()) {
            boolean highest = true;
            for (BlockPos other : step.clearance()) {
                if (sameHorizontalColumn(candidate, other)
                        && other.getY() > candidate.getY()) {
                    highest = false;
                    break;
                }
            }
            if (highest) {
                tops.add(candidate.immutable());
            }
        }
        return tops;
    }

    private static boolean isLoadedBuildPosition(MaidActionContext context, BlockPos pos) {
        return pos.getY() >= context.level().getMinBuildHeight()
                && pos.getY() < context.level().getMaxBuildHeight()
                && context.level().hasChunkAt(pos);
    }

    private double progress(double distance) {
        if (movementStartDistance <= 1.0E-6D) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(0.99D,
                (movementStartDistance - distance) / movementStartDistance));
    }

    private static double distance(EntityMaid maid, BlockPos pos) {
        return maid.position().distanceTo(Vec3.atBottomCenterOf(pos));
    }

    private static double horizontalDistance(EntityMaid maid, BlockPos pos) {
        Vec3 target = Vec3.atBottomCenterOf(pos);
        double dx = target.x - maid.getX();
        double dz = target.z - maid.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void addPosition(JsonObject detail, String prefix, BlockPos pos) {
        detail.addProperty(prefix + "_x", pos.getX());
        detail.addProperty(prefix + "_y", pos.getY());
        detail.addProperty(prefix + "_z", pos.getZ());
    }

    public enum Outcome {
        RUNNING,
        ARRIVED,
        FAILED
    }

    /** A committed route-clear event retaining the pre-break block state. */
    public record ClearedBlock(BlockPos pos, BlockState state) {
        public ClearedBlock {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            Objects.requireNonNull(state, "state");
        }
    }

    /** A committed construction event backed by one real inventory item. */
    public record PlacedBlock(
            BlockPos pos,
            ResourceLocation blockId,
            MaidTerrainBuilder.Purpose purpose) {
        public PlacedBlock {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(purpose, "purpose");
        }
    }

    public record TickResult(
            Outcome outcome,
            ActionEndReason reason,
            boolean replanRecommended,
            JsonObject detail
    ) {
        public TickResult {
            Objects.requireNonNull(outcome, "outcome");
            detail = detail == null ? new JsonObject() : detail;
        }
    }
}
