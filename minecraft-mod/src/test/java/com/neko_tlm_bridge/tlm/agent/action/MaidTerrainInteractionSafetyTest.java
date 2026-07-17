package com.neko_tlm_bridge.tlm.agent.action;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidTerrainInteractionSafetyTest {
    private static final UUID PLAYER = UUID.fromString(
            "00000000-0000-0000-0000-000000000123");

    @Test
    void rejectsBlockIntersectingAlivePlayerBody() {
        MaidTerrainInteractionSafety.Assessment assessment =
                MaidTerrainInteractionSafety.assessModification(
                        new BlockPos(0, 64, 0),
                        List.of(player(new AABB(
                                0.2D, 64.0D, 0.2D,
                                0.8D, 65.8D, 0.8D), true, false)));

        assertEquals(MaidTerrainInteractionSafety.Conflict.PLAYER_BODY,
                assessment.conflict());
        assertEquals(PLAYER, assessment.playerId());
    }

    @Test
    void rejectsEveryBlockSupportingPlayerFootprint() {
        AABB straddlingPlayer = new AABB(
                0.8D, 64.0D, 0.2D,
                1.4D, 65.8D, 0.8D);

        assertEquals(MaidTerrainInteractionSafety.Conflict.PLAYER_SUPPORT,
                MaidTerrainInteractionSafety.assessModification(
                        new BlockPos(0, 63, 0),
                        List.of(player(straddlingPlayer, true, false)))
                        .conflict());
        assertEquals(MaidTerrainInteractionSafety.Conflict.PLAYER_SUPPORT,
                MaidTerrainInteractionSafety.assessModification(
                        new BlockPos(1, 63, 0),
                        List.of(player(straddlingPlayer, true, false)))
                        .conflict());
    }

    @Test
    void ignoresDeadAndSpectatorPlayers() {
        BlockPos target = new BlockPos(0, 64, 0);
        AABB body = new AABB(
                0.2D, 64.0D, 0.2D,
                0.8D, 65.8D, 0.8D);

        assertTrue(MaidTerrainInteractionSafety.assessModification(
                target, List.of(player(body, false, false))).safe());
        assertTrue(MaidTerrainInteractionSafety.assessModification(
                target, List.of(player(body, true, true))).safe());
    }

    @Test
    void bodyConflictWinsOverSupportConflictAcrossWorkZone() {
        MaidTerrainInteractionSafety.PlayerOccupancy occupancy = player(
                new AABB(1.2D, 64.0D, 0.2D,
                        1.8D, 65.8D, 0.8D), true, false);

        MaidTerrainInteractionSafety.Assessment assessment =
                MaidTerrainInteractionSafety.assessWorkZone(
                        List.of(new BlockPos(1, 63, 0),
                                new BlockPos(1, 64, 0)),
                        List.of(occupancy));

        assertEquals(MaidTerrainInteractionSafety.Conflict.PLAYER_BODY,
                assessment.conflict());
    }

    private static MaidTerrainInteractionSafety.PlayerOccupancy player(
            AABB bounds, boolean alive, boolean spectator) {
        return new MaidTerrainInteractionSafety.PlayerOccupancy(
                PLAYER, bounds, alive, spectator);
    }
}
