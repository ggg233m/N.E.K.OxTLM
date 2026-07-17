package com.neko_tlm_bridge.tlm.agent.action;

import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainWorldEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidTerrainNavigatorTest {
    @Test
    void controlledDescendRequiresOneHorizontalAndOneVerticalBlock() {
        BlockPos from = new BlockPos(0, 10, 0);
        BlockPos to = new BlockPos(1, 9, 0);
        assertTrue(MaidTerrainNavigator.isControlledDescendGeometry(
                step(MaidTerrainStep.Kind.DESCEND, from, to)));

        assertFalse(MaidTerrainNavigator.isControlledDescendGeometry(
                step(MaidTerrainStep.Kind.DESCEND, from, new BlockPos(0, 9, 0))));
        assertFalse(MaidTerrainNavigator.isControlledDescendGeometry(
                step(MaidTerrainStep.Kind.DESCEND, from, new BlockPos(1, 8, 0))));
        assertFalse(MaidTerrainNavigator.isControlledDescendGeometry(
                step(MaidTerrainStep.Kind.DESCEND, from, new BlockPos(1, 9, 1))));
        assertFalse(MaidTerrainNavigator.isControlledDescendGeometry(
                step(MaidTerrainStep.Kind.TRAVERSE, from, to)));
    }

    @Test
    void controlledDescendOnlyRecoversTransientUpwardImpulseAtSource() {
        BlockPos from = new BlockPos(4, 20, 7);
        MaidTerrainStep descend = step(
                MaidTerrainStep.Kind.DESCEND, from, new BlockPos(5, 19, 7));

        assertTrue(MaidTerrainNavigator
                .isRecoverableControlledDescendUpwardDisplacement(
                        descend, from.above(), false, true));
        assertFalse(MaidTerrainNavigator
                .isRecoverableControlledDescendUpwardDisplacement(
                        descend, from.above(), false, false));
        assertFalse(MaidTerrainNavigator
                .isRecoverableControlledDescendUpwardDisplacement(
                        descend, from.above(), true, true));
        assertFalse(MaidTerrainNavigator
                .isRecoverableControlledDescendUpwardDisplacement(
                        descend, from.above(2), false, true));
        assertFalse(MaidTerrainNavigator
                .isRecoverableControlledDescendUpwardDisplacement(
                        descend, new BlockPos(5, 21, 7), false, true));
    }

    @Test
    void fallingBlockClassifierOnlyAdmitsGravityBlocks() {
        assertTrue(MaidTerrainNavigator.isFallingBlockState(
                Blocks.SAND.defaultBlockState()));
        assertTrue(MaidTerrainNavigator.isFallingBlockState(
                Blocks.GRAVEL.defaultBlockState()));
        assertTrue(MaidTerrainNavigator.isFallingBlockState(
                Blocks.WHITE_CONCRETE_POWDER.defaultBlockState()));
        assertFalse(MaidTerrainNavigator.isFallingBlockState(
                Blocks.STONE.defaultBlockState()));
        assertFalse(MaidTerrainNavigator.isFallingBlockState(
                Blocks.DIAMOND_ORE.defaultBlockState()));
    }

    @Test
    void dryBreakableObstacleDoesNotEnterWaterSealFlow() {
        assertFalse(MaidTerrainNavigator.requiresWaterSeal(
                MaidTerrainWorldEvaluator.ClearanceAssessment.BREAKABLE,
                false, false));
        assertTrue(MaidTerrainNavigator.requiresWaterSeal(
                MaidTerrainWorldEvaluator.ClearanceAssessment.WATER_HAZARD,
                false, false));
        assertTrue(MaidTerrainNavigator.requiresWaterSeal(
                MaidTerrainWorldEvaluator.ClearanceAssessment.WATER_HAZARD,
                true, true));
        assertFalse(MaidTerrainNavigator.requiresWaterSeal(
                MaidTerrainWorldEvaluator.ClearanceAssessment.UNSAFE,
                true, false));
    }

    @Test
    void localNavigationEdgeClassifierCoversEveryExecutorDiagnostic() {
        assertTrue(HarvestBlocksAction.isLocalNavigationEdgeFailure(
                "native_navigation_cannot_reach_terrain_step"));
        assertTrue(HarvestBlocksAction.isLocalNavigationEdgeFailure(
                "native_navigation_rejected_terrain_step"));
        assertTrue(HarvestBlocksAction.isLocalNavigationEdgeFailure(
                "native_navigation_finished_before_terrain_step"));
        assertTrue(HarvestBlocksAction.isLocalNavigationEdgeFailure(
                "controlled_descend_made_no_progress"));
        assertTrue(HarvestBlocksAction.isLocalNavigationEdgeFailure(
                "direct_waypoint_made_no_progress"));
        assertFalse(HarvestBlocksAction.isLocalNavigationEdgeFailure(
                "no_safe_prospecting_step_found"));
    }

    @Test
    void automaticProspectingVisitsEveryDirectionOnceBeforeExhaustion() {
        EnumSet<Direction> tried = EnumSet.of(Direction.SOUTH);
        Direction next =
                HarvestBlocksAction.nextUntriedHorizontalDirection(
                        Direction.SOUTH, tried);
        assertTrue(next == Direction.WEST);
        tried.add(next);

        next = HarvestBlocksAction.nextUntriedHorizontalDirection(next, tried);
        assertTrue(next == Direction.NORTH);
        tried.add(next);

        next = HarvestBlocksAction.nextUntriedHorizontalDirection(next, tried);
        assertTrue(next == Direction.EAST);
        tried.add(next);

        assertTrue(HarvestBlocksAction.nextUntriedHorizontalDirection(next, tried) == null);
    }

    @Test
    void onlyConsecutiveClearLevelTraversesMayShareNativeNavigation() {
        MaidTerrainStep first = step(MaidTerrainStep.Kind.TRAVERSE,
                new BlockPos(0, 10, 0), new BlockPos(1, 10, 0));
        MaidTerrainStep second = step(MaidTerrainStep.Kind.TRAVERSE,
                new BlockPos(1, 10, 0), new BlockPos(2, 10, 0));
        assertTrue(MaidTerrainNavigator.canChainFlatSteps(first, second));

        assertFalse(MaidTerrainNavigator.canChainFlatSteps(first,
                step(MaidTerrainStep.Kind.ASCEND,
                        new BlockPos(1, 10, 0), new BlockPos(2, 11, 0))));
        assertFalse(MaidTerrainNavigator.canChainFlatSteps(first,
                step(MaidTerrainStep.Kind.TRAVERSE,
                        new BlockPos(2, 10, 0), new BlockPos(3, 10, 0))));
        assertFalse(MaidTerrainNavigator.canChainFlatSteps(first,
                step(MaidTerrainStep.Kind.TRAVERSE,
                        new BlockPos(1, 10, 0), new BlockPos(1, 10, 1))));
        assertFalse(MaidTerrainNavigator.canChainFlatSteps(first,
                step(MaidTerrainStep.Kind.TRAVERSE,
                        new BlockPos(1, 10, 0), new BlockPos(3, 10, 0))));
        MaidTerrainStep obstructed = new MaidTerrainStep(
                MaidTerrainStep.Kind.TRAVERSE,
                new BlockPos(1, 10, 0), new BlockPos(2, 10, 0),
                List.of(new BlockPos(2, 10, 0), new BlockPos(2, 11, 0)),
                List.of(new BlockPos(2, 11, 0)), 2.0D);
        assertFalse(MaidTerrainNavigator.canChainFlatSteps(first, obstructed));
    }

    @Test
    void chainedNativePathMustStayInsideStraightMonotonicCorridor() {
        BlockPos from = new BlockPos(0, 10, 0);
        BlockPos target = new BlockPos(3, 10, 0);
        Path straight = new Path(List.of(
                new Node(0, 10, 0), new Node(1, 10, 0),
                new Node(2, 10, 0), new Node(3, 10, 0)), target, true);
        assertTrue(MaidTerrainNavigator.isStraightCorridorPath(
                straight, from, target));

        Path detour = new Path(List.of(
                new Node(0, 10, 0), new Node(1, 10, 1),
                new Node(3, 10, 0)), target, true);
        assertFalse(MaidTerrainNavigator.isStraightCorridorPath(
                detour, from, target));

        Path reversed = new Path(List.of(
                new Node(0, 10, 0), new Node(2, 10, 0),
                new Node(1, 10, 0), new Node(3, 10, 0)), target, true);
        assertFalse(MaidTerrainNavigator.isStraightCorridorPath(
                reversed, from, target));
    }

    @Test
    void directWaypointOnlyAcceptsAdjacentClearLevelTraverse() {
        assertTrue(MaidTerrainNavigator.isDirectFlatStepGeometry(
                step(MaidTerrainStep.Kind.TRAVERSE,
                        new BlockPos(0, 10, 0), new BlockPos(1, 10, 0))));
        assertFalse(MaidTerrainNavigator.isDirectFlatStepGeometry(
                step(MaidTerrainStep.Kind.ASCEND,
                        new BlockPos(0, 10, 0), new BlockPos(1, 11, 0))));
        assertFalse(MaidTerrainNavigator.isDirectFlatStepGeometry(
                step(MaidTerrainStep.Kind.TRAVERSE,
                        new BlockPos(0, 10, 0), new BlockPos(2, 10, 0))));
    }

    @Test
    void signedWaypointArrivalAcceptsCenterOvershootButRejectsLateralDrift() {
        MaidTerrainStep east = step(MaidTerrainStep.Kind.TRAVERSE,
                new BlockPos(0, 10, 0), new BlockPos(1, 10, 0));

        assertTrue(MaidTerrainNavigator.directWaypointReached(
                1.55D, 0.50D, east, 0.20D));
        assertTrue(MaidTerrainNavigator.directWaypointReached(
                1.75D, 0.50D, east, 0.20D));
        assertFalse(MaidTerrainNavigator.directWaypointReached(
                1.20D, 0.50D, east, 0.20D));
        assertFalse(MaidTerrainNavigator.directWaypointReached(
                1.55D, 0.80D, east, 0.20D));
    }

    @Test
    void constructionRequiresTheMaidToCenterInsideTheSourceCell() {
        BlockPos source = new BlockPos(-985, -7, 333);

        assertTrue(MaidTerrainNavigator.isCenteredAtOrigin(
                -984.50D, 333.50D, source, 0.10D));
        assertTrue(MaidTerrainNavigator.isCenteredAtOrigin(
                -984.57D, 333.44D, source, 0.10D));
        assertFalse(MaidTerrainNavigator.isCenteredAtOrigin(
                -984.80D, 333.30D, source, 0.10D));
        assertFalse(MaidTerrainNavigator.isCenteredAtOrigin(
                Double.NaN, 333.50D, source, 0.10D));
    }

    @Test
    void constructionCenteringOnlyRunsWhenTheMaidActuallyOverlapsTheTarget() {
        BlockPos support = new BlockPos(-985, -7, 334);
        AABB loggedMaidBounds = new AABB(
                -985.1007D, -7.0D, 333.0D,
                -984.5007D, -5.5D, 333.6D);
        AABB overlappingBounds = loggedMaidBounds.move(0.0D, 0.0D, 0.5D);

        assertFalse(MaidTerrainNavigator.intersectsConstructionTarget(
                loggedMaidBounds, support));
        assertTrue(MaidTerrainNavigator.intersectsConstructionTarget(
                overlappingBounds, support));
    }

    private static MaidTerrainStep step(
            MaidTerrainStep.Kind kind, BlockPos from, BlockPos to) {
        return new MaidTerrainStep(kind, from, to,
                List.of(to, to.above(), to.above(2)), List.of(), 1.0D);
    }
}
