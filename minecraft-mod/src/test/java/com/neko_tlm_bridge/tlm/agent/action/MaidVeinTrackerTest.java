package com.neko_tlm_bridge.tlm.agent.action;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidVeinTrackerTest {
    private static final Comparator<BlockPos> ORDER = Comparator
            .comparingInt((BlockPos pos) -> pos.getX())
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ);

    @Test
    void locksToOneComponentAndIgnoresNearbyDisconnectedOre() {
        BlockPos first = new BlockPos(1, 10, 0);
        BlockPos diagonal = new BlockPos(2, 11, 1);
        BlockPos otherVein = new BlockPos(7, 10, 0);
        MaidVeinTracker tracker = new MaidVeinTracker();
        tracker.rememberHarvested(first);

        List<BlockPos> retained = tracker.retainConnected(
                List.of(otherVein, diagonal, first), ORDER);

        assertEquals(List.of(first, diagonal), retained);
        assertTrue(tracker.contains(first));
        assertTrue(tracker.contains(diagonal));
        assertFalse(tracker.contains(otherVein));
    }

    @Test
    void minedAirPositionBridgesAComponentAcrossLaterRescans() {
        BlockPos mined = new BlockPos(0, 10, 0);
        BlockPos next = new BlockPos(1, 10, 0);
        BlockPos extension = new BlockPos(2, 10, 0);
        BlockPos unrelated = new BlockPos(8, 10, 0);
        MaidVeinTracker tracker = new MaidVeinTracker();
        tracker.rememberHarvested(mined);

        assertEquals(List.of(next), tracker.retainConnected(List.of(next, unrelated), ORDER));
        assertEquals(List.of(extension), tracker.retainConnected(List.of(extension, unrelated), ORDER));
        assertEquals(3, tracker.knownMembers());
    }

    @Test
    void hardLimitMarksOversizedVeinAsTruncated() {
        MaidVeinTracker tracker = new MaidVeinTracker(3);
        BlockPos seed = new BlockPos(0, 10, 0);
        tracker.rememberHarvested(seed);

        List<BlockPos> retained = tracker.retainConnected(List.of(
                new BlockPos(1, 10, 0),
                new BlockPos(2, 10, 0),
                new BlockPos(3, 10, 0)), ORDER);

        assertEquals(2, retained.size());
        assertEquals(3, tracker.knownMembers());
        assertTrue(tracker.truncated());
    }

    @Test
    void duplicateHarvestIsRejectedAndExternalGapDoesNotBridge() {
        MaidVeinTracker tracker = new MaidVeinTracker();
        BlockPos seed = new BlockPos(0, 10, 0);
        BlockPos bridge = new BlockPos(1, 10, 0);
        BlockPos beyond = new BlockPos(2, 10, 0);

        assertTrue(tracker.rememberHarvested(seed));
        assertFalse(tracker.rememberHarvested(seed));
        assertEquals(List.of(bridge, beyond),
                tracker.retainConnected(List.of(bridge, beyond), ORDER));

        tracker.pruneUnharvested(pos -> !pos.equals(bridge));
        assertEquals(List.of(), tracker.retainConnected(List.of(beyond), ORDER));
        assertFalse(tracker.contains(beyond));
    }

    @Test
    void disconnectedMultiBlockTailCannotAnchorItself() {
        MaidVeinTracker tracker = new MaidVeinTracker();
        BlockPos seed = new BlockPos(0, 10, 0);
        BlockPos bridge = new BlockPos(1, 10, 0);
        BlockPos tailStart = new BlockPos(2, 10, 0);
        BlockPos tailEnd = new BlockPos(3, 10, 0);

        tracker.rememberHarvested(seed);
        assertEquals(List.of(bridge, tailStart, tailEnd),
                tracker.retainConnected(
                        List.of(bridge, tailStart, tailEnd), ORDER));

        tracker.pruneUnharvested(pos -> !pos.equals(bridge));
        assertEquals(List.of(), tracker.retainConnected(
                List.of(tailStart, tailEnd), ORDER));
        assertFalse(tracker.contains(tailStart));
        assertFalse(tracker.contains(tailEnd));
    }

    @Test
    void unboundedTrackerDoesNotAbandonVeinsPastLegacyLimit() {
        MaidVeinTracker tracker = MaidVeinTracker.unbounded();
        BlockPos seed = new BlockPos(0, 10, 0);
        tracker.rememberHarvested(seed);
        List<BlockPos> connected = new ArrayList<>();
        for (int x = 1; x <= 600; x++) {
            connected.add(new BlockPos(x, 10, 0));
        }

        assertEquals(connected, tracker.retainConnected(connected, ORDER));
        assertEquals(601, tracker.knownMembers());
        assertFalse(tracker.truncated());
    }

    @Test
    void durableRestoreKeepsMinedAirAsConnectivityBridge() {
        BlockPos mined = new BlockPos(0, 10, 0);
        BlockPos pending = new BlockPos(1, 10, 0);
        MaidVeinTracker restored = MaidVeinTracker.restore(
                List.of(mined, pending), List.of(mined));

        assertTrue(restored.locked());
        assertEquals(Set.of(pending), restored.pendingMembers());
        assertEquals(List.of(pending, new BlockPos(2, 10, 0)),
                restored.retainConnected(
                        List.of(pending, new BlockPos(2, 10, 0)), ORDER));
    }
}
