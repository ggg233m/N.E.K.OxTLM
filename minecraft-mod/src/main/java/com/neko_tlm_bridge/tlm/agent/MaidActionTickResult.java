package com.neko_tlm_bridge.tlm.agent;

import com.google.gson.JsonObject;

public record MaidActionTickResult(
        Outcome outcome,
        ActionEndReason reason,
        JsonObject result
) {
    public enum Outcome {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public static MaidActionTickResult running() {
        return new MaidActionTickResult(Outcome.RUNNING, null, new JsonObject());
    }

    public static MaidActionTickResult succeeded(JsonObject result) {
        return new MaidActionTickResult(Outcome.SUCCEEDED, ActionEndReason.COMPLETED,
                result == null ? new JsonObject() : result);
    }

    public static MaidActionTickResult failed(ActionEndReason reason, JsonObject result) {
        return new MaidActionTickResult(Outcome.FAILED, reason,
                result == null ? new JsonObject() : result);
    }
}
