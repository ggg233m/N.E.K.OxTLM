package com.neko_tlm_bridge.tlm.agent.action;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.MaidActionContext;
import com.neko_tlm_bridge.tlm.agent.runtime.HandLease;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Tick-driven block breaking for terrain steps. The actual block mutation,
 * drops and durability accounting stay centralized in {@link MaidBlockBreaker}.
 */
public final class MaidProgressiveBlockBreaker {
    private static final double MAX_BREAK_DISTANCE_SQUARED = 4.5D * 4.5D;

    private final BlockPos target;
    private final BlockState expectedState;
    private final HandLease handLease;
    private final boolean requireCorrectTool;

    private double progress;
    private boolean started;
    private boolean finished;

    public MaidProgressiveBlockBreaker(BlockPos target, BlockState expectedState,
                                       HandLease handLease, boolean requireCorrectTool) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.expectedState = Objects.requireNonNull(expectedState, "expectedState");
        this.handLease = Objects.requireNonNull(handLease, "handLease");
        this.requireCorrectTool = requireCorrectTool;
    }

    public TickResult tick(MaidActionContext context) {
        Objects.requireNonNull(context, "context");
        if (finished) {
            return failed(ActionEndReason.INTERNAL_ERROR, "breaker_ticked_after_terminal_state");
        }
        if (!started) {
            started = true;
            context.maid().swing(InteractionHand.MAIN_HAND);
        }

        EntityMaid maid = context.maid();
        if (handLease.validate(maid) != HandLease.LeaseHealth.HEALTHY) {
            return finishFailure(context, ActionEndReason.HAND_CONFLICT, "held_tool_changed_while_clearing_path");
        }

        BlockState state = context.level().getBlockState(target);
        if (!state.equals(expectedState)) {
            return finishFailure(context, ActionEndReason.TARGET_CHANGED, "terrain_changed_while_clearing_path");
        }
        if (!canReachVisibleFace(context, target)) {
            return finishFailure(context, ActionEndReason.PATH_NOT_FOUND,
                    "terrain_block_is_not_visible_or_in_reach");
        }

        float hardness = state.getDestroySpeed(context.level(), target);
        if (hardness < 0.0F) {
            return finishFailure(context, ActionEndReason.BLOCK_PROTECTED, "terrain_block_is_unbreakable");
        }

        ItemStack tool = maid.getMainHandItem();
        boolean correctForDrops = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        if (requireCorrectTool && !correctForDrops) {
            return finishFailure(context, ActionEndReason.TOOL_NOT_FOUND,
                    "held_tool_cannot_harvest_terrain_obstacle");
        }
        float toolSpeed = Math.max(1.0F, tool.getDestroySpeed(state));
        double increment = hardness == 0.0F ? 1.0D
                : toolSpeed / hardness / 30.0D;
        progress = Math.min(1.0D, progress + increment);

        maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(target));
        maid.setSwingingArms(true);
        context.level().destroyBlockProgress(maid.getId(), target,
                Math.min(9, (int) Math.floor(progress * 10.0D)));
        if (progress < 1.0D) {
            return new TickResult(Outcome.RUNNING, null, progress, detail(null));
        }

        MaidBlockBreaker.BreakResult committed = MaidBlockBreaker.breakWithHeldTool(
                maid, target, expectedState, handLease);
        return switch (committed) {
            case SUCCESS -> {
                finished = true;
                clearAnimation(context);
                yield new TickResult(Outcome.CLEARED, null, 1.0D, detail(null));
            }
            case TARGET_CHANGED -> finishFailure(context, ActionEndReason.TARGET_CHANGED,
                    "terrain_changed_before_break_commit");
            case BLOCK_PROTECTED -> finishFailure(context, ActionEndReason.BLOCK_PROTECTED,
                    "terrain_break_was_rejected");
            case HAND_CONFLICT -> finishFailure(context, ActionEndReason.HAND_CONFLICT,
                    "held_tool_changed_before_break_commit");
        };
    }

    public void stop(MaidActionContext context) {
        Objects.requireNonNull(context, "context");
        clearAnimation(context);
        finished = true;
    }

    public BlockPos target() {
        return target;
    }

    public BlockState expectedState() {
        return expectedState;
    }

    public double progress() {
        return progress;
    }

    private TickResult finishFailure(MaidActionContext context, ActionEndReason reason, String message) {
        finished = true;
        clearAnimation(context);
        return failed(reason, message);
    }

    private TickResult failed(ActionEndReason reason, String message) {
        return new TickResult(Outcome.FAILED, reason, progress, detail(message));
    }

    private JsonObject detail(String message) {
        JsonObject detail = new JsonObject();
        detail.addProperty("x", target.getX());
        detail.addProperty("y", target.getY());
        detail.addProperty("z", target.getZ());
        detail.addProperty("block_progress", progress);
        if (message != null) {
            detail.addProperty("message", message);
        }
        return detail;
    }

    private void clearAnimation(MaidActionContext context) {
        context.level().destroyBlockProgress(context.maid().getId(), target, -1);
        context.maid().setSwingingArms(false);
    }

    private static boolean canReachVisibleFace(MaidActionContext context, BlockPos target) {
        Vec3 eye = context.maid().getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);
        if (eye.distanceToSqr(center) > MAX_BREAK_DISTANCE_SQUARED) {
            return false;
        }
        BlockHitResult hit = context.level().clip(new ClipContext(
                eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context.maid()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    public enum Outcome {
        RUNNING,
        CLEARED,
        FAILED
    }

    public record TickResult(Outcome outcome, ActionEndReason reason, double progress, JsonObject detail) {
        public TickResult {
            Objects.requireNonNull(outcome, "outcome");
            detail = detail == null ? new JsonObject() : detail;
        }
    }
}
