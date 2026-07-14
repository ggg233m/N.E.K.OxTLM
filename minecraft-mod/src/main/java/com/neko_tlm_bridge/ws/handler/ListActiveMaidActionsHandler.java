package com.neko_tlm_bridge.ws.handler;

import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import com.neko_tlm_bridge.ws.Protocol;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.WebSocket;

import java.util.UUID;

public final class ListActiveMaidActionsHandler implements MessageHandlerInterface {
    private final MinecraftServer server;

    public ListActiveMaidActionsHandler(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public JsonObject handle(JsonObject request, WebSocket conn) {
        String requestId = request.has("request_id") ? request.get("request_id").getAsString() : null;
        JsonObject response = new JsonObject();
        response.addProperty("type", Protocol.TYPE_MAID_ACTION_LIST);
        if (requestId != null) response.addProperty("request_id", requestId);
        JsonObject data = new JsonObject();
        try {
            if (server == null || !server.isSameThread()) throw new IllegalStateException("Server not ready");
            UUID maidFilter = null;
            if (request.has("data") && request.get("data").isJsonObject()) {
                JsonObject input = request.getAsJsonObject("data");
                if (input.has("maid_id") && !input.get("maid_id").getAsString().isBlank()) {
                    maidFilter = UUID.fromString(input.get("maid_id").getAsString());
                }
            }
            data.add("actions", MaidActionStore.getInstance().listActive(maidFilter));
        } catch (RuntimeException malformed) {
            data.addProperty("error", malformed.getMessage() == null ? "INVALID_REQUEST" : malformed.getMessage());
        }
        response.add("data", data);
        return response;
    }
}
