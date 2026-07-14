package com.neko_tlm_bridge.ws.handler;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.config.ModConfig;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import com.neko_tlm_bridge.ws.Protocol;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps the legacy attack_target protocol while routing it through the single body owner. */
public final class AttackTargetHandler implements MessageHandlerInterface {
    private static final Logger LOGGER = LoggerFactory.getLogger("NekoTlmBridge");
    private static final long LEGACY_ATTACK_TIMEOUT_MS = 120_000L;
    private final MinecraftServer server;

    public AttackTargetHandler(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public JsonObject handle(JsonObject request, WebSocket conn) {
        String requestId = request.has("request_id") ? request.get("request_id").getAsString() : null;
        try {
            if (!ModConfig.MAID_AGENT_ENABLED.get()) {
                return error(requestId, "MAID_AGENT_DISABLED");
            }
            JsonObject data = request.has("data") && request.get("data").isJsonObject()
                    ? request.getAsJsonObject("data") : new JsonObject();
            String maidId = requiredString(data, "maid_id");
            EntityMaid maid = MaidHelper.findMaidById(server, maidId);
            if (maid == null) {
                return error(requestId, "Maid not found: " + maidId);
            }

            Map<UUID, String> targets = resolveTargets(data, (ServerLevel) maid.level());
            if (targets.isEmpty()) {
                return error(requestId, "No valid living target in the maid's dimension");
            }

            JsonObject args = new JsonObject();
            JsonArray targetIds = new JsonArray();
            targets.keySet().forEach(id -> targetIds.add(id.toString()));
            args.add("target_ids", targetIds);

            UUID actionId = UUID.randomUUID();
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.LEGACY_ATTACK, args,
                    LEGACY_ATTACK_TIMEOUT_MS, true);
            if (!start.accepted()) {
                return error(requestId, start.rejectionReason() == null
                        ? "Attack action rejected" : start.rejectionReason());
            }

            JsonObject response = new JsonObject();
            response.addProperty("type", Protocol.TYPE_ATTACK_TARGET_RESULT);
            if (requestId != null) response.addProperty("request_id", requestId);
            JsonObject result = new JsonObject();
            result.addProperty("status", "dispatched");
            result.addProperty("maid_id", maidId);
            result.addProperty("action_id", actionId.toString());
            result.addProperty("target_count", targets.size());
            result.addProperty("message", "已通知" + maid.getName().getString() + "攻击目标");
            JsonArray names = new JsonArray();
            targets.values().forEach(names::add);
            result.add("target_names", names);
            if (start.status() != null && start.status().has("generation")) {
                result.add("generation", start.status().get("generation"));
            }
            response.add("data", result);
            LOGGER.info("Started unified legacy attack action {} for maid {} with {} target(s)",
                    actionId, maid.getUUID(), targets.size());
            return response;
        } catch (IllegalArgumentException malformed) {
            return error(requestId, malformed.getMessage());
        }
    }

    private static Map<UUID, String> resolveTargets(JsonObject data, ServerLevel level) {
        Map<UUID, String> targets = new LinkedHashMap<>();
        if (data.has("target_entity_id") && !data.get("target_entity_id").getAsString().isBlank()) {
            addTarget(targets, level, data.get("target_entity_id").getAsString());
        }
        if (data.has("target_entity_ids") && data.get("target_entity_ids").isJsonArray()) {
            for (var value : data.getAsJsonArray("target_entity_ids")) {
                addTarget(targets, level, value.getAsString());
            }
        }
        return targets;
    }

    private static void addTarget(Map<UUID, String> targets, ServerLevel level, String rawId) {
        UUID targetId;
        try {
            targetId = UUID.fromString(rawId);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Invalid target entity UUID: " + rawId);
        }
        Entity entity = level.getEntity(targetId);
        if (entity instanceof LivingEntity living && living.isAlive()) {
            targets.putIfAbsent(targetId, living.getName().getString());
        }
    }

    private static String requiredString(JsonObject data, String name) {
        if (!data.has(name) || data.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return data.get(name).getAsString();
    }

    private static JsonObject error(String requestId, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("type", Protocol.TYPE_ERROR);
        if (requestId != null) response.addProperty("request_id", requestId);
        JsonObject data = new JsonObject();
        data.addProperty("message", message == null ? "Invalid attack request" : message);
        response.add("data", data);
        return response;
    }
}
