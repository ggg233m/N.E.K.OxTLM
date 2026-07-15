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
        int excavationBudget,
        int maxSegments
) {
    private static final int DEFAULT_DISTANCE = 8;
    private static final int DEFAULT_DEPTH = 4;
    private static final int DEFAULT_EXCAVATION_BUDGET = 24;
    private static final int AUTOMATIC_EXCAVATION_BUDGET = 64;
    private static final int AUTOMATIC_MAX_SEGMENTS = 4;

    public MiningPlan {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(heading, "heading");
        requireRange(maxDistance, "mining_plan.max_distance", 1, 16);
        requireRange(maxDepth, "mining_plan.max_depth", 0, 12);
        requireRange(excavationBudget, "mining_plan.excavation_budget", 0, 256);
        requireRange(maxSegments, "mining_plan.max_segments", 1, 4);
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

    /** Backwards-compatible single-segment constructor for existing callers. */
    public MiningPlan(Mode mode, Heading heading, int maxDistance, int maxDepth,
                      int excavationBudget) {
        this(mode, heading, maxDistance, maxDepth, excavationBudget, 1);
    }

    public static MiningPlan nearby() {
        return new MiningPlan(Mode.NEARBY, Heading.MAID_FACING,
                DEFAULT_DISTANCE, 0, DEFAULT_EXCAVATION_BUDGET, 1);
    }

    public static MiningPlan automatic() {
        return new MiningPlan(Mode.AUTO, Heading.MAID_FACING,
                DEFAULT_DISTANCE, DEFAULT_DEPTH, AUTOMATIC_EXCAVATION_BUDGET,
                AUTOMATIC_MAX_SEGMENTS);
    }

    public static MiningPlan fromArgs(JsonObject args, boolean selectorTargeting) {
        return fromArgs(args, selectorTargeting, false);
    }

    public static MiningPlan fromArgs(JsonObject args, boolean selectorTargeting,
                                      boolean defaultAuto) {
        Objects.requireNonNull(args, "args");
        if (!args.has("mining_plan")) {
            return defaultAuto ? automatic() : nearby();
        }
        if (!args.get("mining_plan").isJsonObject()) {
            throw new IllegalArgumentException("mining_plan must be an object");
        }
        JsonObject value = args.getAsJsonObject("mining_plan");
        Set<String> allowedFields = Set.of(
                "mode", "direction", "max_distance", "max_depth", "excavation_budget",
                "max_segments");
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
        int maxSegments = optionalInt(value, "max_segments", 1);
        int defaultBudget = maxSegments > 1
                ? AUTOMATIC_EXCAVATION_BUDGET : DEFAULT_EXCAVATION_BUDGET;
        int budget = optionalInt(value, "excavation_budget", defaultBudget);
        return new MiningPlan(mode, heading, maxDistance, maxDepth, budget, maxSegments);
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

    /**
     * Compatibility diagnostic. Prospecting no longer has an action-wide
     * step cap; cancellation, deadline and live-world safety remain the
     * terminal boundaries.
     */
    public int totalStepLimit() {
        return Integer.MAX_VALUE;
    }

    /** Compatibility diagnostic for the removed action-wide descent cap. */
    public int totalDescentLimit() {
        return Integer.MAX_VALUE;
    }

    /**
     * Returns whether the current segment may execute one more step while
     * respecting the shape of the current segment. Segment exhaustion rolls
     * into another segment instead of terminating the action.
     */
    public boolean hasNextStep(long totalSteps, long totalDescent,
                               int segmentSteps, int segmentDescent) {
        if (!enabled() || segmentSteps >= maxDistance) {
            return false;
        }
        if (nextStepMode(segmentDescent) != StepMode.DESCEND) {
            return true;
        }
        return segmentDescent < maxDepth;
    }

    /**
     * Backwards-compatible single-segment query. Multi-segment executors
     * should use the four-counter overload and {@link #canAdvanceSegment}.
     */
    public boolean hasNextStep(long completedSteps, long descentSteps) {
        int segmentSteps = (int) Math.max(0L,
                Math.min((long) Integer.MAX_VALUE, completedSteps));
        int segmentDescent = (int) Math.max(0L,
                Math.min((long) Integer.MAX_VALUE, descentSteps));
        return hasNextStep(completedSteps, descentSteps, segmentSteps, segmentDescent);
    }

    /**
     * Segment count is retained in the wire contract for compatibility, but
     * no longer terminates prospecting. Enabled plans always roll over from
     * the maid's live position.
     */
    public boolean canAdvanceSegment(long segmentIndex, long totalSteps, long totalDescent) {
        return enabled() && segmentIndex >= 0;
    }

    public StepMode nextStepMode(int segmentDescentSteps) {
        return nextStepMode(0, segmentDescentSteps);
    }

    /**
     * Compatibility overload. Total descent no longer caps the action;
     * AUTO repeats its descend-then-forward pattern for each segment.
     */
    public StepMode nextStepMode(int totalDescentSteps, int segmentDescentSteps) {
        return switch (mode) {
            case NEARBY -> throw new IllegalStateException("nearby mode has no prospecting step");
            case FORWARD_TUNNEL -> StepMode.FORWARD;
            case STAIRCASE_DOWN -> StepMode.DESCEND;
            case AUTO -> segmentDescentSteps >= maxDepth
                    ? StepMode.FORWARD : StepMode.DESCEND;
        };
    }

    public BlockPos nextDestination(BlockPos current, Direction direction,
                                    int segmentDescentSteps) {
        return nextDestination(current, direction, 0, segmentDescentSteps);
    }

    public BlockPos nextDestination(BlockPos current, Direction direction,
                                    int totalDescentSteps, int segmentDescentSteps) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(direction, "direction");
        if (!direction.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("prospecting direction must be horizontal");
        }
        BlockPos forward = current.relative(direction);
        return nextStepMode(totalDescentSteps, segmentDescentSteps) == StepMode.DESCEND
                ? forward.below() : forward;
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
