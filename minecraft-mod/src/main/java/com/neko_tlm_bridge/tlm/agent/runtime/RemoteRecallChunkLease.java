package com.neko_tlm_bridge.tlm.agent.runtime;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.neko_tlm_bridge.ws.handler.MaidHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 仅用于玩家显式远程召回的小型移动区块票据。它让未加载的女仆开始 tick，
 * 并在行走时维持有界规划范围；普通寻路或采矿不会使用它。
 */
public final class RemoteRecallChunkLease implements AutoCloseable {
    private static final int TICKET_RADIUS = 3;
    private static final long PREPARED_TTL_TICKS = 200L;
    private static final TicketType<UUID> TICKET_TYPE = TicketType.create(
            "neko_tlm_remote_recall", UUID::compareTo);
    private static final Map<UUID, RemoteRecallChunkLease> PREPARED = new HashMap<>();

    private final ServerLevel level;
    private final UUID maidId;
    private ChunkPos center;
    private long expiresAt;
    private boolean closed;

    private RemoteRecallChunkLease(ServerLevel level, UUID maidId, ChunkPos center) {
        this.level = Objects.requireNonNull(level, "level");
        this.maidId = Objects.requireNonNull(maidId, "maidId");
        this.center = Objects.requireNonNull(center, "center");
        this.expiresAt = level.getGameTime() + PREPARED_TTL_TICKS;
        level.getChunkSource().addRegionTicket(
                TICKET_TYPE, center, TICKET_RADIUS, maidId);
    }

    /** 为后续重试启动动作准备未加载的女仆。 */
    public static Preparation prepare(MinecraftServer server, String maidIdText) {
        UUID maidId;
        try {
            maidId = UUID.fromString(maidIdText);
        } catch (IllegalArgumentException invalid) {
            return new Preparation(null, "MAID_NOT_FOUND");
        }
        EntityMaid loaded = MaidHelper.findMaidById(server, maidIdText);
        if (loaded != null) {
            return new Preparation(loaded, null);
        }
        MaidHelper.UnloadedMaid unloaded = MaidHelper.findUnloadedMaid(server, maidIdText);
        if (unloaded == null) {
            return new Preparation(null, "MAID_NOT_FOUND");
        }
        if (unloaded.owner().level() != unloaded.level()) {
            return new Preparation(null, "OWNER_NOT_IN_MAID_DIMENSION");
        }
        ChunkPos savedChunk = new ChunkPos(unloaded.info().getChunkPos());
        RemoteRecallChunkLease lease = PREPARED.get(maidId);
        if (lease == null || lease.closed || lease.level != unloaded.level()) {
            if (lease != null) {
                lease.close();
            }
            lease = new RemoteRecallChunkLease(unloaded.level(), maidId, savedChunk);
            PREPARED.put(maidId, lease);
        } else {
            lease.expiresAt = unloaded.level().getGameTime() + PREPARED_TTL_TICKS;
            lease.moveTo(savedChunk);
        }

        // 中心 FULL 区块采用同步且有界的加载方式，并且仅由用户请求的召回触发。
        // 实体 NBT 可能在后续服务器 tick 才加入世界，此时调用方会在票据保持期间重试。
        unloaded.level().getChunk(savedChunk.x, savedChunk.z);
        loaded = MaidHelper.findMaidById(server, maidIdText);
        return loaded == null
                ? new Preparation(null, "MAID_LOAD_PENDING")
                : new Preparation(loaded, null);
    }

    /** 认领已准备的租约；若远程女仆已经加载，则创建一个新租约。 */
    public static RemoteRecallChunkLease claim(EntityMaid maid) {
        UUID maidId = maid.getUUID();
        RemoteRecallChunkLease lease = PREPARED.remove(maidId);
        if (lease != null && (lease.closed || lease.level != maid.level())) {
            lease.close();
            lease = null;
        }
        if (lease == null) {
            lease = new RemoteRecallChunkLease(
                    (ServerLevel) maid.level(), maidId, new ChunkPos(maid.blockPosition()));
        } else {
            lease.moveTo(maid.blockPosition());
        }
        lease.expiresAt = Long.MAX_VALUE;
        return lease;
    }

    public void moveTo(BlockPos position) {
        moveTo(new ChunkPos(position));
    }

    private void moveTo(ChunkPos next) {
        if (closed || center.equals(next)) {
            return;
        }
        // 先添加新票据，确保女仆不会在任意 tick 中失去已加载的中心区块。
        level.getChunkSource().addRegionTicket(
                TICKET_TYPE, next, TICKET_RADIUS, maidId);
        level.getChunkSource().removeRegionTicket(
                TICKET_TYPE, center, TICKET_RADIUS, maidId);
        center = next;
    }

    public static void tickPrepared() {
        Iterator<RemoteRecallChunkLease> iterator = PREPARED.values().iterator();
        while (iterator.hasNext()) {
            RemoteRecallChunkLease lease = iterator.next();
            if (lease.closed || lease.level.getGameTime() >= lease.expiresAt) {
                iterator.remove();
                lease.close();
            }
        }
    }

    public static void shutdownPrepared() {
        PREPARED.values().forEach(RemoteRecallChunkLease::close);
        PREPARED.clear();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        level.getChunkSource().removeRegionTicket(
                TICKET_TYPE, center, TICKET_RADIUS, maidId);
    }

    public record Preparation(EntityMaid maid, String errorCode) {
    }
}
