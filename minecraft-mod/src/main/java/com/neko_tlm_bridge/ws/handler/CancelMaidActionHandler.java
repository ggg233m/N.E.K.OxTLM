package com.neko_tlm_bridge.ws.handler;

import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import com.neko_tlm_bridge.ws.Protocol;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.WebSocket;

import java.util.UUID;

public final class CancelMaidActionHandler implements MessageHandlerInterface {
    private final MinecraftServer server;

    public CancelMaidActionHandler(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public JsonObject handle(JsonObject request, WebSocket conn) {
        String requestId = request.has("request_id") ? request.get("request_id").getAsString() : null;
        JsonObject response = new JsonObject();
        response.addProperty("type", Protocol.TYPE_MAID_ACTION_CANCEL_RESULT);
        if (requestId != null) response.addProperty("request_id", requestId);
        JsonObject result = new JsonObject();
        try {
            if (server == null || !server.isSameThread()) {
                throw new IllegalStateException("Server not ready");
            }
            JsonObject data = request.getAsJsonObject("data");
            UUID actionId = UUID.fromString(data.get("action_id").getAsString());
            MaidActionStore.CancelResult cancel = MaidActionStore.getInstance().requestCancel(actionId);
            result.addProperty("accepted", cancel.accepted());
            if (cancel.rejectionReason() != null) {
                result.addProperty("rejection_reason", cancel.rejectionReason());
                result.addProperty("error_code", cancel.rejectionReason());
            }
            if (cancel.status() != null) {
                cancel.status().entrySet().forEach(entry ->
                        result.add(entry.getKey(), entry.getValue().deepCopy()));
            }
        } catch (RuntimeException malformed) {
            result.addProperty("accepted", false);
            result.addProperty("rejection_reason", malformed.getMessage() == null ? "INVALID_REQUEST" : malformed.getMessage());
        }
        response.add("data", result);
        return response;
    }
}
