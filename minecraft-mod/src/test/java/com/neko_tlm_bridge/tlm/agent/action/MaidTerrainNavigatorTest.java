package com.neko_tlm_bridge.tlm.agent.action;

import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
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
    void localNavigationEdgeClassifierCoversEveryExecutorDiagnostic() {
        assertTrue(HarvestBlocksAction.isLocalNavigationEdgeFailure(
                "native_navigation_cannot_reach_terrain_step"));
        assertTrue(HarvestBlocksAction.isLocalNavigationEdgeFailure(
                "native_navigation_rejected_terrain_step"));
        assertTrue(HarvestBlocksAction.isLocalNavigationEdgeFailure(
                "native_navigation_finished_before_terrain_step"));
        assertTrue(HarvestBlocksAction.isLocalNavigationEdgeFailure(
                "controlled_descend_made_no_progress"));
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

    private static MaidTerrainStep step(
            MaidTerrainStep.Kind kind, BlockPos from, BlockPos to) {
        return new MaidTerrainStep(kind, from, to,
                List.of(to, to.above(), to.above(2)), List.of(), 1.0D);
    }
}
