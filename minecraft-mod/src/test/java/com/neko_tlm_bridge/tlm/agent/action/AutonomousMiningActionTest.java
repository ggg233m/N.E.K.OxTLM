package com.neko_tlm_bridge.tlm.agent.action;

import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.MaidActionResource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousMiningActionTest {
    @Test
    void parsesFrozenWireContractAndDefaults() {
        JsonObject args = args("tag", "minecraft:diamond_ores");

        AutonomousMiningAction action = AutonomousMiningAction.fromArgs(args);

        assertEquals(MaidActionKind.AUTONOMOUS_MINING, action.kind());
        assertEquals(1, action.targetCount());
        assertEquals(8, action.segmentLength());
        assertEquals(0.7D, action.speed());
        assertEquals(AutonomousMiningAction.DirectionMode.AUTO,
                action.directionMode());
        assertEquals(AutonomousMiningAction.ShapeMode.AUTO, action.shapeMode());
        assertEquals(AutonomousMiningAction.DiscoveryMode.LOADED_SCAN,
                action.discoveryMode());
        assertEquals("tag:#minecraft:diamond_ores", action.selectorDescription());
        assertEquals(1, action.normalizedArgs().get("target_count").getAsInt());
        assertEquals("auto", action.normalizedArgs().get("direction").getAsString());
        assertEquals("disabled",
                action.normalizedArgs().get("placement_policy").getAsString());
        assertEquals(0, action.normalizedArgs().get("max_placements").getAsInt());
        assertTrue(action.resources().containsAll(List.of(
                MaidActionResource.MOVE,
                MaidActionResource.HAND,
                MaidActionResource.BREAK)));
        assertFalse(action.resources().contains(MaidActionResource.PLACE));
    }

    @Test
    void explicitSafeConstructionClaimsPlaceResource() {
        JsonObject args = args("tag", "minecraft:diamond_ores");
        args.addProperty("placement_policy", "safe_support_and_water_seal");

        AutonomousMiningAction action = AutonomousMiningAction.fromArgs(args);

        assertTrue(action.resources().contains(MaidActionResource.PLACE));
    }

    @Test
    void parsesExplicitPlanningControls() {
        JsonObject args = args("block", "minecraft:ancient_debris");
        args.addProperty("target_count", 32);
        args.addProperty("direction", "west");
        args.addProperty("shape", "staircase_down");
        args.addProperty("segment_length", 3);
        args.addProperty("speed", 0.5D);
        args.addProperty("discovery_mode", "exposed_only");
        args.addProperty("placement_policy", "disabled");
        args.addProperty("max_placements", 12);

        AutonomousMiningAction action = AutonomousMiningAction.fromArgs(args);

        assertEquals(32, action.targetCount());
        assertEquals(AutonomousMiningAction.DirectionMode.WEST,
                action.directionMode());
        assertEquals(AutonomousMiningAction.ShapeMode.STAIRCASE_DOWN,
                action.shapeMode());
        assertEquals(3, action.segmentLength());
        assertEquals(0.5D, action.speed());
        assertEquals(AutonomousMiningAction.DiscoveryMode.EXPOSED_ONLY,
                action.discoveryMode());
        assertEquals("disabled",
                action.normalizedArgs().get("placement_policy").getAsString());
        assertEquals(12,
                action.normalizedArgs().get("max_placements").getAsInt());
        assertFalse(action.resources().contains(MaidActionResource.PLACE));
    }

    @Test
    void rejectsUnknownAndOutOfRangeFields() {
        JsonObject unknown = args("block", "minecraft:stone");
        unknown.addProperty("max_segments", 4);
        assertThrows(IllegalArgumentException.class,
                () -> AutonomousMiningAction.fromArgs(unknown));

        JsonObject count = args("block", "minecraft:stone");
        count.addProperty("target_count", 0);
        assertThrows(IllegalArgumentException.class,
                () -> AutonomousMiningAction.fromArgs(count));

        JsonObject length = args("block", "minecraft:stone");
        length.addProperty("segment_length", 9);
        assertThrows(IllegalArgumentException.class,
                () -> AutonomousMiningAction.fromArgs(length));

        JsonObject speed = args("block", "minecraft:stone");
        speed.addProperty("speed", 1.1D);
        assertThrows(IllegalArgumentException.class,
                () -> AutonomousMiningAction.fromArgs(speed));

        JsonObject placements = args("block", "minecraft:stone");
        placements.addProperty("max_placements", 4097);
        assertThrows(IllegalArgumentException.class,
                () -> AutonomousMiningAction.fromArgs(placements));

        JsonObject placementPolicy = args("block", "minecraft:stone");
        placementPolicy.addProperty("placement_policy", "unsafe");
        assertThrows(IllegalArgumentException.class,
                () -> AutonomousMiningAction.fromArgs(placementPolicy));
    }

    @Test
    void externalTerminationSnapshotUsesStableLongActionFields() {
        AutonomousMiningAction action = AutonomousMiningAction.fromArgs(
                args("block", "minecraft:diamond_ore"));

        JsonObject result = action.terminationResult(null, ActionEndReason.REQUESTED);

        assertEquals("VALIDATING", result.get("phase").getAsString());
        assertEquals(0, result.get("collected_count").getAsInt());
        assertEquals(1, result.get("target_count").getAsInt());
        assertEquals(0, result.get("segments_dug").getAsInt());
        assertEquals(0, result.get("cleared_blocks").getAsInt());
        assertEquals("requested", result.get("blocked_reason").getAsString());
        assertFalse(result.get("decision_required").getAsBoolean());
        assertTrue(result.get("origin").isJsonObject());
        assertTrue(result.get("real_end").isJsonObject());
    }

    @Test
    void resolvesAutoDirectionFromMaidFacing() {
        assertEquals(Direction.SOUTH, AutonomousMiningAction.resolveDirection(
                AutonomousMiningAction.DirectionMode.AUTO, Direction.SOUTH));
        assertEquals(Direction.EAST, AutonomousMiningAction.resolveDirection(
                AutonomousMiningAction.DirectionMode.EAST, Direction.NORTH));
        assertEquals(Direction.NORTH, AutonomousMiningAction.resolveDirection(
                AutonomousMiningAction.DirectionMode.AUTO, Direction.UP));
    }

    @Test
    void routeClearedSelectorBlocksCountAndLockConnectedMiningProgress() {
        AutonomousMiningAction action = AutonomousMiningAction.fromArgs(
                args("block", "minecraft:diamond_ore"));

        assertTrue(action.recordRouteClearedBlock(
                new BlockPos(4, -54, 7), Blocks.DIAMOND_ORE.defaultBlockState()));
        assertTrue(action.recordRouteClearedBlock(
                new BlockPos(5, -54, 7), Blocks.DIAMOND_ORE.defaultBlockState()));
        assertFalse(action.recordRouteClearedBlock(
                new BlockPos(5, -54, 7), Blocks.DIAMOND_ORE.defaultBlockState()));

        JsonObject result = action.terminationResult(null, ActionEndReason.REQUESTED);
        assertEquals(2, result.get("collected_count").getAsInt());
        assertTrue(result.get("minimum_reached").getAsBoolean());
        assertTrue(result.get("vein_locked").getAsBoolean());
        assertFalse(result.get("vein_complete").getAsBoolean());
        assertEquals(1, result.get("target_overshoot").getAsInt());
        assertEquals("target_count_is_minimum_finish_committed_vein",
                result.get("completion_rule").getAsString());
        // target_count is a minimum; route collection does not complete until
        // the locked connected vein is rescanned and exhausted.
        assertEquals("VALIDATING", result.get("phase").getAsString());
    }

    @Test
    void routeClearedForeignOreDoesNotCountAsSelectorProgress() {
        AutonomousMiningAction action = AutonomousMiningAction.fromArgs(
                args("block", "minecraft:diamond_ore"));

        assertFalse(action.recordRouteClearedBlock(
                new BlockPos(0, 16, 0), Blocks.IRON_ORE.defaultBlockState()));

        JsonObject result = action.terminationResult(null, ActionEndReason.REQUESTED);
        assertEquals(0, result.get("collected_count").getAsInt());
    }

    private static JsonObject args(String type, String id) {
        JsonObject selector = new JsonObject();
        selector.addProperty("type", type);
        selector.addProperty("id", id);
        JsonObject args = new JsonObject();
        args.add("selector", selector);
        return args;
    }
}
