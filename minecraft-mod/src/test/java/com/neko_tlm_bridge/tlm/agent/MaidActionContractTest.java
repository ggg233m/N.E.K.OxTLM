package com.neko_tlm_bridge.tlm.agent;

import com.neko_tlm_bridge.ws.Protocol;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class MaidActionContractTest {
    @Test
    void terminalStatusesMatchTheWireContract() {
        EnumSet<ActionStatus> terminal = EnumSet.noneOf(ActionStatus.class);
        for (ActionStatus status : ActionStatus.values()) {
            if (status.isTerminal()) terminal.add(status);
        }
        assertEquals(EnumSet.of(ActionStatus.SUCCEEDED, ActionStatus.FAILED,
                ActionStatus.CANCELLED, ActionStatus.SUPERSEDED, ActionStatus.TIMEOUT), terminal);
    }

    @Test
    void stateMachineAllowsOnlyTheFrozenLifecycleEdges() {
        assertTrue(ActionStatus.PENDING.canTransitionTo(ActionStatus.RUNNING));
        assertTrue(ActionStatus.RUNNING.canTransitionTo(ActionStatus.CANCEL_REQUESTED));
        assertTrue(ActionStatus.RUNNING.canTransitionTo(ActionStatus.TERMINATING));
        assertTrue(ActionStatus.CANCEL_REQUESTED.canTransitionTo(ActionStatus.TERMINATING));
        assertTrue(ActionStatus.TERMINATING.canTransitionTo(ActionStatus.SUPERSEDED));
        assertFalse(ActionStatus.RUNNING.canTransitionTo(ActionStatus.SUCCEEDED));
        assertFalse(ActionStatus.SUCCEEDED.canTransitionTo(ActionStatus.RUNNING));
    }

    @Test
    void publicKindsRoundTripAndLegacyKindStaysInternal() {
        assertEquals(MaidActionKind.NAVIGATE, MaidActionKind.fromWireName("navigate"));
        assertEquals(MaidActionKind.HARVEST_BLOCKS, MaidActionKind.fromWireName("HARVEST_BLOCKS"));
        assertEquals(MaidActionKind.EXCAVATE_SEGMENT,
                MaidActionKind.fromWireName("excavate_segment"));
        assertEquals(MaidActionKind.AUTONOMOUS_MINING,
                MaidActionKind.fromWireName("autonomous_mining"));
        assertEquals(MaidActionKind.RETURN_TO_POSITION,
                MaidActionKind.fromWireName("return_to_position"));
        assertEquals(MaidActionKind.LEGACY_ATTACK, MaidActionKind.fromWireName("legacy_attack"));
        assertThrows(IllegalArgumentException.class, () -> MaidActionKind.fromWireName("mine_down"));
    }

    @Test
    void terminalReasonsCoverTheFrozenContract() {
        EnumSet<ActionEndReason> reasons = EnumSet.allOf(ActionEndReason.class);
        assertTrue(reasons.containsAll(EnumSet.of(
                ActionEndReason.COMPLETED, ActionEndReason.REQUESTED,
                ActionEndReason.USER_OVERRIDE, ActionEndReason.SAFETY_PREEMPTED,
                ActionEndReason.SUPERSEDED, ActionEndReason.TIMEOUT,
                ActionEndReason.PATH_NOT_FOUND, ActionEndReason.STUCK,
                ActionEndReason.TARGET_CHANGED, ActionEndReason.BLOCK_PROTECTED,
                ActionEndReason.TOOL_NOT_FOUND, ActionEndReason.HAND_CONFLICT,
                ActionEndReason.ENTITY_UNLOADED, ActionEndReason.ENTITY_DEAD,
                ActionEndReason.SERVER_STATE_LOST, ActionEndReason.INTERNAL_ERROR)));
    }

    @Test
    void requestAndResponseTypesStayDistinct() {
        assertNotEquals(Protocol.TYPE_START_MAID_ACTION, Protocol.TYPE_MAID_ACTION_START_RESULT);
        assertNotEquals(Protocol.TYPE_CANCEL_MAID_ACTION, Protocol.TYPE_MAID_ACTION_CANCEL_RESULT);
        assertEquals("maid_action_finished", Protocol.TYPE_MAID_ACTION_FINISHED);
    }
}
