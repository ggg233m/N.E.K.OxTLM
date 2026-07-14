package com.neko_tlm_bridge.ws.handler;

import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.WebSocket;

import java.util.UUID;

public final class GetMaidActionStatusHandler implements MessageHandlerInterface {
    private final MinecraftServer server;

    public GetMaidActionStatusHandler(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public JsonObject handle(JsonObject request, WebSocket conn) {
        String requestId = request.has("request_id") ? request.get("request_id").getAsString() : null;
        JsonObject response = new JsonObject();
        response.addProperty("type", "maid_action_status");
        if (requestId != null) response.addProperty("request_id", requestId);
        JsonObject data = new JsonObject();
        try {
            if (server == null || !server.isSameThread()) throw new IllegalStateException("Server not ready");
            UUID actionId = UUID.fromString(request.getAsJsonObject("data").get("action_id").getAsString());
            var status = MaidActionStore.getInstance().getStatus(actionId);
            data.addProperty("found", status.isPresent());
            status.ifPresent(value -> data.add("action", value));
        } catch (RuntimeException malformed) {
            data.addProperty("found", false);
            data.addProperty("error", malformed.getMessage() == null ? "INVALID_REQUEST" : malformed.getMessage());
        }
        response.add("data", data);
        return response;
    }
}
