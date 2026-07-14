package com.neko_tlm_bridge.tlm.agent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;

public record MaidActionContext(
        ServerLevel level,
        EntityMaid maid,
        long gameTime,
        MaidActionExecution execution
) {
}
