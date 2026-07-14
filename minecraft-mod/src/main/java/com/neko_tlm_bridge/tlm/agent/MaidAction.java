package com.neko_tlm_bridge.tlm.agent;

import java.util.Set;

public interface MaidAction {
    MaidActionKind kind();

    Set<MaidActionResource> resources();

    default void start(MaidActionContext context) {
    }

    MaidActionTickResult tick(MaidActionContext context);

    default void stop(MaidActionContext context, ActionEndReason reason) {
    }
}
