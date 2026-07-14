package com.neko_tlm_bridge.tlm.agent.runtime;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/** The only extra Core behavior allowed to own a maid body for agent actions. */
public final class NekoAgentBehavior {
    private NekoAgentBehavior() {
    }

    public static BehaviorControl<EntityMaid> create() {
        return BehaviorBuilder.create(context -> context.group(
                context.registered(MemoryModuleType.WALK_TARGET),
                context.registered(MemoryModuleType.LOOK_TARGET),
                context.registered(MemoryModuleType.PATH)
        ).apply(context, (walkTarget, lookTarget, path)
                -> (ServerLevel level, EntityMaid maid, long gameTime) ->
                MaidActionStore.getInstance().tickMaid(level, maid, gameTime)));
    }
}
