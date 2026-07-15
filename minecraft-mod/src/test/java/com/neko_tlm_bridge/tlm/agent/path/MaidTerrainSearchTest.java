package com.neko_tlm_bridge.tlm.agent.path;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MaidTerrainSearchTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void findsShortestStraightPath() {
        FakeEvaluator world = flatWorld(0, 3, 0, 0);
        BlockPos start = pos(0, 1, 0);
        BlockPos goal = pos(3, 1, 0);

        MaidTerrainPath path = complete(new MaidTerrainSearch(start, Set.of(goal), world, 32));

        assertEquals(goal, path.target());
        assertEquals(List.of(pos(1, 1, 0), pos(2, 1, 0), goal), destinations(path));
        assertEquals(List.of(
                MaidTerrainStep.Kind.TRAVERSE,
                MaidTerrainStep.Kind.TRAVERSE,
                MaidTerrainStep.Kind.TRAVERSE
        ), kinds(path));
        assertEquals(3.0, path.totalCost(), EPSILON);
        assertTrue(path.steps().stream().allMatch(step -> step.toBreak().isEmpty()));
    }

    @Test
    void wallClearCostChangesChosenRoute() {
        BlockPos start = pos(0, 1, 0);
        BlockPos goal = pos(4, 1, 0);
        BlockPos wall = pos(2, 1, 0);

        FakeEvaluator cheapWorld = flatWorld(0, 4, -1, 1).clearCost(wall, 0.25);
        MaidTerrainPath cheapPath = complete(new MaidTerrainSearch(start, Set.of(goal), cheapWorld, 128));
        assertEquals(List.of(
                pos(1, 1, 0), pos(2, 1, 0), pos(3, 1, 0), goal
        ), destinations(cheapPath));
        assertEquals(List.of(wall), cheapPath.steps().get(1).toBreak());
        assertEquals(4.25, cheapPath.totalCost(), EPSILON);

        FakeEvaluator expensiveWorld = flatWorld(0, 4, -1, 1).clearCost(wall, 10.0);
        MaidTerrainPath expensivePath = complete(new MaidTerrainSearch(start, Set.of(goal), expensiveWorld, 128));
        assertFalse(expensivePath.steps().stream().flatMap(step -> step.toBreak().stream()).anyMatch(wall::equals));
        assertTrue(expensivePath.steps().stream().anyMatch(step -> step.to().getZ() != 0));
        assertEquals(6.0, expensivePath.totalCost(), EPSILON);
    }

    @Test
    void reservesAndClearsBothFeetAndHeadCellsThroughWall() {
        BlockPos start = pos(0, 1, 0);
        BlockPos feet = pos(1, 1, 0);
        BlockPos head = feet.above();
        BlockPos goal = pos(2, 1, 0);
        FakeEvaluator world = flatWorld(0, 2, 0, 0)
                .clearCost(feet, 2.0)
                .clearCost(head, 3.0);

        MaidTerrainPath path = complete(new MaidTerrainSearch(start, Set.of(goal), world, 32));
        MaidTerrainStep wallStep = path.steps().getFirst();

        assertEquals(MaidTerrainStep.Kind.TRAVERSE, wallStep.kind());
        assertEquals(List.of(feet, head), wallStep.clearance());
        assertEquals(List.of(feet, head), wallStep.toBreak());
        assertTrue(wallStep.clearance().contains(wallStep.to()));
        assertTrue(wallStep.clearance().contains(wallStep.to().above()));
    }

    @Test
    void rejectsPassageWhenHeadCellCannotBeCleared() {
        BlockPos start = pos(0, 1, 0);
        BlockPos blockedHead = pos(1, 2, 0);
        BlockPos goal = pos(2, 1, 0);
        FakeEvaluator world = flatWorld(0, 2, 0, 0)
                .clearCost(blockedHead, Double.POSITIVE_INFINITY);

        MaidTerrainSearch search = new MaidTerrainSearch(start, Set.of(goal), world, 32);

        assertEquals(MaidTerrainSearch.Status.FAILED, finish(search));
        assertTrue(search.result().isEmpty());
    }

    @Test
    void usesExplicitAscendAndDescendSteps() {
        FakeEvaluator world = new FakeEvaluator(0, 4, 0, 4, 0, 0)
                .support(pos(0, 0, 0))
                .support(pos(1, 1, 0))
                .support(pos(2, 1, 0))
                .support(pos(3, 0, 0))
                .support(pos(4, 0, 0));
        BlockPos start = pos(0, 1, 0);
        BlockPos goal = pos(4, 1, 0);

        MaidTerrainPath path = complete(new MaidTerrainSearch(start, Set.of(goal), world, 128));

        assertEquals(List.of(
                MaidTerrainStep.Kind.ASCEND,
                MaidTerrainStep.Kind.TRAVERSE,
                MaidTerrainStep.Kind.DESCEND,
                MaidTerrainStep.Kind.TRAVERSE
        ), kinds(path));
        assertEquals(List.of(
                pos(1, 2, 0), pos(2, 2, 0), pos(3, 1, 0), goal
        ), destinations(path));
        MaidTerrainStep descend = path.steps().get(2);
        assertEquals(List.of(descend.to(), descend.to().above(), descend.to().above(2)),
                descend.clearance());
    }

    @Test
    void digsStraightDownWhenGoalIsBelow() {
        FakeEvaluator world = new FakeEvaluator(0, 0, 0, 3, 0, 0)
                .support(pos(0, 0, 0))
                .clearCost(pos(0, 1, 0), 2.0);
        BlockPos start = pos(0, 2, 0);
        BlockPos goal = pos(0, 1, 0);

        MaidTerrainPath path = complete(new MaidTerrainSearch(start, Set.of(goal), world, 16));

        assertEquals(1, path.steps().size());
        MaidTerrainStep step = path.steps().getFirst();
        assertEquals(MaidTerrainStep.Kind.DIG_DOWN, step.kind());
        assertEquals(start, step.from());
        assertEquals(goal, step.to());
        assertEquals(List.of(goal), step.toBreak());
        assertEquals(3.35, step.cost(), EPSILON);
    }

    @Test
    void failsWhenRequiredTargetCellCannotBeCleared() {
        FakeEvaluator world = flatWorld(0, 2, 0, 0)
                .clearCost(pos(2, 1, 0), Double.POSITIVE_INFINITY);
        MaidTerrainSearch search = new MaidTerrainSearch(pos(0, 1, 0), Set.of(pos(2, 1, 0)), world, 32);

        assertEquals(MaidTerrainSearch.Status.FAILED, finish(search));
        assertTrue(search.result().isEmpty());
    }

    @Test
    void advanceHonorsPerCallNodeBudgetIncrementally() {
        FakeEvaluator world = flatWorld(0, 3, 0, 0);
        MaidTerrainSearch search = new MaidTerrainSearch(
                pos(0, 1, 0), Set.of(pos(3, 1, 0)), world, 16
        );

        for (int expectedExpanded = 1; expectedExpanded <= 3; expectedExpanded++) {
            int before = search.expandedNodes();
            assertEquals(MaidTerrainSearch.Status.SEARCHING, search.advance(1));
            assertEquals(expectedExpanded, search.expandedNodes());
            assertEquals(1, search.expandedNodes() - before);
        }

        assertEquals(MaidTerrainSearch.Status.FOUND, search.advance(1));
        assertEquals(3, search.expandedNodes(), "popping a goal must not consume expansion budget");
        assertTrue(search.result().isPresent());
    }

    @Test
    void equalCostTieBreakIsDeterministic() {
        BlockPos start = pos(0, 1, 0);
        BlockPos goal = pos(2, 1, 0);
        BlockPos blocked = pos(1, 1, 0);
        List<BlockPos> expected = null;

        for (int run = 0; run < 20; run++) {
            FakeEvaluator world = flatWorld(0, 2, -1, 1)
                    .clearCost(blocked, Double.POSITIVE_INFINITY);
            MaidTerrainPath path = complete(new MaidTerrainSearch(start, new HashSet<>(Set.of(goal)), world, 128));
            List<BlockPos> actual = destinations(path);
            if (expected == null) {
                expected = actual;
            } else {
                assertEquals(expected, actual);
            }
        }

        assertEquals(pos(0, 1, -1), expected.getFirst());
        assertNotEquals(0, expected.getFirst().getZ());
    }

    @Test
    void rejectsUnloadedNodesWithoutInspectingTheirBlocks() {
        BlockPos unloaded = pos(1, 1, 0);
        FakeEvaluator world = flatWorld(0, 2, 0, 0).unload(unloaded);
        MaidTerrainSearch search = new MaidTerrainSearch(
                pos(0, 1, 0), Set.of(pos(2, 1, 0)), world, 32
        );

        assertEquals(MaidTerrainSearch.Status.FAILED, finish(search));
        assertTrue(search.result().isEmpty());
        assertFalse(world.clearCostQueries.contains(unloaded.asLong()),
                "the search must reject an unloaded node before reading its block cost");
    }

    private static FakeEvaluator flatWorld(int minX, int maxX, int minZ, int maxZ) {
        FakeEvaluator evaluator = new FakeEvaluator(minX, maxX, 0, 2, minZ, maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                evaluator.support(pos(x, 0, z));
            }
        }
        return evaluator;
    }

    private static MaidTerrainPath complete(MaidTerrainSearch search) {
        assertEquals(MaidTerrainSearch.Status.FOUND, finish(search));
        return search.result().orElseThrow();
    }

    private static MaidTerrainSearch.Status finish(MaidTerrainSearch search) {
        for (int iteration = 0; iteration < 1_000; iteration++) {
            MaidTerrainSearch.Status status = search.advance(16);
            if (status != MaidTerrainSearch.Status.SEARCHING) {
                return status;
            }
        }
        fail("terrain search did not terminate");
        throw new AssertionError("unreachable");
    }

    private static List<BlockPos> destinations(MaidTerrainPath path) {
        return path.steps().stream().map(MaidTerrainStep::to).toList();
    }

    private static List<MaidTerrainStep.Kind> kinds(MaidTerrainPath path) {
        return path.steps().stream().map(MaidTerrainStep::kind).toList();
    }

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static final class FakeEvaluator implements MaidTerrainNodeEvaluator {
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;
        private final Map<Long, Double> clearCosts = new HashMap<>();
        private final Set<Long> supports = new HashSet<>();
        private final Set<Long> unloaded = new HashSet<>();
        private final Set<Long> clearCostQueries = new HashSet<>();

        private FakeEvaluator(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        private FakeEvaluator support(BlockPos pos) {
            supports.add(pos.asLong());
            return this;
        }

        private FakeEvaluator clearCost(BlockPos pos, double cost) {
            clearCosts.put(pos.asLong(), cost);
            return this;
        }

        private FakeEvaluator unload(BlockPos pos) {
            unloaded.add(pos.asLong());
            return this;
        }

        @Override
        public boolean withinBounds(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        @Override
        public boolean isLoaded(BlockPos pos) {
            return withinBounds(pos) && !unloaded.contains(pos.asLong());
        }

        @Override
        public double clearCost(BlockPos pos) {
            if (!isLoaded(pos)) {
                throw new AssertionError("clearCost queried for unloaded or out-of-bounds node " + pos);
            }
            clearCostQueries.add(pos.asLong());
            return clearCosts.getOrDefault(pos.asLong(), 0.0);
        }

        @Override
        public boolean canStandOn(BlockPos pos) {
            return isLoaded(pos) && supports.contains(pos.asLong());
        }
    }
}
