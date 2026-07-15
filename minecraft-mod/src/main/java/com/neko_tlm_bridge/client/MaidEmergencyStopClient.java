package com.neko_tlm_bridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.neko_tlm_bridge.network.agent.EmergencyStopMaidActionsPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Configurable client key binding for the WebSocket-independent emergency stop. */
@OnlyIn(Dist.CLIENT)
public final class MaidEmergencyStopClient {
    private static final KeyMapping EMERGENCY_STOP = new KeyMapping(
            "key.neko_tlm_bridge.emergency_stop",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.neko_tlm_bridge");

    private MaidEmergencyStopClient() {
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(EMERGENCY_STOP);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        while (EMERGENCY_STOP.consumeClick()) {
            if (Minecraft.getInstance().getConnection() != null) {
                PacketDistributor.sendToServer(new EmergencyStopMaidActionsPayload(true));
            }
        }
    }
}
