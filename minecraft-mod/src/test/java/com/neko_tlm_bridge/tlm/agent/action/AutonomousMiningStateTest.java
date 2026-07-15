package com.neko_tlm_bridge.tlm.agent.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousMiningStateTest {
    @Test
    void tracksUnboundedExcavationSeparatelyFromGoalProgress() {
        AutonomousMiningState state = new AutonomousMiningState(2);
        state.transitionTo(AutonomousMiningState.Phase.SELECTING_SITE);
        state.transitionTo(AutonomousMiningState.Phase.EXCAVATING);
        state.recordExcavationStep(2);
        state.recordRouteClearance(1);

        assertEquals(1, state.segmentsDug());
        assertEquals(3, state.clearedBlocks());
        assertEquals(0, state.collectedCount());
        assertFalse(state.goalReached());
    }

    @Test
    void targetCountIsMinimumAndCompletionWaitsForVeinBoundary() {
        AutonomousMiningState state = new AutonomousMiningState(2);
        state.transitionTo(AutonomousMiningState.Phase.HARVESTING);
        state.recordHarvest();
        assertEquals(AutonomousMiningState.Phase.HARVESTING, state.phase());

        state.recordHarvest();

        assertTrue(state.goalReached());
        assertEquals(AutonomousMiningState.Phase.HARVESTING, state.phase());
        state.recordHarvest();
        assertEquals(3, state.collectedCount());
        state.complete();
        assertEquals(AutonomousMiningState.Phase.COMPLETED, state.phase());
        assertEquals("none", state.blockedReason());
        assertFalse(state.decisionRequired());
        assertThrows(IllegalStateException.class, state::recordHarvest);
    }

    @Test
    void blockedStateRequiresDecisionAndIsTerminal() {
        AutonomousMiningState state = new AutonomousMiningState(1);
        state.block("Lava hazard");

        assertEquals(AutonomousMiningState.Phase.BLOCKED, state.phase());
        assertEquals("lava_hazard", state.blockedReason());
        assertTrue(state.decisionRequired());
        assertThrows(IllegalStateException.class,
                () -> state.transitionTo(AutonomousMiningState.Phase.SCANNING));
    }

    @Test
    void restoresDurableCountersWithoutInventingProgress() {
        AutonomousMiningState state = AutonomousMiningState.restore(
                10, 3, 42L, 117L);

        assertEquals(3, state.collectedCount());
        assertEquals(42L, state.segmentsDug());
        assertEquals(117L, state.clearedBlocks());
        assertEquals(AutonomousMiningState.Phase.VALIDATING, state.phase());
        assertFalse(state.goalReached());
    }

    @Test
    void restoreAllowsVeinOverrunAndRequiresFreshBoundaryScan() {
        AutonomousMiningState state = AutonomousMiningState.restore(
                1, 2, 4L, 9L);

        assertTrue(state.goalReached());
        assertEquals(2, state.collectedCount());
        assertEquals(AutonomousMiningState.Phase.VALIDATING, state.phase());
    }

    @Test
    void targetOneStillAcceptsSecondConnectedVeinBlock() {
        AutonomousMiningState state = new AutonomousMiningState(1);
        state.transitionTo(AutonomousMiningState.Phase.HARVESTING);

        state.recordHarvest();
        assertTrue(state.goalReached());
        assertEquals(AutonomousMiningState.Phase.HARVESTING, state.phase());
        state.recordHarvest();
        state.complete();

        assertEquals(2, state.collectedCount());
        assertEquals(AutonomousMiningState.Phase.COMPLETED, state.phase());
    }

    @Test
    void rejectsInvalidCountersAndPrematureCompletion() {
        assertThrows(IllegalArgumentException.class,
                () -> new AutonomousMiningState(0));
        AutonomousMiningState state = new AutonomousMiningState(1);
        assertThrows(IllegalArgumentException.class,
                () -> state.recordRouteClearance(-1));
        assertThrows(IllegalStateException.class,
                () -> state.transitionTo(AutonomousMiningState.Phase.COMPLETED));
    }
}
