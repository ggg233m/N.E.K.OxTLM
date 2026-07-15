package com.neko_tlm_bridge.network.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded client-bound representation of a maid route.
 *
 * <p>This intentionally does not use vanilla {@code minecraft:debug/path}.
 * Vanilla {@link Path#writeToStream} writes nothing when a programmatically
 * constructed path has no internal DebugData, while its decoder always expects
 * a complete path. Sending such a path therefore disconnects the client.</p>
 */
public record MaidPathNodesPayload(
        int entityId,
        List<BlockPos> nodes,
        int nextNodeIndex,
        BlockPos target,
        boolean reached,
        float maxNodeDistance
) implements CustomPacketPayload {
    public static final int MAX_NODES = 256;
    public static final Type<MaidPathNodesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("neko_tlm_bridge", "maid_path_nodes"));
    public static final StreamCodec<FriendlyByteBuf, MaidPathNodesPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MaidPathNodesPayload decode(FriendlyByteBuf buffer) {
                    int entityId = buffer.readVarInt();
                    int nodeCount = buffer.readVarInt();
                    if (nodeCount < 1 || nodeCount > MAX_NODES) {
                        throw new IllegalArgumentException("maid path node count out of bounds: " + nodeCount);
                    }
                    List<BlockPos> nodes = new ArrayList<>(nodeCount);
                    for (int i = 0; i < nodeCount; i++) {
                        nodes.add(buffer.readBlockPos());
                    }
                    int nextNodeIndex = buffer.readVarInt();
                    BlockPos target = buffer.readBlockPos();
                    boolean reached = buffer.readBoolean();
                    float maxNodeDistance = buffer.readFloat();
                    return new MaidPathNodesPayload(entityId, nodes, nextNodeIndex,
                            target, reached, maxNodeDistance);
                }

                @Override
                public void encode(FriendlyByteBuf buffer, MaidPathNodesPayload payload) {
                    buffer.writeVarInt(payload.entityId());
                    buffer.writeVarInt(payload.nodes().size());
                    payload.nodes().forEach(buffer::writeBlockPos);
                    buffer.writeVarInt(payload.nextNodeIndex());
                    buffer.writeBlockPos(payload.target());
                    buffer.writeBoolean(payload.reached());
                    buffer.writeFloat(payload.maxNodeDistance());
                }
            };

    public MaidPathNodesPayload {
        Objects.requireNonNull(nodes, "nodes");
        if (nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("nodes must contain between 1 and " + MAX_NODES + " positions");
        }
        nodes = nodes.stream()
                .map(pos -> Objects.requireNonNull(pos, "node").immutable())
                .toList();
        target = Objects.requireNonNull(target, "target").immutable();
        if (nextNodeIndex < 0 || nextNodeIndex > nodes.size()) {
            throw new IllegalArgumentException("nextNodeIndex is outside the node list");
        }
        if (!Float.isFinite(maxNodeDistance) || maxNodeDistance <= 0.0F || maxNodeDistance > 4.0F) {
            throw new IllegalArgumentException("maxNodeDistance must be finite and within (0, 4]");
        }
    }

    public static MaidPathNodesPayload fromPath(int entityId, Path path, float maxNodeDistance) {
        Objects.requireNonNull(path, "path");
        int count = path.getNodeCount();
        if (count < 1 || count > MAX_NODES) {
            throw new IllegalArgumentException("path must contain between 1 and " + MAX_NODES + " nodes");
        }
        List<BlockPos> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            nodes.add(path.getNodePos(i));
        }
        return new MaidPathNodesPayload(entityId, nodes, path.getNextNodeIndex(),
                path.getTarget(), path.canReach(), maxNodeDistance);
    }

    public static void handle(MaidPathNodesPayload payload, IPayloadContext context) {
        if (!context.flow().isClientbound()) {
            return;
        }
        context.enqueueWork(() -> dispatchToClient(payload));
    }

    private static void dispatchToClient(MaidPathNodesPayload payload) {
        try {
            Class<?> clientClass = Class.forName("com.neko_tlm_bridge.client.MaidPathDebugClient");
            Method method = clientClass.getMethod("acceptPath", MaidPathNodesPayload.class);
            method.invoke(null, payload);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalStateException("Maid path client handler is unavailable", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Maid path client handler failed", cause);
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
