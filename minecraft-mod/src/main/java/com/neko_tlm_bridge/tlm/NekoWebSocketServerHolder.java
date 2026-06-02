package com.neko_tlm_bridge.tlm;

import com.neko_tlm_bridge.ws.NekoWebSocketServer;

public class NekoWebSocketServerHolder {
    private static NekoWebSocketServer server;

    public static void setServer(NekoWebSocketServer wsServer) {
        server = wsServer;
    }

    public static NekoWebSocketServer getServer() {
        return server;
    }
}
