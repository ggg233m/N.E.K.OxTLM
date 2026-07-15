package com.neko_tlm_bridge.tlm.agent.action;

import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.MaidActionResource;
import net.minecraft.core.Direction;
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
        assertTrue(action.resources().containsAll(List.of(
                MaidActionResource.MOVE,
                MaidActionResource.HAND,
                MaidActionResource.BREAK)));
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

    private static JsonObject args(String type, String id) {
        JsonObject selector = new JsonObject();
        selector.addProperty("type", type);
        selector.addProperty("id", id);
        JsonObject args = new JsonObject();
        args.add("selector", selector);
        return args;
    }
}
