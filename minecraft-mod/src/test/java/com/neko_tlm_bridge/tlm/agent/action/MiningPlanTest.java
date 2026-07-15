package com.neko_tlm_bridge.tlm.agent.action;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningPlanTest {
    @Test
    void omittedPlanPreservesNearbyOnlyBehavior() {
        MiningPlan plan = MiningPlan.fromArgs(new JsonObject(), true);

        assertEquals(MiningPlan.Mode.NEARBY, plan.mode());
        assertFalse(plan.enabled());
        assertFalse(plan.hasNextStep(0, 0));
    }

    @Test
    void oreDefaultUsesBoundedAutomaticProspecting() {
        MiningPlan plan = MiningPlan.fromArgs(new JsonObject(), true, true);

        assertEquals(MiningPlan.Mode.AUTO, plan.mode());
        assertTrue(plan.enabled());
        assertEquals(8, plan.maxDistance());
        assertEquals(4, plan.maxDepth());
        assertEquals(64, plan.excavationBudget());
        assertEquals(4, plan.maxSegments());
        assertEquals(Integer.MAX_VALUE, plan.totalStepLimit());
        assertEquals(Integer.MAX_VALUE, plan.totalDescentLimit());
    }

    @Test
    void explicitNearbyOverridesAutomaticOreDefault() {
        JsonObject value = new JsonObject();
        value.addProperty("mode", "nearby");
        JsonObject args = new JsonObject();
        args.add("mining_plan", value);

        MiningPlan plan = MiningPlan.fromArgs(args, true, true);

        assertEquals(MiningPlan.Mode.NEARBY, plan.mode());
        assertFalse(plan.enabled());
    }

    @Test
    void autoDescendsThenContinuesForwardOneCellAtATime() {
        JsonObject args = argsWithPlan("auto", "east", 6, 2, 12);
        MiningPlan plan = MiningPlan.fromArgs(args, true);
        BlockPos start = new BlockPos(10, 40, 10);

        assertEquals(new BlockPos(11, 39, 10),
                plan.nextDestination(start, plan.resolveDirection(Direction.NORTH), 0));
        assertEquals(new BlockPos(11, 40, 10),
                plan.nextDestination(start, plan.resolveDirection(Direction.NORTH), 2));
        assertTrue(plan.hasNextStep(5, 2));
        assertFalse(plan.hasNextStep(6, 2));
    }

    @Test
    void staircaseNeverTurnsIntoStraightDownOrForwardAfterDepthBudget() {
        JsonObject args = argsWithPlan("staircase_down", "north", 8, 3, 16);
        MiningPlan plan = MiningPlan.fromArgs(args, true);
        BlockPos current = new BlockPos(0, 20, 0);

        assertEquals(new BlockPos(0, 19, -1),
                plan.nextDestination(current, Direction.NORTH, 0));
        assertFalse(plan.hasNextStep(3, 3));
    }

    @Test
    void segmentShapeRollsOverWithoutTerminatingTheAction() {
        JsonObject args = argsWithPlan("auto", "east", 8, 4, 64);
        args.getAsJsonObject("mining_plan").addProperty("max_segments", 2);
        MiningPlan plan = MiningPlan.fromArgs(args, true);

        assertEquals(2, plan.maxSegments());
        assertEquals(Integer.MAX_VALUE, plan.totalStepLimit());
        assertEquals(Integer.MAX_VALUE, plan.totalDescentLimit());
        assertFalse(plan.hasNextStep(8, 4, 8, 4));
        assertTrue(plan.canAdvanceSegment(0, 8, 4));
        assertTrue(plan.hasNextStep(8, 4, 0, 0));
        assertTrue(plan.canAdvanceSegment(1, 16, 8));
        assertTrue(plan.canAdvanceSegment(100, 808, 404));
    }

    @Test
    void autoRepeatsItsDescendThenForwardShapeForEverySegment() {
        JsonObject args = argsWithPlan("auto", "east", 16, 12, 128);
        args.getAsJsonObject("mining_plan").addProperty("max_segments", 4);
        MiningPlan plan = MiningPlan.fromArgs(args, true);

        assertEquals(Integer.MAX_VALUE, plan.totalDescentLimit());
        assertEquals(MiningPlan.StepMode.DESCEND,
                plan.nextStepMode(32, 0));
        assertTrue(plan.hasNextStep(40, 32, 0, 0));
        assertTrue(plan.canAdvanceSegment(2, 40, 32));
        assertEquals(new BlockPos(1, -56, 0), plan.nextDestination(
                new BlockPos(0, -55, 0), Direction.EAST, 32, 0));
    }

    @Test
    void explicitPlanDefaultsToOneSegmentAndMultiSegmentGetsSafeBudget() {
        JsonObject singleArgs = new JsonObject();
        JsonObject single = new JsonObject();
        single.addProperty("mode", "auto");
        singleArgs.add("mining_plan", single);
        MiningPlan singlePlan = MiningPlan.fromArgs(singleArgs, true);

        assertEquals(1, singlePlan.maxSegments());
        assertEquals(24, singlePlan.excavationBudget());

        JsonObject multipleArgs = new JsonObject();
        JsonObject multiple = new JsonObject();
        multiple.addProperty("mode", "auto");
        multiple.addProperty("max_segments", 3);
        multipleArgs.add("mining_plan", multiple);
        MiningPlan multiplePlan = MiningPlan.fromArgs(multipleArgs, true);

        assertEquals(3, multiplePlan.maxSegments());
        assertEquals(64, multiplePlan.excavationBudget());
    }

    @Test
    void rejectsInvalidSegmentAndExpandedExcavationBounds() {
        JsonObject noSegments = argsWithPlan("auto", "east", 8, 4, 64);
        noSegments.getAsJsonObject("mining_plan").addProperty("max_segments", 0);
        assertThrows(IllegalArgumentException.class,
                () -> MiningPlan.fromArgs(noSegments, true));

        JsonObject tooManySegments = argsWithPlan("auto", "east", 8, 4, 64);
        tooManySegments.getAsJsonObject("mining_plan").addProperty("max_segments", 5);
        assertThrows(IllegalArgumentException.class,
                () -> MiningPlan.fromArgs(tooManySegments, true));

        JsonObject maximumBudget = argsWithPlan("auto", "east", 8, 4, 256);
        assertEquals(256, MiningPlan.fromArgs(maximumBudget, true).excavationBudget());

        JsonObject excessiveBudget = argsWithPlan("auto", "east", 8, 4, 257);
        assertThrows(IllegalArgumentException.class,
                () -> MiningPlan.fromArgs(excessiveBudget, true));
    }

    @Test
    void explorationRequiresSelectorAndForwardRejectsDepth() {
        JsonObject auto = argsWithPlan("auto", "maid_facing", 4, 2, 8);
        assertThrows(IllegalArgumentException.class,
                () -> MiningPlan.fromArgs(auto, false));

        JsonObject forward = argsWithPlan("forward_tunnel", "west", 4, 1, 8);
        assertThrows(IllegalArgumentException.class,
                () -> MiningPlan.fromArgs(forward, true));

        JsonObject shortStaircase = argsWithPlan("staircase_down", "north", 2, 3, 8);
        assertThrows(IllegalArgumentException.class,
                () -> MiningPlan.fromArgs(shortStaircase, true));

        JsonObject autoWithoutForwardBudget = argsWithPlan("auto", "east", 4, 4, 12);
        assertThrows(IllegalArgumentException.class,
                () -> MiningPlan.fromArgs(autoWithoutForwardBudget, true));
    }

    private static JsonObject argsWithPlan(String mode, String direction,
                                           int distance, int depth, int budget) {
        JsonObject plan = new JsonObject();
        plan.addProperty("mode", mode);
        plan.addProperty("direction", direction);
        plan.addProperty("max_distance", distance);
        plan.addProperty("max_depth", depth);
        plan.addProperty("excavation_budget", budget);
        JsonObject args = new JsonObject();
        args.add("mining_plan", plan);
        return args;
    }
}
