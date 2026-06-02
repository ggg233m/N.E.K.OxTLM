package com.neko_tlm_bridge.tlm;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.AbstractMaidContext;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.neko_tlm_bridge.ws.NekoWebSocketServer;

public class NekoBridgeContext extends AbstractMaidContext {

    public NekoBridgeContext() {
        super("neko_bridge_status", "N.E.K.O Bridge Connection Status");
    }

    @Override
    public String getValue(EntityMaid maid) {
        NekoWebSocketServer wsServer = NekoWebSocketServerHolder.getServer();
        boolean connected = wsServer != null && wsServer.hasClients();
        return "N.E.K.O Bridge Status: " + (connected ? "Connected" : "Disconnected");
    }
}
