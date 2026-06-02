package com.neko_tlm_bridge;

import com.neko_tlm_bridge.config.ModConfig;
import com.neko_tlm_bridge.event.GameEventHandler;
import com.neko_tlm_bridge.tlm.NekoAttackTargetStore;
import com.neko_tlm_bridge.tlm.NekoWebSocketServerHolder;
import com.neko_tlm_bridge.ws.MessageHandler;
import com.neko_tlm_bridge.ws.NekoCommand;
import com.neko_tlm_bridge.ws.NekoWebSocketServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(NekoTlmBridge.MOD_ID)
public class NekoTlmBridge {
    public static final String MOD_ID = "neko_tlm_bridge";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static NekoWebSocketServer webSocketServer;
    private static MessageHandler messageHandler;

    public NekoTlmBridge(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(Type.COMMON, ModConfig.SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                    (container, parentScreen) -> new com.neko_tlm_bridge.client.NekoConfigScreen(parentScreen));
        }

        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onServerStarting);
        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onServerStopping);
        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onServerTick);
        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onRegisterCommands);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        if (!ModConfig.NEKO_MODE_ENABLED.get()) {
            LOGGER.info("N.E.K.O Mode is disabled in config");
            return;
        }

        messageHandler = new MessageHandler(null);
        webSocketServer = new NekoWebSocketServer(messageHandler);
        messageHandler.setMinecraftServer(event.getServer());

        try {
            webSocketServer.start();
            NekoWebSocketServerHolder.setServer(webSocketServer);
            GameEventHandler.setWebSocketServer(webSocketServer);
            LOGGER.info("N.E.K.O Mode enabled, WebSocket server started on port {}", ModConfig.WEBSOCKET_PORT.get());
        } catch (Exception e) {
            LOGGER.error("Failed to start WebSocket server: {}", e.getMessage());
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (webSocketServer != null) {
            try {
                webSocketServer.stop();
                LOGGER.info("N.E.K.O Bridge WebSocket server stopped");
            } catch (Exception e) {
                LOGGER.error("Error stopping WebSocket server: {}", e.getMessage());
            }
            webSocketServer = null;
            NekoWebSocketServerHolder.setServer(null);
            GameEventHandler.setWebSocketServer(null);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (messageHandler != null) {
            messageHandler.tick();
        }
        if (webSocketServer != null) {
            webSocketServer.tickPendingCommands();
        }
        NekoAttackTargetStore.tickCleanup();
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        NekoCommand.register(event.getDispatcher());
    }
}
