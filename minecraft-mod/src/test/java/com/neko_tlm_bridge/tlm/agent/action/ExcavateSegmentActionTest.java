package com.neko_tlm_bridge.tlm.agent.action;

import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.MaidActionResource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcavateSegmentActionTest {
    @Test
    void parsesFrozenWireContract() {
        JsonObject args = new JsonObject();
        args.addProperty("direction", "west");
        args.addProperty("shape", "staircase_down");
        args.addProperty("length", 8);

        ExcavateSegmentAction action = ExcavateSegmentAction.fromArgs(args);

        assertEquals(MaidActionKind.EXCAVATE_SEGMENT, action.kind());
        assertEquals(Direction.WEST, action.direction());
        assertEquals(ExcavateSegmentAction.Shape.STAIRCASE_DOWN, action.shape());
        assertEquals(8, action.length());
        assertTrue(action.resources().containsAll(List.of(
                MaidActionResource.MOVE,
                MaidActionResource.HAND,
                MaidActionResource.BREAK)));
    }

    @Test
    void rejectsInvalidDirectionShapeAndLength() {
        assertThrows(IllegalArgumentException.class,
                () -> ExcavateSegmentAction.fromArgs(args("up", "level", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> ExcavateSegmentAction.fromArgs(args("north", "shaft", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> ExcavateSegmentAction.fromArgs(args("north", "level", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> ExcavateSegmentAction.fromArgs(args("north", "level", 9)));
        JsonObject unsupported = args("north", "level", 1);
        unsupported.addProperty("speed", 0.7D);
        assertThrows(IllegalArgumentException.class,
                () -> ExcavateSegmentAction.fromArgs(unsupported));
    }

    @Test
    void computesExactLevelAndStaircaseGeometry() {
        BlockPos origin = new BlockPos(10, 40, -2);
        BlockPos level = ExcavateSegmentAction.nextPosition(
                origin, Direction.EAST, ExcavateSegmentAction.Shape.LEVEL);
        assertEquals(new BlockPos(11, 40, -2), level);
        assertEquals(List.of(level, level.above()),
                ExcavateSegmentAction.clearanceFor(
                        level, ExcavateSegmentAction.Shape.LEVEL));

        BlockPos down = ExcavateSegmentAction.nextPosition(
                origin, Direction.SOUTH, ExcavateSegmentAction.Shape.STAIRCASE_DOWN);
        assertEquals(new BlockPos(10, 39, -1), down);
        assertEquals(List.of(down, down.above(), down.above(2)),
                ExcavateSegmentAction.clearanceFor(
                        down, ExcavateSegmentAction.Shape.STAIRCASE_DOWN));
    }

    @Test
    void externalEndReasonsAlwaysMapIntoFrozenStopReasonEnum() {
        ExcavateSegmentAction action = new ExcavateSegmentAction(
                Direction.NORTH, ExcavateSegmentAction.Shape.LEVEL, 1);

        JsonObject cancelled = action.terminationResult(null, ActionEndReason.REQUESTED);
        assertEquals("cancelled", cancelled.get("stop_reason").getAsString());
        assertEquals(0, cancelled.get("cleared_blocks").getAsInt());
        assertTrue(cancelled.get("cleared_block_details").isJsonArray());
        assertTrue(cancelled.get("encountered_blocks").isJsonArray());
        assertEquals(0, cancelled.get("segments_dug").getAsInt());
        assertEquals("cancelled", action.terminationResult(
                null, ActionEndReason.TIMEOUT).get("stop_reason").getAsString());
        assertEquals("target_changed", action.terminationResult(
                null, ActionEndReason.HAND_CONFLICT).get("stop_reason").getAsString());
        assertEquals("path_not_found", action.terminationResult(
                null, ActionEndReason.INTERNAL_ERROR).get("stop_reason").getAsString());
        assertEquals("protected_block", action.terminationResult(
                null, ActionEndReason.BLOCK_PROTECTED).get("stop_reason").getAsString());
        assertEquals("tool_not_found", action.terminationResult(
                null, ActionEndReason.TOOL_NOT_FOUND).get("stop_reason").getAsString());
        assertEquals("stuck", action.terminationResult(
                null, ActionEndReason.STUCK).get("stop_reason").getAsString());
    }

    @Test
    void stopReasonsExactlyMatchFrozenContract() {
        assertEquals(Set.of(
                        "completed", "water_hazard", "lava_hazard",
                        "protected_block", "tool_not_found", "unsafe_support",
                        "ore_encountered", "path_not_found", "stuck",
                        "target_changed", "cancelled"),
                java.util.Arrays.stream(ExcavateSegmentAction.StopReason.values())
                        .map(ExcavateSegmentAction.StopReason::wireName)
                        .collect(Collectors.toSet()));
    }

    private static JsonObject args(String direction, String shape, int length) {
        JsonObject args = new JsonObject();
        args.addProperty("direction", direction);
        args.addProperty("shape", shape);
        args.addProperty("length", length);
        return args;
    }
}
