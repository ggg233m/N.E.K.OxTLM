package com.neko_tlm_bridge;

import com.neko_tlm_bridge.config.ModConfig;
import com.neko_tlm_bridge.event.GameEventHandler;
import com.neko_tlm_bridge.gametest.MaidAgentGameTests;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.neko_tlm_bridge.network.debug.MaidAgentNetwork;
import com.neko_tlm_bridge.network.debug.MaidPathDebugService;
import com.neko_tlm_bridge.network.hud.MiningHudSyncService;
import com.neko_tlm_bridge.tlm.NekoWebSocketServerHolder;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.action.AutonomousMiningAction;
import com.neko_tlm_bridge.tlm.agent.action.HarvestBlocksAction;
import com.neko_tlm_bridge.tlm.agent.action.ExcavateSegmentAction;
import com.neko_tlm_bridge.tlm.agent.action.LegacyAttackAction;
import com.neko_tlm_bridge.tlm.agent.action.NavigateAction;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import com.neko_tlm_bridge.tlm.agent.world.AutonomousMiningRecovery;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
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
        if (FMLEnvironment.dist == Dist.CLIENT) {
            initializeClient(modEventBus, modContainer);
        }

        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onServerStarting);
        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onServerStopping);
        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onServerTick);
        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(NekoTlmBridge::onPlayerLoggedOut);
        modEventBus.addListener(MaidAgentNetwork::registerPayloads);
        modEventBus.addListener(NekoTlmBridge::onRegisterGameTests);

    }

    private static void initializeClient(IEventBus modEventBus, ModContainer modContainer) {
        try {
            // A string-based boundary prevents dedicated-server verification from resolving
            // NekoClientBootstrap or any class in its Screen/render event signatures.
            Class<?> bootstrap = Class.forName("com.neko_tlm_bridge.client.NekoClientBootstrap");
            bootstrap.getMethod("initialize", IEventBus.class, ModContainer.class)
                    .invoke(null, modEventBus, modContainer);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to initialize N.E.K.O client integration", failure);
        }
    }

    private static void onServerStarting(ServerStartingEvent event) {
        if (!ModConfig.NEKO_MODE_ENABLED.get()) {
            LOGGER.info("N.E.K.O Mode is disabled in config");
            return;
        }

        net.minecraft.server.MinecraftServer server = event.getServer();
        PendingCommandManager pendingCommandManager = new PendingCommandManager();
        registerMaidActionFactories();

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
        StartMaidActionHandler startMaidActionHandler = new StartMaidActionHandler(server);
        CancelMaidActionHandler cancelMaidActionHandler = new CancelMaidActionHandler(server);
        GetMaidActionStatusHandler getMaidActionStatusHandler = new GetMaidActionStatusHandler(server);
        ListActiveMaidActionsHandler listActiveMaidActionsHandler = new ListActiveMaidActionsHandler(server);

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
        handlers.put(Protocol.TYPE_START_MAID_ACTION, startMaidActionHandler);
        handlers.put(Protocol.TYPE_CANCEL_MAID_ACTION, cancelMaidActionHandler);
        handlers.put(Protocol.TYPE_GET_MAID_ACTION_STATUS, getMaidActionStatusHandler);
        handlers.put(Protocol.TYPE_LIST_ACTIVE_MAID_ACTIONS, listActiveMaidActionsHandler);
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
        MaidActionStore.getInstance().shutdown();
        MaidPathDebugService.reset();
        MiningHudSyncService.reset();
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
        MaidActionStore.getInstance().tick(event.getServer());
        MiningHudSyncService.onServerTick(event);
        GameEventHandler.onServerTick(event);
    }

    private static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof EntityMaid maid) {
            AutonomousMiningRecovery.recover(maid);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        MaidPathDebugService.removeSubscriber(event.getEntity().getUUID());
    }

    private static void registerMaidActionFactories() {
        MaidActionStore store = MaidActionStore.getInstance();
        store.registerFactory(MaidActionKind.NAVIGATE,
                (maid, args) -> NavigateAction.fromArgs(args));
        store.registerFactory(MaidActionKind.HARVEST_BLOCKS,
                (maid, args) -> HarvestBlocksAction.fromArgs(args));
        store.registerFactory(MaidActionKind.EXCAVATE_SEGMENT,
                (maid, args) -> ExcavateSegmentAction.fromArgs(args));
        store.registerFactory(MaidActionKind.AUTONOMOUS_MINING,
                (maid, args) -> AutonomousMiningAction.fromArgs(args));
        IMaidTask attackTask = TaskManager.findTask(
                        net.minecraft.resources.ResourceLocation.parse("touhou_little_maid:attack"))
                .orElse(TaskManager.getIdleTask());
        store.registerFactory(MaidActionKind.LEGACY_ATTACK,
                (maid, args) -> LegacyAttackAction.fromArgs(args), attackTask);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        NekoCommand.register(event.getDispatcher());
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(MaidAgentGameTests.class);
    }
}
