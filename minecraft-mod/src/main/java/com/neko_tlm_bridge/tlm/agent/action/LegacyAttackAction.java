package com.neko_tlm_bridge.tlm.agent.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.MaidAction;
import com.neko_tlm_bridge.tlm.agent.MaidActionContext;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.MaidActionResource;
import com.neko_tlm_bridge.tlm.agent.MaidActionTickResult;
import com.neko_tlm_bridge.network.debug.MaidPathDebugService;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Compatibility adapter for the existing attack_target WebSocket tool. */
public final class LegacyAttackAction implements MaidAction {
    private final List<UUID> targets;
    private int targetIndex;
    private UUID activeTargetId;

    public LegacyAttackAction(List<UUID> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("At least one target UUID is required");
        }
        this.targets = List.copyOf(targets);
    }

    public static LegacyAttackAction fromArgs(JsonObject args) {
        Objects.requireNonNull(args, "args");
        List<UUID> targets = new ArrayList<>();
        if (args.has("target_ids") && args.get("target_ids").isJsonArray()) {
            JsonArray ids = args.getAsJsonArray("target_ids");
            ids.forEach(value -> targets.add(UUID.fromString(value.getAsString())));
        } else if (args.has("target_id")) {
            targets.add(UUID.fromString(args.get("target_id").getAsString()));
        }
        return new LegacyAttackAction(targets);
    }

    @Override
    public MaidActionKind kind() {
        return MaidActionKind.LEGACY_ATTACK;
    }

    @Override
    public Set<MaidActionResource> resources() {
        return Set.of(MaidActionResource.MOVE, MaidActionResource.HAND);
    }

    @Override
    public MaidActionTickResult tick(MaidActionContext context) {
        while (targetIndex < targets.size()) {
            UUID targetId = targets.get(targetIndex);
            Entity entity = context.level().getEntity(targetId);
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                if (targetId.equals(activeTargetId)) {
                    clearTargetMemories(context);
                    activeTargetId = null;
                }
                targetIndex++;
                continue;
            }

            activeTargetId = targetId;
            context.maid().getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, living);
            context.maid().getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                    new EntityTracker(living, true));
            JsonObject detail = new JsonObject();
            detail.addProperty("target_id", targetId.toString());
            detail.addProperty("target_index", targetIndex);
            detail.addProperty("target_count", targets.size());
            context.execution().reportProgress("attacking",
                    (double) targetIndex / targets.size(), detail);
            MaidPathDebugService.publishIfNeeded(context.maid(), context.gameTime(), false);
            return MaidActionTickResult.running();
        }

        JsonObject result = new JsonObject();
        result.addProperty("targets_completed", targets.size());
        return MaidActionTickResult.succeeded(result);
    }

    @Override
    public void stop(MaidActionContext context, ActionEndReason reason) {
        clearTargetMemories(context);
        MaidPathDebugService.clear(context.maid().getUUID());
    }

    private void clearTargetMemories(MaidActionContext context) {
        context.maid().getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        context.maid().getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        context.maid().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        context.maid().getNavigation().stop();
    }
}
