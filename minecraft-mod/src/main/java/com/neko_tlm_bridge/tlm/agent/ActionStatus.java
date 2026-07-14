package com.neko_tlm_bridge.tlm.agent;

public enum ActionStatus {
    PENDING,
    RUNNING,
    CANCEL_REQUESTED,
    TERMINATING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SUPERSEDED,
    TIMEOUT;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED
                || this == SUPERSEDED || this == TIMEOUT;
    }

    public boolean canTransitionTo(ActionStatus next) {
        if (next == null || this == next || isTerminal()) {
            return false;
        }
        return switch (this) {
            case PENDING -> next == RUNNING || next == TERMINATING;
            case RUNNING -> next == CANCEL_REQUESTED || next == TERMINATING;
            case CANCEL_REQUESTED -> next == TERMINATING;
            case TERMINATING -> next.isTerminal();
            case SUCCEEDED, FAILED, CANCELLED, SUPERSEDED, TIMEOUT -> false;
        };
    }
}
