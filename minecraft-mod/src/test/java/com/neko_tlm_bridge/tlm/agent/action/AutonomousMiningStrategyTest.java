package com.neko_tlm_bridge.tlm.agent.action;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousMiningStrategyTest {
    @Test
    void mapsVanillaOreSelectorsToFrozenTargetLayers() {
        assertEquals(-54, AutonomousMiningStrategy.targetY(
                "tag:#minecraft:diamond_ores").orElseThrow());
        assertEquals(-54, AutonomousMiningStrategy.targetY(
                "block:minecraft:redstone_ore").orElseThrow());
        assertEquals(-16, AutonomousMiningStrategy.targetY(
                "tag:#minecraft:gold_ores").orElseThrow());
        assertEquals(0, AutonomousMiningStrategy.targetY(
                "block:minecraft:lapis_ore").orElseThrow());
        assertEquals(16, AutonomousMiningStrategy.targetY(
                "tag:#minecraft:iron_ores").orElseThrow());
        assertEquals(48, AutonomousMiningStrategy.targetY(
                "block:minecraft:copper_ore").orElseThrow());
        assertEquals(96, AutonomousMiningStrategy.targetY(
                "tag:#minecraft:coal_ores").orElseThrow());
        assertEquals(15, AutonomousMiningStrategy.targetY(
                "block:minecraft:ancient_debris").orElseThrow());
        assertTrue(AutonomousMiningStrategy.targetY(
                "tag:#examplemod:moon_ores").isEmpty());
    }

    @Test
    void autoDescendsOnlyWhileAboveKnownTargetLayer() {
        assertEquals(ExcavateSegmentAction.Shape.STAIRCASE_DOWN,
                AutonomousMiningStrategy.chooseShape(
                        AutonomousMiningAction.ShapeMode.AUTO,
                        "tag:#minecraft:diamond_ores", 32));
        assertEquals(ExcavateSegmentAction.Shape.LEVEL,
                AutonomousMiningStrategy.chooseShape(
                        AutonomousMiningAction.ShapeMode.AUTO,
                        "tag:#minecraft:diamond_ores", -54));
        assertEquals(ExcavateSegmentAction.Shape.LEVEL,
                AutonomousMiningStrategy.chooseShape(
                        AutonomousMiningAction.ShapeMode.AUTO,
                        "tag:#examplemod:moon_ores", 100));
    }

    @Test
    void directionSweepIsFinitePrimaryLeftRightBack() {
        assertEquals(List.of(Direction.NORTH, Direction.WEST,
                        Direction.EAST, Direction.SOUTH),
                AutonomousMiningStrategy.directionAttempts(Direction.NORTH));
    }
}
