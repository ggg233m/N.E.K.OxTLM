package com.neko_tlm_bridge.ws.handler;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.WebSocket;

import java.util.UUID;

public final class StartMaidActionHandler implements MessageHandlerInterface {
    private final MinecraftServer server;

    public StartMaidActionHandler(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public JsonObject handle(JsonObject request, WebSocket conn) {
        String requestId = request.has("request_id") ? request.get("request_id").getAsString() : null;
        JsonObject data = request.has("data") && request.get("data").isJsonObject()
                ? request.getAsJsonObject("data") : new JsonObject();
        try {
            UUID actionId = UUID.fromString(requiredString(data, "action_id"));
            String maidId = requiredString(data, "maid_id");
            MaidActionKind kind = MaidActionKind.fromWireName(requiredString(data, "kind"));
            EntityMaid maid = MaidHelper.findMaidById(server, maidId);
            if (maid == null) {
                return result(requestId, false, "MAID_NOT_FOUND", null);
            }
            long timeout = data.has("timeout_ms") ? data.get("timeout_ms").getAsLong() : 60_000L;
            boolean replace = !data.has("replace_existing") || data.get("replace_existing").getAsBoolean();
            JsonObject args = data.has("args") && data.get("args").isJsonObject()
                    ? data.getAsJsonObject("args") : new JsonObject();
            MaidActionStore.StartResult start = MaidActionStore.getInstance()
                    .start(actionId, maid, kind, args, timeout, replace);
            return result(requestId, start.accepted(), start.rejectionReason(), start.status());
        } catch (IllegalArgumentException malformed) {
            return result(requestId, false, malformed.getMessage(), null);
        }
    }

    private static JsonObject result(String requestId, boolean accepted, String reason, JsonObject action) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "maid_action_start_result");
        if (requestId != null) response.addProperty("request_id", requestId);
        JsonObject data = new JsonObject();
        data.addProperty("accepted", accepted);
        if (reason != null) data.addProperty("rejection_reason", reason);
        if (action != null) data.add("action", action);
        response.add("data", data);
        return response;
    }

    private static String requiredString(JsonObject data, String key) {
        if (!data.has(key) || data.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return data.get(key).getAsString();
    }
}
