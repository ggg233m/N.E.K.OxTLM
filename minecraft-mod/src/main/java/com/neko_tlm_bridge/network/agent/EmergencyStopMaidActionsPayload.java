package com.neko_tlm_bridge.network.agent;

import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/** Client-to-server emergency stop that remains independent of Python and WebSocket. */
public record EmergencyStopMaidActionsPayload(boolean requested) implements CustomPacketPayload {
    public static final Type<EmergencyStopMaidActionsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("neko_tlm_bridge", "emergency_stop_maid_actions"));
    public static final StreamCodec<ByteBuf, EmergencyStopMaidActionsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL,
            EmergencyStopMaidActionsPayload::requested,
            EmergencyStopMaidActionsPayload::new);

    public static void handle(EmergencyStopMaidActionsPayload payload, IPayloadContext context) {
        if (!payload.requested() || !context.flow().isServerbound()) {
            return;
        }
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            int stopped = MaidActionStore.getInstance().emergencyStopOwnedBy(player.getUUID());
            player.sendSystemMessage(stopped > 0
                    ? Component.translatable("message.neko_tlm_bridge.emergency_stop.stopped", stopped)
                    : Component.translatable("message.neko_tlm_bridge.emergency_stop.none"));
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
