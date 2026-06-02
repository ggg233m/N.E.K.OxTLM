package com.neko_tlm_bridge.event;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.config.ModConfig;
import com.neko_tlm_bridge.ws.NekoWebSocketServer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber
public class GameEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("NekoTlmBridge");
    private static NekoWebSocketServer webSocketServer;

    public static void setWebSocketServer(NekoWebSocketServer server) {
        webSocketServer = server;
    }

    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.EntityInteract event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        if (event.getTarget() instanceof EntityMaid maid) {
            JsonObject eventData = new JsonObject();
            eventData.addProperty("event_type", "player_interact");
            eventData.addProperty("maid_id", maid.getStringUUID());
            eventData.addProperty("maid_name", maid.getName().getString());
            if (event.getEntity() instanceof Player player) {
                eventData.addProperty("player_name", player.getName().getString());
            }
            webSocketServer.broadcastEvent(eventData);
        }
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingIncomingDamageEvent event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        if (event.getEntity() instanceof EntityMaid maid) {
            JsonObject eventData = new JsonObject();
            eventData.addProperty("event_type", "maid_hurt");
            eventData.addProperty("maid_id", maid.getStringUUID());
            eventData.addProperty("maid_name", maid.getName().getString());
            eventData.addProperty("damage", event.getAmount());
            eventData.addProperty("health", maid.getHealth() - event.getAmount());
            eventData.addProperty("max_health", maid.getMaxHealth());
            if (event.getSource().getEntity() instanceof Player player) {
                eventData.addProperty("attacker", player.getName().getString());
            }
            webSocketServer.broadcastEvent(eventData);
        }
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        String message = event.getRawText();
        net.minecraft.server.level.ServerPlayer player = event.getPlayer();
        JsonObject chatData = new JsonObject();
        chatData.addProperty("event_type", "chat");
        chatData.addProperty("sender", player.getName().getString());
        chatData.addProperty("message", message);
        chatData.addProperty("x", player.getX());
        chatData.addProperty("y", player.getY());
        chatData.addProperty("z", player.getZ());
        webSocketServer.broadcastChatMessage(chatData);
    }
}
