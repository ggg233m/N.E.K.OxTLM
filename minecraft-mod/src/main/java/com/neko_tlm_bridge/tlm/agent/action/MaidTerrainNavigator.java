package com.neko_tlm_bridge.tlm.agent.action;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.network.debug.MaidPathDebugService;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.MaidActionContext;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainPath;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep;
import com.neko_tlm_bridge.tlm.agent.runtime.HandLease;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
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

    private final MaidTerrainPath terrainPath;
    private final HandLease handLease;
    private final double speed;
    private final boolean requireCorrectTool;
    private final Map<BlockPos, BlockState> plannedBreakStates = new HashMap<>();
    private final Set<BlockPos> clearedBlocks = new LinkedHashSet<>();
    private final ArrayDeque<ClearedBlock> clearedEvents = new ArrayDeque<>();

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
    private String phase = "pending";
    private ActionEndReason lastFailure;

    public MaidTerrainNavigator(MaidTerrainPath terrainPath, HandLease handLease,
                                double speed, boolean requireCorrectTool) {
        this.terrainPath = Objects.requireNonNull(terrainPath, "terrainPath");
        this.handLease = Objects.requireNonNull(handLease, "handLease");
        if (!Double.isFinite(speed)) {
            throw new IllegalArgumentException("speed must be finite");
        }
        this.speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        this.requireCorrectTool = requireCorrectTool;
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

        if (pendingBreaks.isEmpty() && breakIndex == 0 && !step.toBreak().isEmpty()) {
            pendingBreaks = new ArrayList<>(step.toBreak());
            pendingBreaks.sort(Comparator.comparingDouble(
                    pos -> context.maid().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))));
        }

        TickResult clearing = clearNextObstacle(context, step);
        if (clearing != null) {
            publishDebug(context, false);
            return clearing;
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
            if (clearedBlocks.add(pos.immutable())) {
                clearedEvents.addLast(new ClearedBlock(pos, expected));
            }
            JsonObject detail = stepDetail(step);
            detail.addProperty("cleared_x", pos.getX());
            detail.addProperty("cleared_y", pos.getY());
            detail.addProperty("cleared_z", pos.getZ());
            return running(detail);
        }
        return null;
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
        if (context.maid().blockPosition().equals(step.to())) {
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

        // Vanilla/TLM ground path finding has no same-X/Z vertical edge. Once
        // DIG_DOWN removes the support block, let normal gravity perform this
        // one-cell descent instead of treating createPath(null) as a replan.
        if (step.kind() == MaidTerrainStep.Kind.DIG_DOWN) {
            return descendByGravity(context, step, distance);
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
                && support.isFaceSturdy(context.level(), supportPos, Direction.UP);
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
