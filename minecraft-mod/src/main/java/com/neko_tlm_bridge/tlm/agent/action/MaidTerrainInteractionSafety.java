package com.neko_tlm_bridge.tlm.agent.action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only player safety guard for terrain modification and movement work
 * zones. It deliberately treats every alive, non-spectator player equally:
 * an owner is not safer to place a block into, and another player must not be
 * mined out from under merely because they do not own the maid.
 */
public final class MaidTerrainInteractionSafety {
    private static final double SUPPORT_Y_TOLERANCE = 0.20D;

    private MaidTerrainInteractionSafety() {
    }

    public static Assessment assessModification(ServerLevel level, BlockPos target) {
        Objects.requireNonNull(level, "level");
        return assessModification(target, occupancies(level));
    }

    public static Assessment assessWorkZone(
            ServerLevel level, Collection<BlockPos> positions) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(positions, "positions");
        return assessWorkZone(positions, occupancies(level));
    }

    /** Visible for deterministic geometry tests without a running level. */
    static Assessment assessWorkZone(
            Collection<BlockPos> positions,
            Collection<PlayerOccupancy> players) {
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(players, "players");
        Assessment supportConflict = Assessment.clear();
        for (BlockPos position : positions) {
            Assessment assessment = assessModification(position, players);
            if (assessment.conflict() == Conflict.PLAYER_BODY) {
                return assessment;
            }
            if (!assessment.safe() && supportConflict.safe()) {
                supportConflict = assessment;
            }
        }
        return supportConflict;
    }

    /** Visible for deterministic geometry tests without a running level. */
    static Assessment assessModification(
            BlockPos target, Collection<PlayerOccupancy> players) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(players, "players");
        AABB block = new AABB(
                target.getX(), target.getY(), target.getZ(),
                target.getX() + 1.0D, target.getY() + 1.0D,
                target.getZ() + 1.0D);
        Assessment supportConflict = Assessment.clear();
        for (PlayerOccupancy player : players) {
            if (!player.alive() || player.spectator()) {
                continue;
            }
            if (block.intersects(player.bounds())) {
                return new Assessment(Conflict.PLAYER_BODY,
                        player.playerId(), target.immutable());
            }
            if (supportsFeet(block, player.bounds()) && supportConflict.safe()) {
                supportConflict = new Assessment(Conflict.PLAYER_SUPPORT,
                        player.playerId(), target.immutable());
            }
        }
        return supportConflict;
    }

    private static List<PlayerOccupancy> occupancies(ServerLevel level) {
        return level.players().stream()
                .map(MaidTerrainInteractionSafety::occupancy)
                .toList();
    }

    private static PlayerOccupancy occupancy(ServerPlayer player) {
        return new PlayerOccupancy(player.getUUID(), player.getBoundingBox(),
                player.isAlive(), player.isSpectator());
    }

    private static boolean supportsFeet(AABB block, AABB player) {
        boolean horizontalOverlap = block.maxX > player.minX
                && block.minX < player.maxX
                && block.maxZ > player.minZ
                && block.minZ < player.maxZ;
        return horizontalOverlap
                && Math.abs(block.maxY - player.minY) <= SUPPORT_Y_TOLERANCE;
    }

    public enum Conflict {
        NONE("none"),
        PLAYER_BODY("player_body"),
        PLAYER_SUPPORT("player_support");

        private final String wireName;

        Conflict(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public record Assessment(Conflict conflict, UUID playerId, BlockPos target) {
        public Assessment {
            Objects.requireNonNull(conflict, "conflict");
            if (conflict == Conflict.NONE) {
                playerId = null;
                target = null;
            } else {
                Objects.requireNonNull(playerId, "playerId");
                target = Objects.requireNonNull(target, "target").immutable();
            }
        }

        public boolean safe() {
            return conflict == Conflict.NONE;
        }

        public static Assessment clear() {
            return new Assessment(Conflict.NONE, null, null);
        }
    }

    record PlayerOccupancy(
            UUID playerId, AABB bounds, boolean alive, boolean spectator) {
        PlayerOccupancy {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(bounds, "bounds");
        }
    }
}
