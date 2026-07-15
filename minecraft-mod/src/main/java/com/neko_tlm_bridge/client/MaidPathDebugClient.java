package com.neko_tlm_bridge.client;

import com.neko_tlm_bridge.config.ClientConfig;
import com.neko_tlm_bridge.network.debug.MaidPathNodesPayload;
import com.neko_tlm_bridge.network.debug.SetMaidPathDebugPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.debug.PathfindingRenderer;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Client-only path display state. The config screen calls {@link #setEnabled(boolean)}. */
public final class MaidPathDebugClient {
    private static PathfindingRenderer pathRenderer = new PathfindingRenderer();

    private MaidPathDebugClient() {
    }

    public static boolean isEnabled() {
        return ClientConfig.PATH_RENDERING_ENABLED.get();
    }

    public static void setEnabled(boolean value) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!value) {
            // PathfindingRenderer does not override clear(). Replacing our
            // private renderer reliably drops its cache without disturbing
            // unrelated vanilla or Mod debug state.
            resetRenderer();
        }
        if (minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(new SetMaidPathDebugPayload(value));
        }
    }

    /** Re-sends the local preference after joining or reconnecting to a server. */
    public static void synchronizePreference() {
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new SetMaidPathDebugPayload(isEnabled()));
        }
    }

    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        synchronizePreference();
    }

    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        resetRenderer();
    }

    /** Accepts the Mod's bounded node payload and reconstructs a render-only path. */
    public static void acceptPath(MaidPathNodesPayload payload) {
        if (!isEnabled()) {
            return;
        }
        List<Node> nodes = new ArrayList<>(payload.nodes().size());
        payload.nodes().forEach(pos -> {
            Node node = new Node(pos.getX(), pos.getY(), pos.getZ());
            node.type = PathType.WALKABLE;
            nodes.add(node);
        });
        Path path = new Path(nodes, payload.target(), payload.reached());
        path.setNextNodeIndex(payload.nextNodeIndex());
        pathRenderer.addPath(payload.entityId(), path, payload.maxNodeDistance());
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!isEnabled() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        pathRenderer.render(
                event.getPoseStack(),
                bufferSource,
                event.getCamera().getPosition().x,
                event.getCamera().getPosition().y,
                event.getCamera().getPosition().z);
        bufferSource.endBatch();
    }

    private static void resetRenderer() {
        pathRenderer = new PathfindingRenderer();
    }
}
