package com.neko_tlm_bridge.tlm.agent.action;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainWorldEvaluator;
import com.neko_tlm_bridge.tlm.agent.runtime.HandLease;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** Commits a TLM-compatible block break with the real held item. */
public final class MaidBlockBreaker {
    private MaidBlockBreaker() {
    }

    public static BreakResult breakWithHeldTool(EntityMaid maid, BlockPos pos,
                                                BlockState expectedState, HandLease handLease) {
        Objects.requireNonNull(maid, "maid");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(handLease, "handLease");

        if (handLease.validate(maid) != HandLease.LeaseHealth.HEALTHY) {
            return BreakResult.HAND_CONFLICT;
        }
        BlockState currentState = maid.level().getBlockState(pos);
        if (!currentState.equals(expectedState)) {
            return BreakResult.TARGET_CHANGED;
        }
        if (!(maid.level() instanceof ServerLevel serverLevel)
                || !MaidTerrainWorldEvaluator.isSafeToClear(
                serverLevel, pos, expectedState)) {
            return BreakResult.BLOCK_PROTECTED;
        }

        BlockEntity blockEntity = currentState.hasBlockEntity() ? maid.level().getBlockEntity(pos) : null;
        ItemStack heldTool = maid.getMainHandItem();
        if (!maid.destroyBlock(pos, false)) {
            return BreakResult.BLOCK_PROTECTED;
        }

        maid.dropResourcesToMaidInv(currentState, maid.level(), pos, blockEntity, maid, heldTool);
        if (!heldTool.isEmpty()) {
            heldTool.hurtAndBreak(1, maid, EquipmentSlot.MAINHAND);
        }
        handLease.acknowledgeHeldToolMutation(maid);
        return BreakResult.SUCCESS;
    }

    public enum BreakResult {
        SUCCESS,
        TARGET_CHANGED,
        BLOCK_PROTECTED,
        HAND_CONFLICT
    }
}
