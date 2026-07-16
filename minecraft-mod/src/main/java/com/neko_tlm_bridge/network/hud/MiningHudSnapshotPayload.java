package com.neko_tlm_bridge.network.hud;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** Bounded server-authored action snapshot for the monitored-maid HUD. */
public record MiningHudSnapshotPayload(
        String monitoredMaidId,
        String snapshotJson
) implements CustomPacketPayload {
    public static final int MAX_MAID_ID_CHARS = 36;
    public static final int MAX_SNAPSHOT_CHARS = 32_767;
    public static final Type<MiningHudSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("neko_tlm_bridge", "mining_hud_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, MiningHudSnapshotPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MiningHudSnapshotPayload decode(FriendlyByteBuf buffer) {
                    return new MiningHudSnapshotPayload(
                            buffer.readUtf(MAX_MAID_ID_CHARS),
                            buffer.readUtf(MAX_SNAPSHOT_CHARS));
                }

                @Override
                public void encode(FriendlyByteBuf buffer, MiningHudSnapshotPayload payload) {
                    buffer.writeUtf(payload.monitoredMaidId(), MAX_MAID_ID_CHARS);
                    buffer.writeUtf(payload.snapshotJson(), MAX_SNAPSHOT_CHARS);
                }
            };

    public MiningHudSnapshotPayload {
        monitoredMaidId = Objects.requireNonNull(monitoredMaidId, "monitoredMaidId");
        snapshotJson = Objects.requireNonNull(snapshotJson, "snapshotJson");
        if (monitoredMaidId.length() > MAX_MAID_ID_CHARS) {
            throw new IllegalArgumentException("monitored maid id is too long");
        }
        if (snapshotJson.length() > MAX_SNAPSHOT_CHARS) {
            throw new IllegalArgumentException("mining HUD snapshot is too large");
        }
    }

    public static MiningHudSnapshotPayload clear() {
        return new MiningHudSnapshotPayload("", "");
    }

    public static void handle(MiningHudSnapshotPayload payload, IPayloadContext context) {
        if (!context.flow().isClientbound()) {
            return;
        }
        context.enqueueWork(() -> dispatchToClient(payload));
    }

    private static void dispatchToClient(MiningHudSnapshotPayload payload) {
        try {
            Class<?> clientClass = Class.forName("com.neko_tlm_bridge.client.MiningHudClient");
            Method method = clientClass.getMethod(
                    "acceptSnapshot", MiningHudSnapshotPayload.class);
            method.invoke(null, payload);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalStateException("Mining HUD client handler is unavailable", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Mining HUD client handler failed", cause);
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
