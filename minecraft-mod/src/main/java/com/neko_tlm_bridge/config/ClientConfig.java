package com.neko_tlm_bridge.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue PATH_RENDERING_ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("debug");
        PATH_RENDERING_ENABLED = builder
                .translation("neko_tlm_bridge.config.client.pathRenderingEnabled")
                .define("pathRenderingEnabled", false);
        builder.pop();
        SPEC = builder.build();
    }

    private ClientConfig() {
    }
}
