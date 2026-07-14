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
}
