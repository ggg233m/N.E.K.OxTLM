package com.neko_tlm_bridge.tlm.agent.world;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningReturnRoutePlannerTest {
    @Test
    void reversesBreadcrumbsFromNearestAttachmentToEntry() {
        BlockPos entry = new BlockPos(0, 40, 0);
        BlockPos one = new BlockPos(0, 39, -1);
        BlockPos two = new BlockPos(0, 38, -2);
        BlockPos workface = new BlockPos(1, 38, -2);

        MiningReturnRoutePlanner.ReturnRoute plan =
                MiningReturnRoutePlanner.planToEntry(
                        List.of(entry, one, two, workface),
                        workface, 2.0D).orElseThrow();

        assertEquals(workface, plan.attachment());
        assertEquals(entry, plan.entry());
        assertEquals(3, plan.recordedStepsToEntry());
        assertEquals(List.of(workface, two, one, entry), plan.waypoints());
    }

    @Test
    void canAttachPartwayAlongOldRoute() {
        BlockPos entry = new BlockPos(0, 32, 0);
        BlockPos one = new BlockPos(1, 32, 0);
        BlockPos two = new BlockPos(2, 32, 0);
        BlockPos three = new BlockPos(3, 32, 0);

        MiningReturnRoutePlanner.ReturnRoute plan =
                MiningReturnRoutePlanner.planToEntry(
                        List.of(entry, one, two, three),
                        two.above(), 1.1D).orElseThrow();

        assertEquals(two, plan.attachment());
        assertEquals(List.of(two, one, entry), plan.waypoints());
        assertEquals(1.0D, plan.attachmentDistance());
    }

    @Test
    void refusesDistantOrStructurallyInvalidRoutes() {
        BlockPos entry = new BlockPos(0, 32, 0);
        assertTrue(MiningReturnRoutePlanner.planToEntry(
                List.of(entry, entry.north()),
                new BlockPos(20, 32, 20), 4.0D).isEmpty());
        assertTrue(MiningReturnRoutePlanner.planToEntry(
                List.of(entry, entry.below()), entry, 4.0D).isEmpty());
        assertTrue(MiningReturnRoutePlanner.planToEntry(
                List.of(entry, entry.offset(2, 0, 0)), entry, 4.0D).isEmpty());
    }

    @Test
    void rejectsInvalidAttachmentDistance() {
        assertThrows(IllegalArgumentException.class,
                () -> MiningReturnRoutePlanner.planToEntry(
                        List.of(BlockPos.ZERO), BlockPos.ZERO, -1.0D));
        assertThrows(IllegalArgumentException.class,
                () -> MiningReturnRoutePlanner.planToEntry(
                        List.of(BlockPos.ZERO), BlockPos.ZERO,
                        Double.POSITIVE_INFINITY));
    }
}
