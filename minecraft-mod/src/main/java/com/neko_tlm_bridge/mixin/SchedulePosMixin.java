package com.neko_tlm_bridge.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidBodyLease;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TLM's schedule tick rewrites the maid restriction and may teleport her before
 * the brain tick can reassert an Agent lease. Suppress only that schedule tick
 * while a live or crash-recovery lease owns the body.
 */
@Mixin(value = SchedulePos.class, remap = false)
public abstract class SchedulePosMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void nekoTlmBridge$holdAgentRestriction(EntityMaid maid, CallbackInfo callback) {
        boolean active = MaidActionStore.getInstance().hasActiveMaidAction(maid.getUUID());
        boolean recovering = MaidBodyLease.hasRecoverablePersistentLease(maid);
        if (active || recovering) {
            callback.cancel();
        }
    }
}
