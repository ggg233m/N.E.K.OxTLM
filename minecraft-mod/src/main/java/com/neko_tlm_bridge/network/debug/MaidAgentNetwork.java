package com.neko_tlm_bridge.network.debug;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class MaidAgentNetwork {
    private static final String VERSION = "1.0.0";

    private MaidAgentNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(
                SetMaidPathDebugPayload.TYPE,
                SetMaidPathDebugPayload.STREAM_CODEC,
                SetMaidPathDebugPayload::handle);
    }
}
