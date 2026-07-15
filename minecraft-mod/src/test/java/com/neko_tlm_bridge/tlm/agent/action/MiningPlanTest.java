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
