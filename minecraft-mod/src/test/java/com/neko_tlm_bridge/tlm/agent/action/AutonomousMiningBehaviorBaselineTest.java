package com.neko_tlm_bridge.tlm.agent.action;

import com.neko_tlm_bridge.tlm.agent.action.AutonomousMiningAction.ScanDecision;
import com.neko_tlm_bridge.tlm.agent.action.AutonomousMiningAction.ExhaustedCommitmentDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression contract for the scan decision reducer used directly by
 * AutonomousMiningAction.scanAndPlanHarvest. Facts are captured after
 * committed-vein pruning: an exhausted incomplete commitment has already
 * been unlocked before it reaches this reducer.
 */
class AutonomousMiningBehaviorBaselineTest {
    @Test
    void unlockedGoalCompletesBeforeCapacityOrNewTargets() {
        assertDecision(ScanDecision.COMPLETE,
                false, true, true, false);
        assertDecision(ScanDecision.COMPLETE,
                false, true, false, false);
    }

    @Test
    void unlockedEmptyScanContinuesWhenCapacityExists() {
        assertDecision(ScanDecision.CONTINUE,
                false, false, false, true);
    }

    @Test
    void unlockedScanHarvestsAvailableTarget() {
        assertDecision(ScanDecision.HARVEST,
                false, false, true, true);
    }

    @Test
    void unlockedWorkBlocksWhenCapacityIsUnavailable() {
        assertDecision(ScanDecision.BLOCK_CAPACITY,
                false, false, false, false);
        assertDecision(ScanDecision.BLOCK_CAPACITY,
                false, false, true, false);
    }

    @Test
    void committedVeinTargetOverridesGoalAndCapacityGate() {
        assertDecision(ScanDecision.HARVEST,
                true, true, true, false);
        assertDecision(ScanDecision.HARVEST,
                true, false, true, false);
    }

    @Test
    void exhaustedCommittedVeinCompletesOnceGoalIsReached() {
        assertEquals(ExhaustedCommitmentDecision.COMPLETE_CURRENT,
                AutonomousMiningAction.reduceExhaustedCommitment(true));
        assertDecision(ScanDecision.COMPLETE,
                true, true, false, false);
    }

    @Test
    void exhaustedInsufficientVeinReleasesAndHarvestsNextTargetSameTick() {
        assertEquals(ExhaustedCommitmentDecision.RELEASE_FOR_NEXT,
                AutonomousMiningAction.reduceExhaustedCommitment(false));
        // RELEASE_FOR_NEXT makes the post-commit facts unlocked; the targets
        // already found by the same scan are therefore eligible immediately.
        assertDecision(ScanDecision.HARVEST,
                false, false, true, true);
    }

    @Test
    void exhaustedIncompleteCommitmentMustBeUnlockedBeforeReduction() {
        assertThrows(IllegalArgumentException.class,
                () -> new AutonomousMiningAction.ScanDecisionFacts(
                        true, false, false, false));
    }

    private static void assertDecision(
            ScanDecision expected,
            boolean veinLocked,
            boolean goalReached,
            boolean targetsAvailable,
            boolean capacityAvailable) {
        AutonomousMiningAction.ScanDecisionFacts facts =
                new AutonomousMiningAction.ScanDecisionFacts(
                        veinLocked, goalReached,
                        targetsAvailable, capacityAvailable);
        assertEquals(expected,
                AutonomousMiningAction.reduceScanDecision(facts));
    }
}
