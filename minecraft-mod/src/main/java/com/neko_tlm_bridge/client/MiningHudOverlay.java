package com.neko_tlm_bridge.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Client-side HUD overlay that shows live mining-action progress for the
 * monitored maid.  Mirrors {@link PlanOverlayRenderer} but renders at the
 * top-left corner and is driven by {@link MiningHudClient}, which receives
 * server-authored action snapshots.
 */
@OnlyIn(Dist.CLIENT)
public final class MiningHudOverlay {
    private static volatile String text = "";

    private MiningHudOverlay() {
    }

    public static void setMiningProgress(String newText) {
        text = newText != null ? newText : "";
    }

    public static void clear() {
        text = "";
    }

    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR,
                ResourceLocation.fromNamespaceAndPath("neko_tlm_bridge", "mining_hud"),
                MiningHudOverlay::renderOverlay);
    }

    private static void renderOverlay(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (text.isEmpty()) return;

        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        String[] lines = text.split("\n");
        int lineHeight = 10;
        int padding = 4;

        int maxWidth = 0;
        for (String line : lines) {
            int w = font.width(line);
            if (w > maxWidth) maxWidth = w;
        }

        int boxWidth = maxWidth + padding * 2;
        int boxHeight = lines.length * lineHeight + padding * 2 - 2;
        int x = 5;
        int y = 5;

        graphics.fill(x, y, x + boxWidth, y + boxHeight, 0x80000000);
        graphics.fill(x, y, x + boxWidth, y + 1, 0xFF4A90D9);
        graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, 0xFF4A90D9);

        for (int i = 0; i < lines.length; i++) {
            int color = i == 0 ? 0x66CCFF : 0xFFFFFF;
            graphics.drawString(font, lines[i], x + padding, y + padding + i * lineHeight, color, true);
        }
    }
}
