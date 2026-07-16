package com.neko_tlm_bridge.client;

import com.neko_tlm_bridge.config.ClientConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Keeps every class that references Minecraft client code out of dedicated-server loading. */
@OnlyIn(Dist.CLIENT)
public final class NekoClientBootstrap {
    private NekoClientBootstrap() {
    }

    public static void initialize(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parentScreen) -> new NekoConfigScreen(parentScreen));
        modEventBus.addListener(PlanOverlayRenderer::onRegisterGuiLayers);
        modEventBus.addListener(MiningHudOverlay::onRegisterGuiLayers);
        modEventBus.addListener(MaidEmergencyStopClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(MaidEmergencyStopClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(MaidPathDebugClient::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(MaidPathDebugClient::onClientLogin);
        NeoForge.EVENT_BUS.addListener(MaidPathDebugClient::onClientLogout);
        NeoForge.EVENT_BUS.addListener(MiningHudClient::onClientLogin);
        NeoForge.EVENT_BUS.addListener(MiningHudClient::onClientLogout);
    }
}
