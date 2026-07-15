package com.neko_tlm_bridge.network.debug;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.neko_tlm_bridge.tlm.agent.MaidActionResource;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.PathfindingDebugPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-authoritative distribution of the maid's real navigation path. */
public final class MaidPathDebugService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaidPathDebugService.class);
    private static final double MAX_DISTANCE_SQUARED = 96.0D * 96.0D;
    private static final int MAX_NODES = 256;
    private static final long NODE_UPDATE_INTERVAL = 10L;
    private static final long HEARTBEAT_INTERVAL = 40L;
    private static final long WARNING_INTERVAL = 200L;

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, SyncState> SYNC_STATES = new ConcurrentHashMap<>();

    private MaidPathDebugService() {
    }

    public static void setSubscribed(ServerPlayer player, boolean enabled) {
        if (enabled) {
            if (SUBSCRIBERS.add(player.getUUID())) {
                // Force the next action tick to send the current path only when
                // this connection newly subscribes. Repeated enabled packets
                // must not bypass the global path update rate limit.
                SYNC_STATES.clear();
            }
        } else {
            SUBSCRIBERS.remove(player.getUUID());
        }
    }

    public static boolean isSubscribed(UUID playerId) {
        return SUBSCRIBERS.contains(playerId);
    }

    public static void removeSubscriber(UUID playerId) {
        SUBSCRIBERS.remove(playerId);
    }

    public static void publishIfNeeded(EntityMaid maid, long gameTime, boolean force) {
        Path path = maid.getNavigation().getPath();
        if (path == null || maid.getNavigation().isDone()) {
            clear(maid.getUUID());
            return;
        }
        publishIfNeeded(maid, path, gameTime, force);
    }

    /**
     * Publishes a stable, server-authored path that is not installed into the
     * vanilla navigation object. Terrain actions use this to show their full
     * multi-step plan while native navigation executes one adjacent step at a
     * time. Callers should retain and advance the same {@link Path} instance;
     * constructing a new path every tick defeats identity-based rate limiting.
     */
    public static void publishIfNeeded(EntityMaid maid, Path path, long gameTime, boolean force) {
        if (!MaidActionStore.getInstance().hasActiveResource(maid.getUUID(), MaidActionResource.MOVE)) {
            clear(maid.getUUID());
            return;
        }
        if (path == null || path.isDone()) {
            clear(maid.getUUID());
            return;
        }

        if (path.getNodeCount() > MAX_NODES) {
            SyncState state = SYNC_STATES.computeIfAbsent(maid.getUUID(), ignored -> new SyncState());
            if (gameTime - state.lastWarningAt >= WARNING_INTERVAL) {
                state.lastWarningAt = gameTime;
                LOGGER.warn("Skipping maid path debug sync for {}: {} nodes exceeds limit {}",
                        maid.getUUID(), path.getNodeCount(), MAX_NODES);
            }
            return;
        }

        SyncState state = SYNC_STATES.computeIfAbsent(maid.getUUID(), ignored -> new SyncState());
        int pathIdentity = System.identityHashCode(path);
        int nextNodeIndex = path.getNextNodeIndex();
        boolean newPath = state.pathIdentity != pathIdentity;
        boolean nodeAdvanced = state.nextNodeIndex != nextNodeIndex;
        boolean nodeUpdateDue = nodeAdvanced && gameTime - state.lastSentAt >= NODE_UPDATE_INTERVAL;
        boolean heartbeatDue = gameTime - state.lastSentAt >= HEARTBEAT_INTERVAL;
        if (!force && !newPath && !nodeUpdateDue && !heartbeatDue) {
            return;
        }

        state.pathIdentity = pathIdentity;
        state.nextNodeIndex = nextNodeIndex;
        state.lastSentAt = gameTime;

        for (UUID subscriberId : SUBSCRIBERS) {
            ServerPlayer player = maid.getServer().getPlayerList().getPlayer(subscriberId);
            if (player == null || !mayView(player, maid)) {
                continue;
            }
            player.connection.send(new ClientboundCustomPayloadPacket(
                    new PathfindingDebugPayload(maid.getId(), path, 0.5F)));
        }
    }

    public static void clear(UUID maidId) {
        SYNC_STATES.remove(maidId);
    }

    public static void reset() {
        SUBSCRIBERS.clear();
        SYNC_STATES.clear();
    }

    private static boolean mayView(ServerPlayer player, EntityMaid maid) {
        if (player.level() != maid.level()) {
            return false;
        }
        if (player.distanceToSqr(maid) > MAX_DISTANCE_SQUARED) {
            return false;
        }
        UUID ownerId = maid.getOwnerUUID();
        return player.hasPermissions(2) || ownerId != null && ownerId.equals(player.getUUID());
    }

    private static final class SyncState {
        private int pathIdentity;
        private int nextNodeIndex = -1;
        private long lastSentAt = Long.MIN_VALUE / 2;
        private long lastWarningAt = Long.MIN_VALUE / 2;
    }
}
