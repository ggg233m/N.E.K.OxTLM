package com.neko_tlm_bridge.client;

import com.neko_tlm_bridge.config.ClientConfig;
import com.neko_tlm_bridge.network.debug.SetMaidPathDebugPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-only path display state. The config screen calls {@link #setEnabled(boolean)}. */
public final class MaidPathDebugClient {
    private MaidPathDebugClient() {
    }

    public static boolean isEnabled() {
        return ClientConfig.PATH_RENDERING_ENABLED.get();
    }

    public static void setEnabled(boolean value) {
        Minecraft minecraft = Minecraft.getInstance();
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

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!isEnabled() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        minecraft.debugRenderer.pathfindingRenderer.render(
                event.getPoseStack(),
                bufferSource,
                event.getCamera().getPosition().x,
                event.getCamera().getPosition().y,
                event.getCamera().getPosition().z);
        bufferSource.endBatch();
    }
}
