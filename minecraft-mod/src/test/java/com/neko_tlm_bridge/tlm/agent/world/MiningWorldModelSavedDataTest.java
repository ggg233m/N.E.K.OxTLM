package com.neko_tlm_bridge.tlm.agent.world;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningWorldModelSavedDataTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void roundTripRestoresSessionGraphHazardsAndGenerationFloor() {
        MiningWorldModelSavedData data = new MiningWorldModelSavedData(DIMENSION);
        UUID actionId = UUID.randomUUID();
        UUID maidId = UUID.randomUUID();
        JsonObject args = miningArgs(24, "north", "staircase_down", 8);

        data.getOrCreateOperation(actionId, maidId, args, 100L);
        data.updateGeneration(actionId, 7L, 101L);
        data.setOrigin(actionId, new BlockPos(10, 48, 10), 102L);
        UUID junction = data.addNode(actionId,
                MiningWorldModelSavedData.NodeType.JUNCTION,
                new BlockPos(10, 46, 6), "", 0, 103L);
        UUID workface = data.updateWorkface(
                actionId, new BlockPos(10, 44, 2), 104L);
        data.addNode(actionId, MiningWorldModelSavedData.NodeType.VEIN,
                new BlockPos(11, 44, 2), "#minecraft:iron_ores", 6, 105L);
        data.addNode(actionId, MiningWorldModelSavedData.NodeType.SUPPLY,
                new BlockPos(10, 48, 9), "minecraft:chest", 0, 106L);

        MiningWorldModelSavedData.OperationSnapshot withNodes =
                data.operation(actionId).orElseThrow();
        data.addSegment(actionId, withNodes.entryNodeId(), junction,
                4, 1, 2, true, 107L);
        data.addSegment(actionId, junction, workface,
                4, 1, 2, true, 108L);
        data.addDanger(actionId, MiningWorldModelSavedData.DangerType.LAVA,
                MiningWorldModelSavedData.DangerSeverity.DANGEROUS,
                new BlockPos(9, 44, 1), 2, "lava pocket", 109L);
        data.updateCounts(actionId, 3, 8L, 19L, 110L);
        data.updateConstructionCounts(actionId, 7L, 5L, 2L, 110L);
        data.updatePhase(actionId, "scanning", 111L);
        data.markBlocked(actionId, "lava_ahead", 112L);

        CompoundTag root = data.save(new CompoundTag(), null);
        MiningWorldModelSavedData restored =
                MiningWorldModelSavedData.load(root, null);
        MiningWorldModelSavedData.OperationSnapshot session =
                restored.operation(actionId).orElseThrow();

        assertEquals(actionId, session.operationId());
        assertEquals(maidId, session.maidId());
        assertEquals(DIMENSION, session.dimensionId());
        assertEquals(7L, session.generation());
        assertEquals(24, session.targetCount());
        assertEquals(3, session.collectedCount());
        assertEquals("blocked", session.phase());
        assertEquals(new BlockPos(10, 48, 10), session.originPos());
        assertEquals(new BlockPos(10, 44, 2), session.currentWorkfacePos());
        assertEquals("north", session.mainDirection());
        assertEquals("staircase_down", session.shape());
        assertEquals(8, session.segmentLength());
        assertEquals(0.85D, session.normalizedArgs().get("speed").getAsDouble());
        assertEquals("loaded_scan",
                session.normalizedArgs().get("discovery_mode").getAsString());
        assertEquals("disabled",
                session.normalizedArgs().get("placement_policy").getAsString());
        assertEquals(0, session.normalizedArgs().get("max_placements").getAsInt());
        assertEquals(8L, session.segmentsDug());
        assertEquals(19L, session.clearedBlocks());
        assertEquals(7L, session.placementsUsed());
        assertEquals(5L, session.bridgeSupportsPlaced());
        assertEquals(2L, session.waterSealsPlaced());
        assertEquals("lava_ahead", session.blockedReason());
        assertTrue(session.active());
        assertTrue(session.blocked());
        assertFalse(session.terminal());
        assertEquals(5, session.nodes().size());
        assertEquals(2, session.segments().size());
        assertEquals(1, session.dangers().size());
        assertEquals(actionId,
                restored.findResumableByMaid(maidId).orElseThrow().operationId());
    }

    @Test
    void terminalSessionIsNotResumable() {
        MiningWorldModelSavedData data = new MiningWorldModelSavedData(DIMENSION);
        UUID actionId = UUID.randomUUID();
        UUID maidId = UUID.randomUUID();
        data.getOrCreateOperation(
                actionId, maidId, miningArgs(1, "auto", "auto", 8), 1L);

        data.markTerminal(actionId,
                MiningWorldModelSavedData.OperationStatus.CANCELLED,
                "requested", 2L);

        MiningWorldModelSavedData.OperationSnapshot terminal =
                data.operation(actionId).orElseThrow();
        assertFalse(terminal.active());
        assertFalse(terminal.blocked());
        assertTrue(terminal.terminal());
        assertTrue(data.findResumableByMaid(maidId).isEmpty());
    }

    @Test
    void getOrCreateIsIdempotentButRejectsActionIdArgumentReuse() {
        MiningWorldModelSavedData data = new MiningWorldModelSavedData(DIMENSION);
        UUID actionId = UUID.randomUUID();
        UUID maidId = UUID.randomUUID();
        JsonObject args = miningArgs(12, "east", "level", 6);

        MiningWorldModelSavedData.OperationSnapshot first =
                data.getOrCreateOperation(actionId, maidId, args, 1L);
        MiningWorldModelSavedData.OperationSnapshot replay =
                data.getOrCreateOperation(actionId, maidId, args.deepCopy(), 2L);
        assertEquals(first, replay);

        JsonObject changed = args.deepCopy();
        changed.addProperty("speed", 0.55D);
        assertThrows(IllegalArgumentException.class,
                () -> data.getOrCreateOperation(actionId, maidId, changed, 3L));
        assertThrows(IllegalArgumentException.class,
                () -> data.getOrCreateOperation(
                        actionId, UUID.randomUUID(), args, 3L));
    }

    @Test
    void danglingSegmentsAreDiscardedDuringRecovery() {
        MiningWorldModelSavedData data = new MiningWorldModelSavedData(DIMENSION);
        UUID actionId = UUID.randomUUID();
        UUID maidId = UUID.randomUUID();
        data.createOperation(actionId, maidId, new BlockPos(0, 32, 0), 1L);
        UUID workface = data.updateWorkface(actionId, new BlockPos(0, 32, -4), 2L);
        UUID entry = data.operation(actionId).orElseThrow().entryNodeId();
        data.addSegment(actionId, entry, workface, 4, 1, 2, true, 3L);

        CompoundTag root = data.save(new CompoundTag(), null);
        CompoundTag operation = root.getList("Operations", Tag.TAG_COMPOUND)
                .getCompound(0);
        CompoundTag dangling = new CompoundTag();
        dangling.putUUID("Id", UUID.randomUUID());
        dangling.putUUID("From", entry);
        dangling.putUUID("To", UUID.randomUUID());
        dangling.putInt("Length", 3);
        dangling.putInt("Width", 1);
        dangling.putInt("Height", 2);
        dangling.putBoolean("Traversable", true);
        operation.getList("Segments", Tag.TAG_COMPOUND).add(dangling);

        MiningWorldModelSavedData restored =
                MiningWorldModelSavedData.load(root, null);
        assertEquals(1,
                restored.operation(actionId).orElseThrow().segments().size());
    }

    @Test
    void serializedModelNeverContainsPerBlockRuntimeCaches() {
        MiningWorldModelSavedData data = new MiningWorldModelSavedData(DIMENSION);
        UUID actionId = UUID.randomUUID();
        data.getOrCreateOperation(actionId, UUID.randomUUID(),
                miningArgs(8, "south", "level", 8), 1L);
        data.updateCounts(actionId, 1, 2L, 4L, 2L);

        CompoundTag root = data.save(new CompoundTag(), null);
        assertNoForbiddenKeys(root, Set.of(
                "Path", "PathNodes", "ScannedBlocks", "ClearedPositions",
                "ClearedBlockPositions", "PerBlockCache", "CandidateBlocks"));
        CompoundTag operation = root.getList("Operations", Tag.TAG_COMPOUND)
                .getCompound(0);
        assertTrue(operation.contains("ClearedBlocks", Tag.TAG_LONG),
                "the aggregate cleared block counter must remain durable");
    }

    @Test
    void movingWorkfaceStaysCompactAndNewOperationSupersedesOldSession() {
        MiningWorldModelSavedData data = new MiningWorldModelSavedData(DIMENSION);
        UUID maidId = UUID.randomUUID();
        UUID firstAction = UUID.randomUUID();
        JsonObject args = miningArgs(8, "north", "level", 8);
        data.getOrCreateOperation(firstAction, maidId, args, 1L);
        data.setOrigin(firstAction, new BlockPos(0, 32, 0), 2L);

        UUID firstWorkface = data.updateWorkface(
                firstAction, new BlockPos(0, 32, -1), 3L);
        UUID movedWorkface = data.updateWorkface(
                firstAction, new BlockPos(0, 32, -8), 4L);
        MiningWorldModelSavedData.OperationSnapshot first =
                data.operation(firstAction).orElseThrow();
        assertEquals(firstWorkface, movedWorkface);
        assertEquals(2, first.nodes().size(),
                "entry plus one moving workface must remain compact");

        UUID replacementAction = UUID.randomUUID();
        data.getOrCreateOperation(replacementAction, maidId, args, 5L);
        assertEquals(MiningWorldModelSavedData.OperationStatus.SUPERSEDED,
                data.operation(firstAction).orElseThrow().status());
        assertEquals(replacementAction,
                data.findResumableByMaid(maidId).orElseThrow().operationId());
    }

    private static JsonObject miningArgs(
            int targetCount, String direction, String shape, int segmentLength) {
        JsonObject selector = new JsonObject();
        selector.addProperty("type", "tag");
        selector.addProperty("id", "minecraft:iron_ores");
        JsonObject args = new JsonObject();
        args.add("selector", selector);
        args.addProperty("target_count", targetCount);
        args.addProperty("direction", direction);
        args.addProperty("shape", shape);
        args.addProperty("segment_length", segmentLength);
        args.addProperty("speed", 0.85D);
        args.addProperty("discovery_mode", "loaded_scan");
        return args;
    }

    private static void assertNoForbiddenKeys(Tag tag, Set<String> forbidden) {
        assertNotNull(tag);
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                assertFalse(forbidden.contains(key),
                        () -> "forbidden runtime cache key was serialized: " + key);
                assertNoForbiddenKeys(compound.get(key), forbidden);
            }
        } else if (tag instanceof ListTag list) {
            for (Tag child : list) {
                assertNoForbiddenKeys(child, forbidden);
            }
        }
    }
}
