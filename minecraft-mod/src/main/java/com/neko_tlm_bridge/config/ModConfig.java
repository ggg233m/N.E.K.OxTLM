package com.neko_tlm_bridge.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;

public class ModConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue WEBSOCKET_PORT;
    public static final ModConfigSpec.BooleanValue NEKO_MODE_ENABLED;
    public static final ModConfigSpec.BooleanValue IGNORE_TLM_BUILTIN_CONTEXT;
    public static final ModConfigSpec.BooleanValue EVENT_PUSH_ENABLED;
    public static final ModConfigSpec.BooleanValue COMMAND_EXECUTION_ENABLED;

    static {
        Builder builder = new Builder();

        builder.push("websocket");
        WEBSOCKET_PORT = builder
                .translation("neko_tlm_bridge.config.websocket.port")
                .defineInRange("port", 48920, 1024, 65535);
        builder.pop();

        builder.push("bridge");
        NEKO_MODE_ENABLED = builder
                .translation("neko_tlm_bridge.config.bridge.nekoModeEnabled")
                .define("nekoModeEnabled", true);
        IGNORE_TLM_BUILTIN_CONTEXT = builder
                .translation("neko_tlm_bridge.config.bridge.ignoreTlmBuiltinContext")
                .define("ignoreTlmBuiltinContext", true);
        EVENT_PUSH_ENABLED = builder
                .translation("neko_tlm_bridge.config.bridge.eventPushEnabled")
                .define("eventPushEnabled", true);
        COMMAND_EXECUTION_ENABLED = builder
                .translation("neko_tlm_bridge.config.bridge.commandExecutionEnabled")
                .define("commandExecutionEnabled", false);
        builder.pop();

        SPEC = builder.build();
    }
}
