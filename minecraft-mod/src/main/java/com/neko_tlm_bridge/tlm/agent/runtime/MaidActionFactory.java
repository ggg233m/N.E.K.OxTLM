package com.neko_tlm_bridge.tlm.agent.runtime;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.MaidAction;

@FunctionalInterface
public interface MaidActionFactory {
    MaidAction create(EntityMaid maid, JsonObject args) throws IllegalArgumentException;
}
