package com.neko_tlm_bridge.tlm;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.neko_tlm_bridge.ws.NekoWebSocketServer;
import com.google.gson.JsonObject;

public class NekoBridgeTool implements ITool<NekoBridgeTool.Result> {
    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("message").forGetter(Result::message)
            ).apply(instance, Result::new));

    @Override
    public String id() {
        return "neko_bridge";
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Forward the maid's conversation to N.E.K.O AI for processing. Use this when the player wants to talk to the maid through N.E.K.O.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter message = StringParameter.create()
                .setDescription("The message to forward to N.E.K.O AI");
        root.addProperties("message", message);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        NekoWebSocketServer wsServer = NekoWebSocketServerHolder.getServer();
        if (wsServer == null || !wsServer.hasClients()) {
            return callback.addToolResult("N.E.K.O is not connected. Cannot forward message.", toolCallId);
        }

        EntityMaid maid = callback.getMaid();
        JsonObject requestData = new JsonObject();
        requestData.addProperty("maid_id", maid.getStringUUID());
        requestData.addProperty("maid_name", maid.getName().getString());
        requestData.addProperty("message", result.message());
        if (maid.getOwner() != null) {
            requestData.addProperty("owner", maid.getOwner().getName().getString());
        }

        wsServer.broadcastChatMessage(requestData);

        return callback.addToolResult("Message forwarded to N.E.K.O successfully.", toolCallId);
    }

    @Override
    public String invocationSummary(Result result) {
        return "neko_bridge { " + result.message() + " }";
    }

    public record Result(String message) {}
}
