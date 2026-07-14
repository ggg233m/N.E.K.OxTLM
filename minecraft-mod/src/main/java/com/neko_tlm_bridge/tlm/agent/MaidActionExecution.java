package com.neko_tlm_bridge.tlm.agent;

import com.google.gson.JsonObject;

import java.util.UUID;

public interface MaidActionExecution {
    UUID actionId();

    UUID maidId();

    long generation();

    long startedGameTime();

    long deadlineGameTime();

    void reportProgress(String stage, double progress, JsonObject detail);
}
