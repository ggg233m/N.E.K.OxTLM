package com.neko_tlm_bridge.ws;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PendingCommandManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NekoTlmBridge");
    private static final Gson GSON = new Gson();
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();
    private static final long EXPIRE_MS = 120_000;

    public static class PendingCommand {
        public final String pendingId;
        public final String originalRequestId;
        public final String command;
        public final WebSocket conn;
        public final long createdAt;

        public PendingCommand(String pendingId, String originalRequestId, String command, WebSocket conn) {
            this.pendingId = pendingId;
            this.originalRequestId = originalRequestId;
            this.command = command;
            this.conn = conn;
            this.createdAt = System.currentTimeMillis();
        }
    }

    public String addPendingCommand(String originalRequestId, String command, WebSocket conn) {
        String pendingId = UUID.randomUUID().toString().substring(0, 8);
        pendingCommands.put(pendingId, new PendingCommand(pendingId, originalRequestId, command, conn));
        return pendingId;
    }

    public PendingCommand getAndRemove(String pendingId) {
        return pendingCommands.remove(pendingId);
    }

    public void expireOldCommands() {
        long now = System.currentTimeMillis();
        var iter = pendingCommands.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            PendingCommand cmd = entry.getValue();
            if (now - cmd.createdAt > EXPIRE_MS) {
                iter.remove();
                sendExpiredResult(cmd);
            }
        }
    }

    private void sendExpiredResult(PendingCommand cmd) {
        if (cmd.conn != null && cmd.conn.isOpen()) {
            JsonObject response = new JsonObject();
            response.addProperty("type", Protocol.TYPE_COMMAND_EXECUTION_RESULT);
            if (cmd.originalRequestId != null) {
                response.addProperty("request_id", cmd.originalRequestId);
            }
            JsonObject data = new JsonObject();
            data.addProperty("approved", false);
            data.addProperty("expired", true);
            data.addProperty("command", cmd.command);
            data.addProperty("message", "Command request expired (no player confirmation)");
            response.add("data", data);
            cmd.conn.send(GSON.toJson(response));
        }
        LOGGER.info("Expired pending command: {} ({})", cmd.command, cmd.pendingId);
    }
}
