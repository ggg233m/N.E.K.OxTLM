package com.neko_tlm_bridge;

import com.neko_tlm_bridge.config.ModConfig;
import com.neko_tlm_bridge.config.ClientConfig;
import com.neko_tlm_bridge.event.GameEventHandler;
import com.neko_tlm_bridge.tlm.NekoAttackTargetStore;
import com.neko_tlm_bridge.tlm.NekoWebSocketServerHolder;
import com.neko_tlm_bridge.ws.NekoCommand;
import com.neko_tlm_bridge.ws.NekoWebSocketServer;
import com.neko_tlm_bridge.ws.PendingCommandManager;
import com.neko_tlm_bridge.ws.Protocol;
import com.neko_tlm_bridge.ws.handler.*;
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
    private static MessageRouter messageRouter;

    public NekoTlmBridge(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(Type.COMMON, ModConfig.SPEC);
        modContainer.registerConfig(Type.CLIENT, ClientConfig.SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                    (container, parentScreen) -> new com.neko_tlm_bridge.client.NekoConfigScreen(parentScreen));
        }

        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onServerStarting);
        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onServerStopping);
        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onServerTick);
        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onRegisterCommands);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(com.neko_tlm_bridge.client.PlanOverlayRenderer::onRegisterGuiLayers);
        }
    }

    private static void onServerStarting(ServerStartingEvent event) {
        if (!ModConfig.NEKO_MODE_ENABLED.get()) {
            LOGGER.info("N.E.K.O Mode is disabled in config");
            return;
        }

        net.minecraft.server.MinecraftServer server = event.getServer();
        PendingCommandManager pendingCommandManager = new PendingCommandManager();

        // Create handlers
        MaidStatusHandler maidStatusHandler = new MaidStatusHandler(server);
        CommandMaidHandler commandMaidHandler = new CommandMaidHandler(server);
        GameContextHandler gameContextHandler = new GameContextHandler(server);
        ChatHandler chatHandler = new ChatHandler(server);
        CommandExecutionHandler commandExecutionHandler = new CommandExecutionHandler(server, pendingCommandManager);
        AttackTargetHandler attackTargetHandler = new AttackTargetHandler(server);
        SkillHandler skillHandler = new SkillHandler(server);
        ConfigHandler configHandler = new ConfigHandler(server);
        SetPlanHandler setPlanHandler = new SetPlanHandler();
        GetPlanHandler getPlanHandler = new GetPlanHandler();

        // Create router and register handlers
        java.util.Map<String, MessageHandlerInterface> handlers = new java.util.LinkedHashMap<>();
        handlers.put(Protocol.TYPE_GET_MAID_STATUS, maidStatusHandler);
        handlers.put(Protocol.TYPE_COMMAND_MAID, commandMaidHandler);
        handlers.put(Protocol.TYPE_GET_GAME_CONTEXT, gameContextHandler);
        handlers.put(Protocol.TYPE_SEND_CHAT, chatHandler);
        handlers.put(Protocol.TYPE_EXECUTE_COMMAND, commandExecutionHandler);
        handlers.put(Protocol.TYPE_ATTACK_TARGET, attackTargetHandler);
        handlers.put(Protocol.TYPE_USE_SKILL, skillHandler);
        handlers.put(Protocol.TYPE_GET_CONFIG, configHandler);
        handlers.put(Protocol.TYPE_SET_MONITORED_MAID, configHandler);
        handlers.put(Protocol.TYPE_SET_PLAN, setPlanHandler);
        handlers.put(Protocol.TYPE_GET_PLAN, getPlanHandler);
        messageRouter = new MessageRouter(handlers);

        // Create WebSocket server
        webSocketServer = new NekoWebSocketServer(messageRouter, pendingCommandManager);

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
                GameEventHandler.flushPendingBehaviorEvents();
                webSocketServer.stop();
                LOGGER.info("N.E.K.O Bridge WebSocket server stopped");
            } catch (Exception e) {
                LOGGER.error("Error stopping WebSocket server: {}", e.getMessage());
            }
            webSocketServer = null;
            NekoWebSocketServerHolder.setServer(null);
            GameEventHandler.setWebSocketServer(null);
        }
        // 清理静态状态，避免存档切换时残留导致误报或内存泄漏
        GameEventHandler.resetState();
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (messageRouter != null) {
            messageRouter.tick();
        }
        if (webSocketServer != null) {
            webSocketServer.tickPendingCommands();
        }
        NekoAttackTargetStore.tickCleanup();
        GameEventHandler.onServerTick(event);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        NekoCommand.register(event.getDispatcher());
    }
}
