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

    private Path debugPath;
    private int stepIndex;
    private List<BlockPos> pendingBreaks = List.of();
    private int breakIndex;
    private MaidProgressiveBlockBreaker breaker;
    private boolean movementStarted;
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

        MaidTerrainStep step = terrainPath.steps().get(stepIndex);
        if (!movementStarted && breaker == null && breakIndex == 0
                && !context.maid().blockPosition().equals(step.from())) {
            return fail(context, ActionEndReason.TARGET_CHANGED,
                    "maid_is_no_longer_at_terrain_step_origin", true);
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
        boolean directWater = state.getFluidState()
                .is(net.minecraft.tags.FluidTags.WATER);
        if ((!directWater && !updateExpectedObstacle)
                || (!state.getFluidState().isEmpty() && !directWater)
                || assessment
                == MaidTerrainWorldEvaluator.ClearanceAssessment.LAVA_HAZARD) {
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
        for (BlockPos target : candidates) {
            BlockState fluid = context.level().getBlockState(target);
            if (!fluid.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                    || target.equals(step.from())
                    || target.equals(step.from().above())) {
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
        return fail(context, ActionEndReason.PATH_NOT_FOUND,
                "water_seal_failed", true);
    }

    private TickResult placeConstructionBlock(
            MaidActionContext context, BlockPos target,
            MaidTerrainBuilder.Purpose purpose, String failureMessage) {
        stopLocomotion(context);
        if (maxPlacements == 0 || placementsUsed >= maxPlacements) {
            return fail(context, ActionEndReason.PATH_NOT_FOUND,
                    "placement_budget_exhausted", true);
        }
        MaidTerrainBuilder.PlacementResult placement = MaidTerrainBuilder.place(
                context.maid(), target, purpose);
        if (placement.placed()) {
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
        ActionEndReason reason = switch (placement.status()) {
            case NO_SAFE_MATERIAL -> ActionEndReason.TOOL_NOT_FOUND;
            case PLACE_REJECTED -> ActionEndReason.BLOCK_PROTECTED;
            default -> ActionEndReason.PATH_NOT_FOUND;
        };
        String message = switch (placement.status()) {
            case NO_SAFE_MATERIAL -> "no_building_material";
            case PLACE_REJECTED -> "placement_protected";
            default -> failureMessage;
        };
        return fail(context, reason, message, true);
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
                && (step.kind() != MaidTerrainStep.Kind.DESCEND
                || horizontalDistance(context.maid(), step.to())
                <= DESCEND_CENTER_TOLERANCE)) {
            TickResult settling = settleAtDestination(context, step);
            if (settling != null) {
                return settling;
            }
            completeStep(context);
            if (stepIndex >= terrainPath.steps().size()) {
                return arrive(context);
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

        if (!movementStarted) {
            phase = "moving";
            Path nativePath = context.maid().getNavigation().createPath(step.to(), 0);
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

    private void completeStep(MaidActionContext context) {
        context.maid().getNavigation().stop();
        context.maid().setDeltaMovement(Vec3.ZERO);
        if (debugPath != null && !debugPath.isDone()) {
            debugPath.advance();
        }
        stepIndex++;
        pendingBreaks = List.of();
        breakIndex = 0;
        breaker = null;
        movementStarted = false;
        movementStartDistance = 0.0D;
        arrivalSettleStartedAt = Long.MIN_VALUE;
        controlledDescendRecoveryStartedAt = Long.MIN_VALUE;
        fallingClearanceWaitStartedAt = Long.MIN_VALUE;
        fallingClearanceStableTicks = 0;
        fallingClearanceObserved = false;
        fallingStabilizationRequired = false;
        phase = "step_complete";
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
