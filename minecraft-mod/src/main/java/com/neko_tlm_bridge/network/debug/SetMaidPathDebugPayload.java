package com.neko_tlm_bridge.network.debug;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record SetMaidPathDebugPayload(boolean enabled) implements CustomPacketPayload {
    public static final Type<SetMaidPathDebugPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("neko_tlm_bridge", "set_maid_path_debug"));
    public static final StreamCodec<ByteBuf, SetMaidPathDebugPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL,
            SetMaidPathDebugPayload::enabled,
            SetMaidPathDebugPayload::new);

    public static void handle(SetMaidPathDebugPayload payload, IPayloadContext context) {
        if (!context.flow().isServerbound()) {
            return;
        }
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MaidPathDebugService.setSubscribed(player, payload.enabled());
            }
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
