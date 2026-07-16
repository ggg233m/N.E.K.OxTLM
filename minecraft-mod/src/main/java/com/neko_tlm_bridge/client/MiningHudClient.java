package com.neko_tlm_bridge.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko_tlm_bridge.network.hud.MiningHudSnapshotPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Receives server-authored action snapshots and formats mining progress for
 * {@link MiningHudOverlay}. No integrated-server access is used, so this also
 * works when the client is connected to a dedicated server.
 */
@OnlyIn(Dist.CLIENT)
public final class MiningHudClient {
    private MiningHudClient() {
    }

    public static void acceptSnapshot(MiningHudSnapshotPayload payload) {
        if (payload.snapshotJson().isEmpty()) {
            MiningHudOverlay.clear();
            return;
        }
        try {
            JsonObject status = JsonParser.parseString(payload.snapshotJson()).getAsJsonObject();
            String formatted = formatMiningProgress(status);
            if (formatted.isEmpty()) {
                MiningHudOverlay.clear();
            } else {
                MiningHudOverlay.setMiningProgress(formatted);
            }
        } catch (RuntimeException malformedSnapshot) {
            MiningHudOverlay.clear();
        }
    }

    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        MiningHudOverlay.clear();
    }

    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        MiningHudOverlay.clear();
    }

    private static String formatMiningProgress(JsonObject status) {
        String kind = status.has("kind") ? status.get("kind").getAsString() : "";
        String stage = status.has("stage") ? status.get("stage").getAsString() : "";

        if (!isMiningKind(kind)) {
            return "";
        }

        JsonObject detail = status.has("detail") && status.get("detail").isJsonObject()
                ? status.getAsJsonObject("detail")
                : new JsonObject();

        StringBuilder sb = new StringBuilder();

        switch (kind) {
            case "autonomous_mining" -> formatAutonomousMining(sb, detail, stage);
            case "excavate_segment" -> formatExcavateSegment(sb, detail, stage);
            case "harvest_blocks" -> formatHarvestBlocks(sb, detail, stage);
            default -> {
                return "";
            }
        }

        return sb.toString().trim();
    }

    private static boolean isMiningKind(String kind) {
        return "autonomous_mining".equals(kind)
                || "excavate_segment".equals(kind)
                || "harvest_blocks".equals(kind);
    }

    private static void formatAutonomousMining(StringBuilder sb, JsonObject d, String stage) {
        sb.append("[挖矿] ");
        int collected = d.has("collected_count") ? d.get("collected_count").getAsInt() : 0;
        int target = d.has("target_count") ? d.get("target_count").getAsInt() : 0;
        if (target > 0) {
            sb.append(collected).append('/').append(target);
        } else {
            sb.append(collected);
        }
        sb.append('\n');

        int segments = d.has("segments_dug") ? d.get("segments_dug").getAsInt() : 0;
        int cleared = d.has("cleared_blocks") ? d.get("cleared_blocks").getAsInt() : 0;
        if (segments > 0 || cleared > 0) {
            sb.append("段:").append(segments)
              .append(" 块:").append(cleared).append('\n');
        }

        if (d.has("current_y")) {
            sb.append("Y:").append(d.get("current_y").getAsInt());
            if (d.has("working_y")) {
                sb.append("->").append(d.get("working_y").getAsInt());
            }
            sb.append('\n');
        }

        if (d.has("route_choice")) {
            String route = d.get("route_choice").getAsString();
            sb.append("natural_passage".equals(route) ? "自然通道" : "挖掘通道");
        } else {
            sb.append(stage);
        }
    }

    private static void formatExcavateSegment(StringBuilder sb, JsonObject d, String stage) {
        sb.append("[掘通道] ");
        int segments = d.has("segments_dug") ? d.get("segments_dug").getAsInt() : 0;
        sb.append(segments).append("段\n");

        if (d.has("cleared_blocks")) {
            sb.append("清理:").append(d.get("cleared_blocks").getAsInt()).append("块\n");
        }

        if (d.has("direction")) {
            sb.append("方向:").append(d.get("direction").getAsString());
            if (d.has("shape")) {
                sb.append(" ").append(d.get("shape").getAsString());
            }
            sb.append('\n');
        }

        if (d.has("stop_reason")) {
            sb.append("停止:").append(d.get("stop_reason").getAsString());
        } else {
            sb.append(stage);
        }
    }

    private static void formatHarvestBlocks(StringBuilder sb, JsonObject d, String stage) {
        sb.append("[采集] ");
        if (d.has("harvested")) {
            sb.append(d.get("harvested").getAsInt());
            if (d.has("max_blocks")) {
                sb.append('/').append(d.get("max_blocks").getAsInt());
            }
        }
        sb.append('\n').append(stage);
    }
}
