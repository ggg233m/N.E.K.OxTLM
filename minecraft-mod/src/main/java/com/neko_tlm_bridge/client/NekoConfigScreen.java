package com.neko_tlm_bridge.client;

import com.neko_tlm_bridge.config.ModConfig;
import com.neko_tlm_bridge.config.ClientConfig;
import com.neko_tlm_bridge.tlm.NekoWebSocketServerHolder;
import com.neko_tlm_bridge.ws.NekoWebSocketServer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class NekoConfigScreen extends Screen {
    private final Screen parent;

    private Button nekoModeButton;
    private Button maidAgentButton;
    private EditBox portEditBox;
    private Button eventPushButton;
    private Button commandExecutionButton;
    private Button chatBubbleButton;
    private Button chatBoxButton;
    private Button pathRenderingButton;
    private Button doneButton;

    private boolean nekoModeEnabled;
    private boolean maidAgentEnabled;
    private boolean eventPushEnabled;
    private boolean commandExecutionEnabled;
    private boolean chatBubbleEnabled;
    private boolean chatBoxEnabled;
    private boolean pathRenderingEnabled;

    public NekoConfigScreen(Screen parent) {
        super(Component.translatable("neko_tlm_bridge.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        nekoModeEnabled = ModConfig.NEKO_MODE_ENABLED.get();
        maidAgentEnabled = ModConfig.MAID_AGENT_ENABLED.get();
        eventPushEnabled = ModConfig.EVENT_PUSH_ENABLED.get();
        commandExecutionEnabled = ModConfig.COMMAND_EXECUTION_ENABLED.get();
        chatBubbleEnabled = ModConfig.CHAT_BUBBLE_ENABLED.get();
        chatBoxEnabled = ModConfig.CHAT_BOX_ENABLED.get();
        pathRenderingEnabled = ClientConfig.PATH_RENDERING_ENABLED.get();

        int centerX = this.width / 2;

        nekoModeButton = Button.builder(
                toggleText("neko_tlm_bridge.config.bridge.nekoModeEnabled", nekoModeEnabled),
                button -> {
                    nekoModeEnabled = !nekoModeEnabled;
                    button.setMessage(toggleText("neko_tlm_bridge.config.bridge.nekoModeEnabled", nekoModeEnabled));
                    updateWidgetVisibility();
                }
        ).bounds(centerX - 100, 30, 200, 20).build();
        this.addRenderableWidget(nekoModeButton);

        portEditBox = new EditBox(this.font, centerX - 100, 70, 200, 20, Component.translatable("neko_tlm_bridge.config.websocket.port"));
        portEditBox.setMaxLength(5);
        portEditBox.setValue(String.valueOf(ModConfig.WEBSOCKET_PORT.get()));
        this.addRenderableWidget(portEditBox);

        maidAgentButton = Button.builder(
                toggleText("neko_tlm_bridge.config.bridge.maidAgentEnabled", maidAgentEnabled),
                button -> {
                    maidAgentEnabled = !maidAgentEnabled;
                    button.setMessage(toggleText("neko_tlm_bridge.config.bridge.maidAgentEnabled", maidAgentEnabled));
                }
        ).bounds(centerX - 155, 100, 150, 20).build();
        this.addRenderableWidget(maidAgentButton);

        eventPushButton = Button.builder(
                toggleText("neko_tlm_bridge.config.bridge.eventPushEnabled", eventPushEnabled),
                button -> {
                    eventPushEnabled = !eventPushEnabled;
                    button.setMessage(toggleText("neko_tlm_bridge.config.bridge.eventPushEnabled", eventPushEnabled));
                }
        ).bounds(centerX - 155, 130, 150, 20).build();
        this.addRenderableWidget(eventPushButton);

        commandExecutionButton = Button.builder(
                toggleText("neko_tlm_bridge.config.bridge.commandExecutionEnabled", commandExecutionEnabled),
                button -> {
                    commandExecutionEnabled = !commandExecutionEnabled;
                    button.setMessage(toggleText("neko_tlm_bridge.config.bridge.commandExecutionEnabled", commandExecutionEnabled));
                }
        ).bounds(centerX - 155, 160, 150, 20).build();
        this.addRenderableWidget(commandExecutionButton);

        chatBubbleButton = Button.builder(
                toggleText("neko_tlm_bridge.config.bridge.chatBubbleEnabled", chatBubbleEnabled),
                button -> {
                    chatBubbleEnabled = !chatBubbleEnabled;
                    button.setMessage(toggleText("neko_tlm_bridge.config.bridge.chatBubbleEnabled", chatBubbleEnabled));
                }
        ).bounds(centerX + 5, 100, 150, 20).build();
        this.addRenderableWidget(chatBubbleButton);

        chatBoxButton = Button.builder(
                toggleText("neko_tlm_bridge.config.bridge.chatBoxEnabled", chatBoxEnabled),
                button -> {
                    chatBoxEnabled = !chatBoxEnabled;
                    button.setMessage(toggleText("neko_tlm_bridge.config.bridge.chatBoxEnabled", chatBoxEnabled));
                }
        ).bounds(centerX + 5, 130, 150, 20).build();
        this.addRenderableWidget(chatBoxButton);

        pathRenderingButton = Button.builder(
                toggleText("neko_tlm_bridge.config.client.pathRenderingEnabled", pathRenderingEnabled),
                button -> {
                    pathRenderingEnabled = !pathRenderingEnabled;
                    ClientConfig.PATH_RENDERING_ENABLED.set(pathRenderingEnabled);
                    ClientConfig.SPEC.save();
                    MaidPathDebugClient.setEnabled(pathRenderingEnabled);
                    button.setMessage(toggleText("neko_tlm_bridge.config.client.pathRenderingEnabled", pathRenderingEnabled));
                }
        ).bounds(centerX + 5, 160, 150, 20).build();
        this.addRenderableWidget(pathRenderingButton);

        doneButton = Button.builder(
                Component.translatable("neko_tlm_bridge.config.done"),
                button -> {
                    saveConfig();
                    onClose();
                }
        ).bounds(centerX - 100, this.height - 27, 200, 20).build();
        this.addRenderableWidget(doneButton);

        updateWidgetVisibility();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (nekoModeEnabled) {
            int centerX = this.width / 2;
            guiGraphics.drawString(this.font, Component.translatable("neko_tlm_bridge.config.websocket.port"), centerX - 100, 60, 0xA0A0A0);

            boolean connected = NekoWebSocketServerHolder.getServer() != null && NekoWebSocketServerHolder.getServer().hasClients();
            Component statusLabel = Component.translatable("neko_tlm_bridge.config.connection_status");
            Component statusValue = connected
                    ? Component.translatable("neko_tlm_bridge.config.connection_connected")
                    : Component.translatable("neko_tlm_bridge.config.connection_disconnected");
            int statusColor = connected ? 0x55FF55 : 0xFF5555;
            guiGraphics.drawString(this.font, statusLabel, centerX - 155, 190, 0xA0A0A0);
            guiGraphics.drawString(this.font, statusValue, centerX + 155 - this.font.width(statusValue), 190, statusColor);
        } else {
            guiGraphics.drawCenteredString(this.font, Component.translatable("neko_tlm_bridge.config.neko_mode_hint"), this.width / 2, 70, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private Component toggleText(String translationKey, boolean value) {
        return Component.translatable(translationKey)
                .append(": ")
                .append(value ? Component.literal("ON") : Component.literal("OFF"));
    }

    private void updateWidgetVisibility() {
        boolean visible = nekoModeEnabled;
        portEditBox.visible = visible;
        maidAgentButton.visible = visible;
        eventPushButton.visible = visible;
        commandExecutionButton.visible = visible;
        chatBubbleButton.visible = visible;
        chatBoxButton.visible = visible;
        pathRenderingButton.visible = visible;
        if (!visible) {
            portEditBox.setFocused(false);
        }
    }

    private void saveConfig() {
        ModConfig.NEKO_MODE_ENABLED.set(nekoModeEnabled);
        try {
            int port = Integer.parseInt(portEditBox.getValue());
            if (port >= 1024 && port <= 65535) {
                ModConfig.WEBSOCKET_PORT.set(port);
            }
        } catch (NumberFormatException ignored) {
        }
        ModConfig.EVENT_PUSH_ENABLED.set(eventPushEnabled);
        ModConfig.MAID_AGENT_ENABLED.set(maidAgentEnabled);
        ModConfig.COMMAND_EXECUTION_ENABLED.set(commandExecutionEnabled);
        ModConfig.CHAT_BUBBLE_ENABLED.set(chatBubbleEnabled);
        ModConfig.CHAT_BOX_ENABLED.set(chatBoxEnabled);
        ModConfig.SPEC.save();
        ClientConfig.PATH_RENDERING_ENABLED.set(pathRenderingEnabled);
        ClientConfig.SPEC.save();

        NekoWebSocketServer server = NekoWebSocketServerHolder.getServer();
        if (server != null) {
            server.broadcastConfigUpdate();
        }
    }
}
