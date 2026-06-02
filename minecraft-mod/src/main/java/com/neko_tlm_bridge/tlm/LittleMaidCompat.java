package com.neko_tlm_bridge.tlm;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.ExtraMaidBrainManager;
import com.neko_tlm_bridge.config.ModConfig;

@LittleMaidExtension
public class LittleMaidCompat implements ILittleMaid {

    @Override
    public void registerAITool(ToolRegister register) {
        if (ModConfig.NEKO_MODE_ENABLED.get()) {
            register.register(new NekoBridgeTool());
        }
    }

    @Override
    public void addExtraMaidBrain(ExtraMaidBrainManager manager) {
        if (ModConfig.NEKO_MODE_ENABLED.get()) {
            manager.addExtraMaidBrain(new NekoExtraMaidBrain());
        }
    }
}
