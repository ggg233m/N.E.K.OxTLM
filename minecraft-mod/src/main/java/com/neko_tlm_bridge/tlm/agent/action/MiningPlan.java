package com.neko_tlm_bridge.tlm.agent.action;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, explicit prospecting policy for a selector-based harvest action.
 * A plan advances one adjacent cell at a time so the live world can be
 * rescanned and cancellation can take effect between every tunnel step.
 */
public record MiningPlan(
        Mode mode,
        Heading heading,
        int maxDistance,
        int maxDepth,
        int excavationBudget
) {
    private static final int DEFAULT_DISTANCE = 8;
    private static final int DEFAULT_DEPTH = 4;
    private static final int DEFAULT_EXCAVATION_BUDGET = 24;

    public MiningPlan {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(heading, "heading");
        requireRange(maxDistance, "mining_plan.max_distance", 1, 16);
        requireRange(maxDepth, "mining_plan.max_depth", 0, 12);
        requireRange(excavationBudget, "mining_plan.excavation_budget", 0, 64);
        if (mode == Mode.FORWARD_TUNNEL && maxDepth != 0) {
            throw new IllegalArgumentException(
                    "mining_plan.max_depth must be 0 for forward_tunnel");
        }
        if (mode == Mode.STAIRCASE_DOWN && maxDepth == 0) {
            throw new IllegalArgumentException(
                    "mining_plan.max_depth must be positive for staircase_down");
        }
        if (mode == Mode.STAIRCASE_DOWN && maxDepth > maxDistance) {
            throw new IllegalArgumentException(
                    "mining_plan.max_distance must be at least max_depth for staircase_down");
        }
        if (mode == Mode.AUTO && maxDepth >= maxDistance) {
            throw new IllegalArgumentException(
                    "mining_plan.max_distance must be greater than max_depth for auto");
        }
    }

    public static MiningPlan nearby() {
        return new MiningPlan(Mode.NEARBY, Heading.MAID_FACING,
                DEFAULT_DISTANCE, 0, DEFAULT_EXCAVATION_BUDGET);
    }

    public static MiningPlan fromArgs(JsonObject args, boolean selectorTargeting) {
        Objects.requireNonNull(args, "args");
        if (!args.has("mining_plan")) {
            return nearby();
        }
        if (!args.get("mining_plan").isJsonObject()) {
            throw new IllegalArgumentException("mining_plan must be an object");
        }
        JsonObject value = args.getAsJsonObject("mining_plan");
        Set<String> allowedFields = Set.of(
                "mode", "direction", "max_distance", "max_depth", "excavation_budget");
        for (String name : value.keySet()) {
            if (!allowedFields.contains(name)) {
                throw new IllegalArgumentException(
                        "Unsupported mining_plan field: " + name);
            }
        }
        Mode mode = Mode.fromWireName(optionalString(value, "mode", "nearby"));
        if (mode.enabled() && !selectorTargeting) {
            throw new IllegalArgumentException(
                    "mining_plan exploration requires selector targeting");
        }
        Heading heading = Heading.fromWireName(
                optionalString(value, "direction", "maid_facing"));
        int maxDistance = optionalInt(value, "max_distance", DEFAULT_DISTANCE);
        int defaultDepth = switch (mode) {
            case STAIRCASE_DOWN, AUTO -> DEFAULT_DEPTH;
            case NEARBY, FORWARD_TUNNEL -> 0;
        };
        int maxDepth = optionalInt(value, "max_depth", defaultDepth);
        int budget = optionalInt(value, "excavation_budget", DEFAULT_EXCAVATION_BUDGET);
        return new MiningPlan(mode, heading, maxDistance, maxDepth, budget);
    }

    public boolean enabled() {
        return mode.enabled();
    }

    public Direction resolveDirection(Direction maidFacing) {
        if (heading.direction != null) {
            return heading.direction;
        }
        return maidFacing != null && maidFacing.getAxis().isHorizontal()
                ? maidFacing
                : Direction.NORTH;
    }

    public boolean hasNextStep(int completedSteps, int descentSteps) {
        if (!enabled() || completedSteps >= maxDistance) {
            return false;
        }
        return mode != Mode.STAIRCASE_DOWN || descentSteps < maxDepth;
    }

    public StepMode nextStepMode(int descentSteps) {
        return switch (mode) {
            case NEARBY -> throw new IllegalStateException("nearby mode has no prospecting step");
            case FORWARD_TUNNEL -> StepMode.FORWARD;
            case STAIRCASE_DOWN -> StepMode.DESCEND;
            case AUTO -> descentSteps < maxDepth ? StepMode.DESCEND : StepMode.FORWARD;
        };
    }

    public BlockPos nextDestination(BlockPos current, Direction direction, int descentSteps) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(direction, "direction");
        if (!direction.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("prospecting direction must be horizontal");
        }
        BlockPos forward = current.relative(direction);
        return nextStepMode(descentSteps) == StepMode.DESCEND ? forward.below() : forward;
    }

    public enum Mode {
        NEARBY("nearby"),
        FORWARD_TUNNEL("forward_tunnel"),
        STAIRCASE_DOWN("staircase_down"),
        AUTO("auto");

        private final String wireName;

        Mode(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public boolean enabled() {
            return this != NEARBY;
        }

        private static Mode fromWireName(String value) {
            for (Mode mode : values()) {
                if (mode.wireName.equals(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "mining_plan.mode must be nearby, forward_tunnel, staircase_down or auto");
        }
    }

    public enum Heading {
        MAID_FACING("maid_facing", null),
        NORTH("north", Direction.NORTH),
        SOUTH("south", Direction.SOUTH),
        EAST("east", Direction.EAST),
        WEST("west", Direction.WEST);

        private final String wireName;
        private final Direction direction;

        Heading(String wireName, Direction direction) {
            this.wireName = wireName;
            this.direction = direction;
        }

        private static Heading fromWireName(String value) {
            for (Heading heading : values()) {
                if (heading.wireName.equals(value)) {
                    return heading;
                }
            }
            throw new IllegalArgumentException(
                    "mining_plan.direction must be maid_facing, north, south, east or west");
        }
    }

    public enum StepMode {
        FORWARD,
        DESCEND
    }

    private static String optionalString(JsonObject parent, String name, String fallback) {
        if (!parent.has(name)) {
            return fallback;
        }
        if (!parent.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("mining_plan." + name + " must be a string");
        }
        return parent.get(name).getAsString().trim().toLowerCase(Locale.ROOT);
    }

    private static int optionalInt(JsonObject parent, String name, int fallback) {
        if (!parent.has(name)) {
            return fallback;
        }
        if (!parent.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("mining_plan." + name + " must be an integer");
        }
        try {
            return parent.get(name).getAsInt();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "mining_plan." + name + " must be an integer", exception);
        }
    }

    private static void requireRange(int value, String name, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }
}
