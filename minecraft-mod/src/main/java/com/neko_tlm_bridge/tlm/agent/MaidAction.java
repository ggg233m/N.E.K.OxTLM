package com.neko_tlm_bridge.tlm.agent;

import com.google.gson.JsonObject;

import java.util.Set;

public interface MaidAction {
    MaidActionKind kind();

    Set<MaidActionResource> resources();

    default void start(MaidActionContext context) {
    }

    MaidActionTickResult tick(MaidActionContext context);

    default void stop(MaidActionContext context, ActionEndReason reason) {
    }

    /**
     * Supplies an action-owned terminal snapshot when the runtime, rather than
     * {@link #tick(MaidActionContext)}, ends the action.  Implementations must
     * be side-effect free; caller-provided terminal fields remain authoritative.
     */
    default JsonObject terminationResult(MaidActionContext context, ActionEndReason reason) {
        return new JsonObject();
    }
}
