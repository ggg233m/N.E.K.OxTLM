package com.neko_tlm_bridge.tlm.agent.action;

import net.minecraft.core.Direction;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;

/** Pure strategy functions for deterministic, testable mining decisions. */
final class AutonomousMiningStrategy {
    private AutonomousMiningStrategy() {
    }

    static ExcavateSegmentAction.Shape chooseShape(
            AutonomousMiningAction.ShapeMode requested,
            String selectorDescription,
            int currentY) {
        Objects.requireNonNull(requested, "requested");
        if (requested == AutonomousMiningAction.ShapeMode.LEVEL) {
            return ExcavateSegmentAction.Shape.LEVEL;
        }
        if (requested == AutonomousMiningAction.ShapeMode.STAIRCASE_DOWN) {
            return ExcavateSegmentAction.Shape.STAIRCASE_DOWN;
        }
        OptionalInt targetY = targetY(selectorDescription);
        return targetY.isPresent() && currentY > targetY.getAsInt()
                ? ExcavateSegmentAction.Shape.STAIRCASE_DOWN
                : ExcavateSegmentAction.Shape.LEVEL;
    }

    static OptionalInt targetY(String selectorDescription) {
        String selector = Objects.requireNonNull(selectorDescription,
                "selectorDescription").toLowerCase(Locale.ROOT);
        if (!selector.contains("minecraft:")) {
            return OptionalInt.empty();
        }
        if (selector.contains("ancient_debris")) {
            return OptionalInt.of(15);
        }
        if (selector.contains("diamond") || selector.contains("redstone")) {
            return OptionalInt.of(-54);
        }
        if (selector.contains("gold")) {
            return OptionalInt.of(-16);
        }
        if (selector.contains("lapis")) {
            return OptionalInt.of(0);
        }
        if (selector.contains("iron")) {
            return OptionalInt.of(16);
        }
        if (selector.contains("copper")) {
            return OptionalInt.of(48);
        }
        if (selector.contains("coal")) {
            return OptionalInt.of(96);
        }
        return OptionalInt.empty();
    }

    /** Primary, left, right, opposite; no duplicates and no unbounded retry. */
    static List<Direction> directionAttempts(Direction primary) {
        Direction horizontal = primary != null && primary.getAxis().isHorizontal()
                ? primary : Direction.NORTH;
        return List.of(horizontal, horizontal.getCounterClockWise(),
                horizontal.getClockWise(), horizontal.getOpposite());
    }
}
