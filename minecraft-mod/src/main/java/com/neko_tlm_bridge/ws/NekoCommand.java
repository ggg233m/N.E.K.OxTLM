package com.neko_tlm_bridge.ws;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.neko_tlm_bridge.tlm.NekoWebSocketServerHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NekoCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("NekoTlmBridge");
    private static final Gson GSON = new Gson();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("neko")
                .then(Commands.literal("accept")
                        .then(Commands.argument("pending_id", StringArgumentType.word())
                                .executes(ctx -> acceptCommand(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "pending_id")
                                ))))
                .then(Commands.literal("reject")
                        .then(Commands.argument("pending_id", StringArgumentType.word())
                                .executes(ctx -> rejectCommand(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "pending_id")
                                ))))
        );
    }

    private static int acceptCommand(CommandSourceStack source, String pendingId) {
        NekoWebSocketServer wsServer = NekoWebSocketServerHolder.getServer();
        if (wsServer == null) {
            source.sendFailure(Component.literal("N.E.K.O bridge is not running"));
            return 0;
        }

        PendingCommandManager manager = wsServer.getPendingCommandManager();
        PendingCommandManager.PendingCommand pending = manager.getAndRemove(pendingId);

        if (pending == null) {
            source.sendFailure(Component.translatable("neko_tlm_bridge.command.not_found", pendingId));
            return 0;
        }

        String command = pending.command;
        MinecraftServer server = source.getServer();

        server.getCommands().performPrefixedCommand(source, command);

        source.sendSuccess(() -> Component.translatable("neko_tlm_bridge.command.executed", command), true);

        if (pending.conn != null && pending.conn.isOpen()) {
            JsonObject response = new JsonObject();
            response.addProperty("type", Protocol.TYPE_COMMAND_EXECUTION_RESULT);
            if (pending.originalRequestId != null) {
                response.addProperty("request_id", pending.originalRequestId);
            }
            JsonObject data = new JsonObject();
            data.addProperty("approved", true);
            data.addProperty("success", true);
            data.addProperty("command", command);
            data.addProperty("approved_by", source.getTextName());
            response.add("data", data);
            pending.conn.send(GSON.toJson(response));
        }

        LOGGER.info("Player {} accepted command: {} (pending_id={})", source.getTextName(), command, pendingId);
        return 1;
    }

    private static int rejectCommand(CommandSourceStack source, String pendingId) {
        NekoWebSocketServer wsServer = NekoWebSocketServerHolder.getServer();
        if (wsServer == null) {
            source.sendFailure(Component.literal("N.E.K.O bridge is not running"));
            return 0;
        }

        PendingCommandManager manager = wsServer.getPendingCommandManager();
        PendingCommandManager.PendingCommand pending = manager.getAndRemove(pendingId);

        if (pending == null) {
            source.sendFailure(Component.translatable("neko_tlm_bridge.command.not_found", pendingId));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("neko_tlm_bridge.command.rejected", pending.command), true);

        if (pending.conn != null && pending.conn.isOpen()) {
            JsonObject response = new JsonObject();
            response.addProperty("type", Protocol.TYPE_COMMAND_EXECUTION_RESULT);
            if (pending.originalRequestId != null) {
                response.addProperty("request_id", pending.originalRequestId);
            }
            JsonObject data = new JsonObject();
            data.addProperty("approved", false);
            data.addProperty("command", pending.command);
            data.addProperty("rejected_by", source.getTextName());
            response.add("data", data);
            pending.conn.send(GSON.toJson(response));
        }

        LOGGER.info("Player {} rejected command: {} (pending_id={})", source.getTextName(), pending.command, pendingId);
        return 0;
    }

    public static void broadcastCommandRequest(MinecraftServer server, String pendingId, String command) {
        Component header = Component.translatable("neko_tlm_bridge.command.request_header")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

        Component cmdDisplay = Component.literal(command)
                .withStyle(ChatFormatting.YELLOW);

        Component acceptBtn = Component.translatable("neko_tlm_bridge.command.accept")
                .withStyle(Style.EMPTY
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/neko accept " + pendingId))
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true));

        Component separator = Component.literal("  ");

        Component rejectBtn = Component.translatable("neko_tlm_bridge.command.reject")
                .withStyle(Style.EMPTY
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/neko reject " + pendingId))
                        .withColor(ChatFormatting.RED)
                        .withBold(true));

        Component message = Component.empty()
                .append(header)
                .append(" ")
                .append(cmdDisplay)
                .append("  ")
                .append(acceptBtn)
                .append(separator)
                .append(rejectBtn);

        server.getPlayerList().broadcastSystemMessage(message, false);
    }
}
