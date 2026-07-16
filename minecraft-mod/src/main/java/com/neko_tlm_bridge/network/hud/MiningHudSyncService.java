package com.neko_tlm_bridge.network.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.neko_tlm_bridge.event.GameEventHandler;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Publishes the monitored maid's active action to her owner and server operators. */
public final class MiningHudSyncService {
    private static final int SYNC_INTERVAL_TICKS = 10;
    private static final Set<String> HUD_DETAIL_FIELDS = Set.of(
            "collected_count", "target_count", "segments_dug", "cleared_blocks",
            "current_y", "working_y", "route_choice", "harvested", "max_blocks",
            "direction", "shape", "stop_reason", "block_progress");
    private static final Map<UUID, SentSnapshot> LAST_SENT = new HashMap<>();

    private MiningHudSyncService() {
    }

    public static void reset() {
        LAST_SENT.clear();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SYNC_INTERVAL_TICKS != 0) {
            return;
        }

        MiningHudSnapshotPayload payload = createPayload();
        Set<UUID> onlinePlayers = new HashSet<>();
        EntityMaid monitoredMaid = payload.snapshotJson().isEmpty()
                ? null : findMaid(server, payload.monitoredMaidId());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onlinePlayers.add(player.getUUID());
            boolean mayView = monitoredMaid != null && mayView(player, monitoredMaid);
            sendIfChanged(player, mayView ? payload : MiningHudSnapshotPayload.clear());
        }
        LAST_SENT.keySet().retainAll(onlinePlayers);
    }

    static MiningHudSnapshotPayload createPayload() {
        String monitoredMaidId = GameEventHandler.getMonitoredMaidId();
        if (monitoredMaidId == null || monitoredMaidId.isBlank()) {
            return MiningHudSnapshotPayload.clear();
        }

        UUID maidId;
        try {
            maidId = UUID.fromString(monitoredMaidId);
        } catch (IllegalArgumentException ignored) {
            return MiningHudSnapshotPayload.clear();
        }

        Optional<JsonObject> status = MaidActionStore.getInstance().getActiveStatus(maidId);
        if (status.isEmpty()) {
            return new MiningHudSnapshotPayload(maidId.toString(), "");
        }

        JsonObject snapshotObject = status.get();
        // Wall-clock time changes on every snapshot even when action state did
        // not. The HUD does not render it, and omitting it permits per-player
        // payload deduplication.
        snapshotObject.remove("timestamp");
        String snapshot = snapshotObject.toString();
        if (snapshot.length() > MiningHudSnapshotPayload.MAX_SNAPSHOT_CHARS) {
            snapshot = compactSnapshot(snapshotObject).toString();
        }
        if (snapshot.length() > MiningHudSnapshotPayload.MAX_SNAPSHOT_CHARS) {
            return new MiningHudSnapshotPayload(maidId.toString(), "");
        }
        return new MiningHudSnapshotPayload(maidId.toString(), snapshot);
    }

    private static EntityMaid findMaid(MinecraftServer server, String maidId) {
        UUID id;
        try {
            id = UUID.fromString(maidId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof EntityMaid maid) {
                return maid;
            }
        }
        return null;
    }

    private static boolean mayView(ServerPlayer player, EntityMaid maid) {
        UUID ownerId = maid.getOwnerUUID();
        return player.level() == maid.level()
                && (player.hasPermissions(2)
                || ownerId != null && ownerId.equals(player.getUUID()));
    }

    private static void sendIfChanged(
            ServerPlayer player, MiningHudSnapshotPayload payload) {
        String signature = payload.monitoredMaidId() + '\0' + payload.snapshotJson();
        SentSnapshot previous = LAST_SENT.put(
                player.getUUID(), new SentSnapshot(player, signature));
        if (previous != null && previous.player() == player
                && previous.signature().equals(signature)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static JsonObject compactSnapshot(JsonObject source) {
        JsonObject compact = new JsonObject();
        copy(source, compact, "action_id");
        copy(source, compact, "maid_id");
        copy(source, compact, "generation");
        copy(source, compact, "kind");
        copy(source, compact, "status");
        copy(source, compact, "stage");
        copy(source, compact, "progress");
        if (source.has("detail") && source.get("detail").isJsonObject()) {
            JsonObject detail = new JsonObject();
            JsonObject sourceDetail = source.getAsJsonObject("detail");
            HUD_DETAIL_FIELDS.forEach(name -> copy(sourceDetail, detail, name));
            if (!detail.isEmpty()) {
                compact.add("detail", detail);
            }
        }
        return compact;
    }

    private static void copy(JsonObject source, JsonObject target, String name) {
        JsonElement value = source.get(name);
        if (value != null) {
            target.add(name, value.deepCopy());
        }
    }

    private record SentSnapshot(ServerPlayer player, String signature) {
    }
}
