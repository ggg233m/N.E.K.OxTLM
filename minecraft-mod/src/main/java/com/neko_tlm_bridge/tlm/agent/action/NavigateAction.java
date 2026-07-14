package com.neko_tlm_bridge.tlm.agent.action;

import com.google.gson.JsonObject;
import com.neko_tlm_bridge.network.debug.MaidPathDebugService;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.MaidAction;
import com.neko_tlm_bridge.tlm.agent.MaidActionContext;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.MaidActionResource;
import com.neko_tlm_bridge.tlm.agent.MaidActionTickResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Set;

/**
 * Moves a maid to a concrete server-side block position using her native navigation.
 * The action deliberately does not perform destructive path finding.
 */
public final class NavigateAction implements MaidAction {
    private static final long STUCK_WINDOW_TICKS = 60L;
    private static final double REQUIRED_PROGRESS = 0.5D;
    private static final int MAX_RETRIES = 3;

    private final BlockPos target;
    private final double speed;
    private final double stopDistance;

    private Stage stage = Stage.VALIDATING;
    private double initialDistance;
    private double windowStartDistance;
    private long windowStartedAt;
    private int retries;
    private boolean started;

    public NavigateAction(BlockPos target, double speed, double stopDistance) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.speed = clamp(speed, 0.4D, 1.0D);
        this.stopDistance = clamp(stopDistance, 1.0D, 4.0D);
    }

    public static NavigateAction fromArgs(JsonObject args) {
        Objects.requireNonNull(args, "args");
        JsonObject target = requireObject(args, "target");
        BlockPos targetPos = new BlockPos(
                requireInt(target, "x"),
                requireInt(target, "y"),
                requireInt(target, "z"));
        double speed = optionalDouble(args, "speed", 0.7D);
        double stopDistance = optionalDouble(args, "stop_distance", 1.5D);
        return new NavigateAction(targetPos, speed, stopDistance);
    }

    @Override
    public MaidActionKind kind() {
        return MaidActionKind.NAVIGATE;
    }

    @Override
    public Set<MaidActionResource> resources() {
        return Set.of(MaidActionResource.MOVE);
    }

    @Override
    public void start(MaidActionContext context) {
        started = true;
        double distance = distanceToTarget(context);
        initialDistance = Math.max(distance, stopDistance);
        windowStartDistance = distance;
        windowStartedAt = context.gameTime();
        report(context, Stage.VALIDATING, 0.0D, null);
    }

    @Override
    public MaidActionTickResult tick(MaidActionContext context) {
        if (!started) {
            start(context);
        }

        if (!context.level().hasChunkAt(target)) {
            return failure(ActionEndReason.VALIDATION_FAILED, "target_chunk_not_loaded");
        }

        double distance = distanceToTarget(context);
        if (distance <= stopDistance) {
            stage = Stage.ARRIVED;
            report(context, stage, 1.0D, distance);
            stopNavigation(context);
            JsonObject result = new JsonObject();
            result.add("target", blockPosJson(target));
            result.addProperty("distance", distance);
            result.addProperty("retries", retries);
            return MaidActionTickResult.succeeded(result);
        }

        if (stage == Stage.VALIDATING || stage == Stage.PATHFINDING) {
            MaidActionTickResult pathResult = createAndStartPath(context, distance, retries > 0);
            if (pathResult != null) {
                return pathResult;
            }
        }

        maintainAgentMemories(context);
        MaidPathDebugService.publishIfNeeded(context.maid(), context.gameTime(), false);

        if (context.gameTime() - windowStartedAt >= STUCK_WINDOW_TICKS) {
            double windowImprovement = windowStartDistance - distance;
            if (windowImprovement < REQUIRED_PROGRESS) {
                if (retries >= MAX_RETRIES) {
                    return failure(ActionEndReason.STUCK, "navigation_made_no_progress");
                }
                retries++;
                stage = Stage.PATHFINDING;
                context.maid().getNavigation().stop();
                clearAgentMemories(context);
                windowStartDistance = distance;
                windowStartedAt = context.gameTime();
                return MaidActionTickResult.running();
            }
            windowStartDistance = distance;
            windowStartedAt = context.gameTime();
        }

        if (context.maid().getNavigation().isDone()) {
            stage = Stage.PATHFINDING;
            return MaidActionTickResult.running();
        }

        report(context, Stage.MOVING, progress(distance), distance);
        return MaidActionTickResult.running();
    }

    @Override
    public void stop(MaidActionContext context, ActionEndReason reason) {
        stopNavigation(context);
        MaidPathDebugService.clear(context.maid().getUUID());
    }

    public BlockPos target() {
        return target;
    }

    public double speed() {
        return speed;
    }

    public double stopDistance() {
        return stopDistance;
    }

    private MaidActionTickResult createAndStartPath(MaidActionContext context, double distance, boolean retry) {
        stage = Stage.PATHFINDING;
        JsonObject detail = new JsonObject();
        detail.add("target", blockPosJson(target));
        detail.addProperty("retry", retries);
        context.execution().reportProgress(stage.wireName, progress(distance), detail);

        Path path = context.maid().getNavigation().createPath(target, 0);
        if (path == null || path.getNodeCount() == 0) {
            if (retry && retries < MAX_RETRIES) {
                retries++;
                windowStartedAt = context.gameTime();
                windowStartDistance = distance;
                return null;
            }
            return failure(ActionEndReason.PATH_NOT_FOUND, "path_not_found");
        }

        maintainAgentMemories(context);
        if (!context.maid().getNavigation().moveTo(path, speed)) {
            return failure(ActionEndReason.PATH_NOT_FOUND, "navigation_rejected_path");
        }
        stage = Stage.MOVING;
        windowStartedAt = context.gameTime();
        windowStartDistance = distance;
        MaidPathDebugService.publishIfNeeded(context.maid(), context.gameTime(), true);
        return null;
    }

    private void maintainAgentMemories(MaidActionContext context) {
        BlockPosTracker tracker = new BlockPosTracker(target);
        context.maid().getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(tracker, (float) speed, (int) Math.ceil(stopDistance)));
        context.maid().getBrain().setMemory(MemoryModuleType.LOOK_TARGET, tracker);
    }

    private void clearAgentMemories(MaidActionContext context) {
        context.maid().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        context.maid().getBrain().eraseMemory(MemoryModuleType.PATH);
        context.maid().getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
    }

    private void stopNavigation(MaidActionContext context) {
        context.maid().getNavigation().stop();
        clearAgentMemories(context);
    }

    private double distanceToTarget(MaidActionContext context) {
        return context.maid().position().distanceTo(Vec3.atBottomCenterOf(target));
    }

    private double progress(double distance) {
        if (initialDistance <= stopDistance) {
            return 1.0D;
        }
        return clamp((initialDistance - distance) / (initialDistance - stopDistance), 0.0D, 0.99D);
    }

    private void report(MaidActionContext context, Stage nextStage, double progress, Double distance) {
        stage = nextStage;
        JsonObject detail = new JsonObject();
        detail.add("target", blockPosJson(target));
        detail.addProperty("retry", retries);
        if (distance != null) {
            detail.addProperty("distance", distance);
        }
        context.execution().reportProgress(nextStage.wireName, progress, detail);
    }

    private static MaidActionTickResult failure(ActionEndReason reason, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("message", message);
        return MaidActionTickResult.failed(reason, result);
    }

    private static JsonObject blockPosJson(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static JsonObject requireObject(JsonObject parent, String name) {
        if (!parent.has(name) || !parent.get(name).isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return parent.getAsJsonObject(name);
    }

    private static int requireInt(JsonObject parent, String name) {
        if (!parent.has(name) || !parent.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return parent.get(name).getAsInt();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static double optionalDouble(JsonObject parent, String name, double fallback) {
        if (!parent.has(name)) {
            return fallback;
        }
        try {
            double value = parent.get(name).getAsDouble();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " must be a number", exception);
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private enum Stage {
        VALIDATING("validating"),
        PATHFINDING("pathfinding"),
        MOVING("moving"),
        ARRIVED("arrived");

        private final String wireName;

        Stage(String wireName) {
            this.wireName = wireName;
        }
    }
}
