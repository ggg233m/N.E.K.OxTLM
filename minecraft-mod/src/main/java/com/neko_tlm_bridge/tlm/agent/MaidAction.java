package com.neko_tlm_bridge.tlm.agent;

import com.google.gson.JsonObject;

import java.util.Set;

public interface MaidAction {
    enum CompletionDisposition {
        RESTORE_BODY,
        FOLLOW_OWNER
    }

    MaidActionKind kind();

    Set<MaidActionResource> resources();

    default void start(MaidActionContext context) {
    }

    MaidActionTickResult tick(MaidActionContext context);

    default void stop(MaidActionContext context, ActionEndReason reason) {
    }

    /**
     * 描述动作成功且临时身体租约恢复用户任务后，应当应用的稳定身体状态。
     * 非成功终止状态始终保留默认的 RESTORE_BODY 行为。
     */
    default CompletionDisposition completionDisposition() {
        return CompletionDisposition.RESTORE_BODY;
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
