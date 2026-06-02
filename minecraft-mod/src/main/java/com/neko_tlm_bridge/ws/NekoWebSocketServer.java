package com.neko_tlm_bridge.ws;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.config.ModConfig;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NekoWebSocketServer extends WebSocketServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("NekoTlmBridge");
    private static final Gson GSON = new Gson();
    private final Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final MessageHandler messageHandler;
    private final PendingCommandManager pendingCommandManager = new PendingCommandManager();

    public NekoWebSocketServer(MessageHandler messageHandler) {
        super(new InetSocketAddress("127.0.0.1", ModConfig.WEBSOCKET_PORT.get()));
        this.messageHandler = messageHandler;
        this.setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        InetAddress addr = conn.getRemoteSocketAddress().getAddress();
        if (!addr.isLoopbackAddress()) {
            LOGGER.warn("Rejected non-localhost connection from: {}", addr);
            conn.close(403, "Only localhost connections allowed");
            return;
        }
        clients.add(conn);
        LOGGER.info("N.E.K.O client connected: {}", addr);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        LOGGER.info("N.E.K.O client disconnected: code={}, reason={}", code, reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = GSON.fromJson(message, JsonObject.class);
            String type = json.get("type").getAsString();
            String requestId = json.has("request_id") ? json.get("request_id").getAsString() : null;

            switch (type) {
                case Protocol.TYPE_GET_MAID_STATUS -> messageHandler.handleGetMaidStatus(conn, requestId);
                case Protocol.TYPE_COMMAND_MAID -> messageHandler.handleCommandMaid(conn, requestId, json);
                case Protocol.TYPE_SEND_CHAT -> messageHandler.handleSendChat(conn, requestId, json);
                case Protocol.TYPE_GET_GAME_CONTEXT -> messageHandler.handleGetGameContext(conn, requestId, json);
                case Protocol.TYPE_USE_SKILL -> messageHandler.handleUseSkill(conn, requestId, json);
                case Protocol.TYPE_EXECUTE_COMMAND -> messageHandler.handleExecuteCommand(conn, requestId, json);
                case Protocol.TYPE_ATTACK_TARGET -> messageHandler.handleAttackTarget(conn, requestId, json);
                case Protocol.TYPE_GET_CONFIG -> messageHandler.handleGetConfig(conn, requestId);
                case Protocol.TYPE_PING -> sendToClient(conn, GSON.toJson(createPong(requestId)));
                default -> sendError(conn, requestId, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            LOGGER.error("Error processing message: {}", e.getMessage());
            sendError(conn, null, "Internal error: " + e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOGGER.error("WebSocket error: {}", ex.getMessage());
    }

    @Override
    public void onStart() {
        LOGGER.info("WebSocket server started on port {}", getPort());
    }

    public void broadcastEvent(JsonObject eventData) {
        if (clients.isEmpty()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", Protocol.TYPE_EVENT);
        msg.add("data", eventData);
        String json = GSON.toJson(msg);
        for (WebSocket client : clients) {
            sendToClient(client, json);
        }
    }

    public void broadcastChatMessage(JsonObject chatData) {
        if (clients.isEmpty()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", Protocol.TYPE_CHAT_MESSAGE);
        msg.add("data", chatData);
        String json = GSON.toJson(msg);
        for (WebSocket client : clients) {
            sendToClient(client, json);
        }
    }

    public boolean hasClients() {
        return !clients.isEmpty();
    }

    public PendingCommandManager getPendingCommandManager() {
        return pendingCommandManager;
    }

    public void tickPendingCommands() {
        pendingCommandManager.expireOldCommands();
    }

    private void sendToClient(WebSocket conn, String message) {
        if (conn != null && conn.isOpen()) {
            conn.send(message);
        }
    }

    private JsonObject createPong(String requestId) {
        JsonObject pong = new JsonObject();
        pong.addProperty("type", Protocol.TYPE_PONG);
        if (requestId != null) pong.addProperty("request_id", requestId);
        return pong;
    }

    private void sendError(WebSocket conn, String requestId, String errorMessage) {
        JsonObject error = new JsonObject();
        error.addProperty("type", Protocol.TYPE_ERROR);
        if (requestId != null) error.addProperty("request_id", requestId);
        JsonObject data = new JsonObject();
        data.addProperty("message", errorMessage);
        error.add("data", data);
        sendToClient(conn, GSON.toJson(error));
    }
}
