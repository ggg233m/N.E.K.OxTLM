package com.neko_tlm_bridge.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;

public class ModConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue WEBSOCKET_PORT;
    public static final ModConfigSpec.BooleanValue NEKO_MODE_ENABLED;
    public static final ModConfigSpec.BooleanValue MAID_AGENT_ENABLED;

    public static final ModConfigSpec.BooleanValue EVENT_PUSH_ENABLED;
    public static final ModConfigSpec.BooleanValue COMMAND_EXECUTION_ENABLED;
    public static final ModConfigSpec.BooleanValue CHAT_BUBBLE_ENABLED;
    public static final ModConfigSpec.BooleanValue CHAT_BOX_ENABLED;
    public static final ModConfigSpec.BooleanValue WEATHER_EVENT_ENABLED;
    public static final ModConfigSpec.BooleanValue TIME_EVENT_ENABLED;
    public static final ModConfigSpec.IntValue COMMAND_CONFIRMATION_MIN_OP_LEVEL;
    public static final ModConfigSpec.IntValue BEHAVIOR_AGGREGATE_IDLE_TICKS;
    public static final ModConfigSpec.IntValue BEHAVIOR_AGGREGATE_MAX_WINDOW_TICKS;
    public static final ModConfigSpec.IntValue BLOCK_ACTIVITY_IDLE_TICKS;
    public static final ModConfigSpec.IntValue BLOCK_ACTIVITY_MAX_WINDOW_TICKS;
    public static final ModConfigSpec.IntValue BLOCK_ACTIVITY_MIN_COUNT;

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
        MAID_AGENT_ENABLED = builder
                .translation("neko_tlm_bridge.config.bridge.maidAgentEnabled")
                .define("maidAgentEnabled", true);
        EVENT_PUSH_ENABLED = builder
                .translation("neko_tlm_bridge.config.bridge.eventPushEnabled")
                .define("eventPushEnabled", true);
        COMMAND_EXECUTION_ENABLED = builder
                .translation("neko_tlm_bridge.config.bridge.commandExecutionEnabled")
                .define("commandExecutionEnabled", false);
        CHAT_BUBBLE_ENABLED = builder
                .translation("neko_tlm_bridge.config.bridge.chatBubbleEnabled")
                .define("chatBubbleEnabled", true);
        CHAT_BOX_ENABLED = builder
                .translation("neko_tlm_bridge.config.bridge.chatBoxEnabled")
                .define("chatBoxEnabled", true);
        WEATHER_EVENT_ENABLED = builder
                .translation("neko_tlm_bridge.config.bridge.weatherEventEnabled")
                .define("weatherEventEnabled", true);
        TIME_EVENT_ENABLED = builder
                .translation("neko_tlm_bridge.config.bridge.timeEventEnabled")
                .define("timeEventEnabled", true);
        COMMAND_CONFIRMATION_MIN_OP_LEVEL = builder
                .translation("neko_tlm_bridge.config.bridge.commandConfirmationMinOpLevel")
                .defineInRange("commandConfirmationMinOpLevel", 0, 0, 4);
        builder.pop();

        builder.push("playmate_events");
        BEHAVIOR_AGGREGATE_IDLE_TICKS = builder
                .translation("neko_tlm_bridge.config.playmate_events.behaviorAggregateIdleTicks")
                .defineInRange("behaviorAggregateIdleTicks", 80, 20, 1200);
        BEHAVIOR_AGGREGATE_MAX_WINDOW_TICKS = builder
                .translation("neko_tlm_bridge.config.playmate_events.behaviorAggregateMaxWindowTicks")
                .defineInRange("behaviorAggregateMaxWindowTicks", 160, 20, 2400);
        BLOCK_ACTIVITY_IDLE_TICKS = builder
                .translation("neko_tlm_bridge.config.playmate_events.blockActivityIdleTicks")
                .defineInRange("blockActivityIdleTicks", 60, 20, 1200);
        BLOCK_ACTIVITY_MAX_WINDOW_TICKS = builder
                .translation("neko_tlm_bridge.config.playmate_events.blockActivityMaxWindowTicks")
                .defineInRange("blockActivityMaxWindowTicks", 400, 20, 6000);
        BLOCK_ACTIVITY_MIN_COUNT = builder
                .translation("neko_tlm_bridge.config.playmate_events.blockActivityMinCount")
                .defineInRange("blockActivityMinCount", 4, 1, 128);
        builder.pop();

        SPEC = builder.build();
    }
}
