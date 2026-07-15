package com.neko_tlm_bridge.tlm.agent.action;

import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

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

    private static MaidTerrainStep step(
            MaidTerrainStep.Kind kind, BlockPos from, BlockPos to) {
        return new MaidTerrainStep(kind, from, to,
                List.of(to, to.above(), to.above(2)), List.of(), 1.0D);
    }
}
