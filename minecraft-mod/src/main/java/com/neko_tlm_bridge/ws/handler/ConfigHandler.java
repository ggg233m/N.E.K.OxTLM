package com.neko_tlm_bridge.ws.handler;

import com.google.gson.JsonObject;
import com.neko_tlm_bridge.config.ModConfig;
import com.neko_tlm_bridge.event.GameEventHandler;
import com.neko_tlm_bridge.ws.Protocol;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 配置与监控女仆处理器 — 处理 get_config 和 set_monitored_maid 请求，同步监控女仆 ID 到 GameEventHandler */
public class ConfigHandler implements MessageHandlerInterface {
    private static final Logger LOGGER = LoggerFactory.getLogger("NekoTlmBridge");
    private final MinecraftServer server;

    public ConfigHandler(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public JsonObject handle(JsonObject request, WebSocket conn) {
        String type = request.get("type").getAsString();
        if (Protocol.TYPE_GET_CONFIG.equals(type)) {
            return handleGetConfig(request, conn);
        } else if (Protocol.TYPE_SET_MONITORED_MAID.equals(type)) {
            return handleSetMonitoredMaid(request, conn);
        }
        String requestId = request.has("request_id") ? request.get("request_id").getAsString() : null;
        return createErrorResponse(requestId, "Unknown type for ConfigHandler: " + type);
    }

    private JsonObject handleGetConfig(JsonObject request, WebSocket conn) {
        String requestId = request.has("request_id") ? request.get("request_id").getAsString() : null;
        JsonObject response = new JsonObject();
        response.addProperty("type", Protocol.TYPE_CONFIG);
        if (requestId != null) response.addProperty("request_id", requestId);
        JsonObject data = new JsonObject();
        data.addProperty("neko_mode_enabled", ModConfig.NEKO_MODE_ENABLED.get());
        data.addProperty("maid_agent_enabled", ModConfig.MAID_AGENT_ENABLED.get());
        data.addProperty("event_push_enabled", ModConfig.EVENT_PUSH_ENABLED.get());
        data.addProperty("command_execution_enabled", ModConfig.COMMAND_EXECUTION_ENABLED.get());
        data.addProperty("chat_bubble_enabled", ModConfig.CHAT_BUBBLE_ENABLED.get());
        data.addProperty("chat_box_enabled", ModConfig.CHAT_BOX_ENABLED.get());
        data.addProperty("websocket_port", ModConfig.WEBSOCKET_PORT.get());
        data.addProperty("behavior_aggregate_idle_ticks", ModConfig.BEHAVIOR_AGGREGATE_IDLE_TICKS.get());
        data.addProperty("behavior_aggregate_max_window_ticks", ModConfig.BEHAVIOR_AGGREGATE_MAX_WINDOW_TICKS.get());
        data.addProperty("block_activity_idle_ticks", ModConfig.BLOCK_ACTIVITY_IDLE_TICKS.get());
        data.addProperty("block_activity_max_window_ticks", ModConfig.BLOCK_ACTIVITY_MAX_WINDOW_TICKS.get());
        data.addProperty("block_activity_min_count", ModConfig.BLOCK_ACTIVITY_MIN_COUNT.get());
        response.add("data", data);
        return response;
    }

    private JsonObject handleSetMonitoredMaid(JsonObject request, WebSocket conn) {
        String requestId = request.has("request_id") ? request.get("request_id").getAsString() : null;
        JsonObject data = request.has("data") ? request.getAsJsonObject("data") : new JsonObject();
        String maidId = data.has("maid_id") ? data.get("maid_id").getAsString() : "";
        GameEventHandler.setMonitoredMaidId(maidId);
        LOGGER.info("Set monitored maid_id: {}", maidId);

        JsonObject response = new JsonObject();
        response.addProperty("type", Protocol.TYPE_CONFIG);
        if (requestId != null) response.addProperty("request_id", requestId);
        JsonObject resultData = new JsonObject();
        resultData.addProperty("monitored_maid_id", maidId);
        response.add("data", resultData);
        return response;
    }
}
