package com.neko_tlm_bridge.ws;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.github.tartaricacid.touhoulittlemaid.api.task.FunctionCallSwitchResult;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.skill.SkillInstance;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.skill.SkillLoader;
import com.neko_tlm_bridge.config.ModConfig;
import com.neko_tlm_bridge.tlm.NekoAttackTargetStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MessageHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("NekoTlmBridge");
    private static final Gson GSON = new Gson();
    private final NekoWebSocketServer server;
    private final ConcurrentLinkedQueue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();
    private MinecraftServer minecraftServer;

    public MessageHandler(NekoWebSocketServer server) {
        this.server = server;
    }

    public void setMinecraftServer(MinecraftServer server) {
        this.minecraftServer = server;
    }

    public void tick() {
        Runnable task;
        while ((task = mainThreadTasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("Error executing main thread task: {}", e.getMessage());
            }
        }
    }

    public void handleGetMaidStatus(WebSocket conn, String requestId) {
        if (minecraftServer == null) {
            sendError(conn, requestId, "Server not ready");
            return;
        }
        mainThreadTasks.add(() -> {
            JsonObject response = new JsonObject();
            response.addProperty("type", Protocol.TYPE_MAID_STATUS);
            if (requestId != null) response.addProperty("request_id", requestId);
            JsonArray maidsArray = new JsonArray();
            for (ServerLevel level : minecraftServer.getAllLevels()) {
                for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, new AABB(level.getWorldBorder().getMinX(), level.getMinBuildHeight(), level.getWorldBorder().getMinZ(), level.getWorldBorder().getMaxX(), level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ()))) {
                    maidsArray.add(serializeMaid(maid));
                }
            }
            JsonObject data = new JsonObject();
            data.add("maids", maidsArray);
            response.add("data", data);
            conn.send(GSON.toJson(response));
        });
    }

    public void handleCommandMaid(WebSocket conn, String requestId, JsonObject json) {
        if (minecraftServer == null) {
            sendError(conn, requestId, "Server not ready");
            return;
        }
        JsonObject data = json.has("data") ? json.getAsJsonObject("data") : new JsonObject();
        String maidId = data.has("maid_id") ? data.get("maid_id").getAsString() : "";
        String command = data.has("command") ? data.get("command").getAsString() : "";
        JsonObject args = data.has("args") ? data.getAsJsonObject("args") : new JsonObject();

        mainThreadTasks.add(() -> {
            EntityMaid maid = findMaidById(maidId);
            if (maid == null) {
                sendError(conn, requestId, "Maid not found: " + maidId);
                return;
            }
            JsonObject resultData = new JsonObject();
            boolean success = executeCommand(maid, command, args, resultData);
            JsonObject response = new JsonObject();
            response.addProperty("type", Protocol.TYPE_COMMAND_RESULT);
            if (requestId != null) response.addProperty("request_id", requestId);
            resultData.addProperty("success", success);
            resultData.addProperty("command", command);
            response.add("data", resultData);
            conn.send(GSON.toJson(response));
        });
    }

    public void handleSendChat(WebSocket conn, String requestId, JsonObject json) {
        if (minecraftServer == null) {
            sendError(conn, requestId, "Server not ready");
            return;
        }
        JsonObject data = json.has("data") ? json.getAsJsonObject("data") : new JsonObject();
        String maidId = data.has("maid_id") ? data.get("maid_id").getAsString() : "";
        String message = data.has("message") ? data.get("message").getAsString() : "";

        mainThreadTasks.add(() -> {
            EntityMaid maid = findMaidById(maidId);
            if (maid == null) {
                sendError(conn, requestId, "Maid not found: " + maidId);
                return;
            }
            String maidName = maid.getName().getString();
            Component chatMessage = Component.literal("[" + maidName + "] " + message);
            minecraftServer.getPlayerList().broadcastSystemMessage(chatMessage, false);

            maid.getChatBubbleManager().addChatBubble(
                    com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData.type2(
                            Component.literal(message)
                    )
            );

            JsonObject response = new JsonObject();
            response.addProperty("type", Protocol.TYPE_CHAT_RESULT);
            if (requestId != null) response.addProperty("request_id", requestId);
            JsonObject resultData = new JsonObject();
            resultData.addProperty("success", true);
            response.add("data", resultData);
            conn.send(GSON.toJson(response));
        });
    }

    public void handleGetGameContext(WebSocket conn, String requestId, JsonObject json) {
        if (minecraftServer == null) {
            sendError(conn, requestId, "Server not ready");
            return;
        }
        JsonObject data = json.has("data") ? json.getAsJsonObject("data") : new JsonObject();
        String category = data.has("category") ? data.get("category").getAsString() : "world";
        String maidId = data.has("maid_id") ? data.get("maid_id").getAsString() : "";

        mainThreadTasks.add(() -> {
            JsonObject response = new JsonObject();
            response.addProperty("type", Protocol.TYPE_GAME_CONTEXT);
            if (requestId != null) response.addProperty("request_id", requestId);
            response.addProperty("category", category);
            JsonObject contextData = new JsonObject();

            EntityMaid maid = maidId.isEmpty() ? findFirstMaid() : findMaidById(maidId);

            switch (category) {
                case "status" -> contextData = collectStatusContext(maid);
                case "world" -> contextData = collectWorldContext();
                case "equipment" -> contextData = collectEquipmentContext(maid);
                case "user" -> contextData = collectUserContext(maid);
                case "effects" -> contextData = collectEffectsContext(maid);
                case "position" -> contextData = collectPositionContext(maid);
                case "nearby_entities" -> contextData = collectNearbyEntitiesContext(maid);
                default -> contextData = collectWorldContext();
            }

            response.add("data", contextData);
            conn.send(GSON.toJson(response));
        });
    }

    private EntityMaid findFirstMaid() {
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            List<EntityMaid> maids = level.getEntitiesOfClass(EntityMaid.class,
                    new AABB(level.getWorldBorder().getMinX(), level.getMinBuildHeight(), level.getWorldBorder().getMinZ(),
                            level.getWorldBorder().getMaxX(), level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ()));
            if (!maids.isEmpty()) {
                return maids.get(0);
            }
        }
        return null;
    }

    private JsonObject collectStatusContext(EntityMaid maid) {
        JsonObject data = new JsonObject();
        if (maid == null) {
            data.addProperty("error", "No maid found");
            return data;
        }
        data.addProperty("health", maid.getHealth());
        data.addProperty("max_health", maid.getMaxHealth());
        IMaidTask task = maid.getTask();
        data.addProperty("task", task != null ? task.getUid().toString() : "");
        data.addProperty("schedule", maid.getSchedule().name());
        data.addProperty("is_following", !maid.isHomeModeEnable());
        data.addProperty("is_sitting", maid.isMaidInSittingPose());
        data.addProperty("name", maid.getName().getString());
        data.addProperty("maid_id", maid.getStringUUID());
        JsonArray availableTasks = new JsonArray();
        for (IMaidTask t : TaskManager.getNotHiddenTaskList(maid)) {
            JsonObject taskObj = new JsonObject();
            taskObj.addProperty("id", t.getUid().toString());
            taskObj.addProperty("name", t.getName().getString());
            availableTasks.add(taskObj);
        }
        data.add("available_tasks", availableTasks);
        return data;
    }

    private JsonObject collectWorldContext() {
        JsonObject data = new JsonObject();
        ServerLevel overworld = minecraftServer.getLevel(Level.OVERWORLD);
        if (overworld != null) {
            long dayTime = overworld.getDayTime();
            data.addProperty("day_time", dayTime);
            data.addProperty("game_time", overworld.getGameTime());
            data.addProperty("is_raining", overworld.isRaining());
            data.addProperty("is_thundering", overworld.isThundering());
            data.addProperty("time_of_day", dayTime % 24000);
            data.addProperty("dimension", Level.OVERWORLD.location().toString());
        }
        JsonArray onlinePlayers = new JsonArray();
        for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
            JsonObject playerObj = new JsonObject();
            playerObj.addProperty("name", player.getName().getString());
            playerObj.addProperty("health", player.getHealth());
            playerObj.addProperty("x", player.getX());
            playerObj.addProperty("y", player.getY());
            playerObj.addProperty("z", player.getZ());
            playerObj.addProperty("dimension", player.level().dimension().location().toString());
            onlinePlayers.add(playerObj);
        }
        data.add("online_players", onlinePlayers);
        return data;
    }

    private JsonObject collectEquipmentContext(EntityMaid maid) {
        JsonObject data = new JsonObject();
        if (maid == null) {
            data.addProperty("error", "No maid found");
            return data;
        }
        data.addProperty("maid_id", maid.getStringUUID());
        data.addProperty("main_hand", maid.getMainHandItem().isEmpty() ? ""
                : BuiltInRegistries.ITEM.getKey(maid.getMainHandItem().getItem()).toString());
        data.addProperty("off_hand", maid.getOffhandItem().isEmpty() ? ""
                : BuiltInRegistries.ITEM.getKey(maid.getOffhandItem().getItem()).toString());

        JsonArray armorArray = new JsonArray();
        var armorInv = maid.getArmorInvWrapper();
        for (int i = 0; i < armorInv.getSlots(); i++) {
            ItemStack stack = armorInv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                JsonObject armorItem = new JsonObject();
                armorItem.addProperty("slot", i);
                armorItem.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                armorArray.add(armorItem);
            }
        }
        data.add("armor", armorArray);

        JsonArray inventoryArray = new JsonArray();
        ItemStackHandler maidInv = maid.getMaidInv();
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                JsonObject invItem = new JsonObject();
                invItem.addProperty("slot", i);
                invItem.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                invItem.addProperty("count", stack.getCount());
                inventoryArray.add(invItem);
            }
        }
        data.add("inventory", inventoryArray);
        return data;
    }

    private JsonObject collectUserContext(EntityMaid maid) {
        JsonObject data = new JsonObject();
        if (maid == null) {
            data.addProperty("error", "No maid found");
            return data;
        }
        LivingEntity owner = maid.getOwner();
        if (owner instanceof ServerPlayer player) {
            data.addProperty("name", player.getName().getString());
            data.addProperty("health", player.getHealth());
            data.addProperty("max_health", player.getMaxHealth());
            data.addProperty("main_hand", player.getMainHandItem().isEmpty() ? ""
                    : BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());
            JsonObject pos = new JsonObject();
            pos.addProperty("x", player.getX());
            pos.addProperty("y", player.getY());
            pos.addProperty("z", player.getZ());
            data.add("position", pos);
            data.addProperty("dimension", player.level().dimension().location().toString());
        } else {
            data.addProperty("error", "Owner not online or not found");
        }
        return data;
    }

    private JsonObject collectEffectsContext(EntityMaid maid) {
        JsonObject data = new JsonObject();
        if (maid == null) {
            data.addProperty("error", "No maid found");
            return data;
        }
        data.addProperty("maid_id", maid.getStringUUID());
        JsonArray effectsArray = new JsonArray();
        for (var effect : maid.getActiveEffects()) {
            JsonObject effectObj = new JsonObject();
            effectObj.addProperty("effect", effect.getEffect().value().getDescriptionId());
            effectObj.addProperty("amplifier", effect.getAmplifier());
            effectObj.addProperty("duration", effect.getDuration());
            effectsArray.add(effectObj);
        }
        data.add("effects", effectsArray);
        return data;
    }

    private JsonObject collectPositionContext(EntityMaid maid) {
        JsonObject data = new JsonObject();
        if (maid == null) {
            data.addProperty("error", "No maid found");
            return data;
        }
        JsonObject maidPos = new JsonObject();
        maidPos.addProperty("x", maid.getX());
        maidPos.addProperty("y", maid.getY());
        maidPos.addProperty("z", maid.getZ());
        data.add("maid_position", maidPos);
        data.addProperty("maid_dimension", maid.level().dimension().location().toString());

        LivingEntity owner = maid.getOwner();
        if (owner != null) {
            JsonObject ownerPos = new JsonObject();
            ownerPos.addProperty("x", owner.getX());
            ownerPos.addProperty("y", owner.getY());
            ownerPos.addProperty("z", owner.getZ());
            data.add("owner_position", ownerPos);
            data.addProperty("owner_dimension", owner.level().dimension().location().toString());
            if (maid.level().dimension().equals(owner.level().dimension())) {
                data.addProperty("distance", maid.distanceTo(owner));
            } else {
                data.addProperty("distance", -1);
            }
        }
        return data;
    }

    private JsonObject collectNearbyEntitiesContext(EntityMaid maid) {
        JsonObject data = new JsonObject();
        if (maid == null) {
            data.addProperty("error", "No maid found");
            return data;
        }
        data.addProperty("maid_id", maid.getStringUUID());
        double radius = 32.0;
        AABB searchBox = new AABB(
                maid.getX() - radius, maid.getY() - radius, maid.getZ() - radius,
                maid.getX() + radius, maid.getY() + radius, maid.getZ() + radius);
        List<LivingEntity> nearby = maid.level().getEntitiesOfClass(LivingEntity.class, searchBox);
        JsonArray entitiesArray = new JsonArray();
        int count = 0;
        for (LivingEntity entity : nearby) {
            if (entity == maid) continue;
            if (count >= 20) break;
            JsonObject entityObj = new JsonObject();
            entityObj.addProperty("entity_id", entity.getStringUUID());
            entityObj.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            entityObj.addProperty("name", entity.getName().getString());
            entityObj.addProperty("health", entity.getHealth());
            entityObj.addProperty("x", entity.getX());
            entityObj.addProperty("y", entity.getY());
            entityObj.addProperty("z", entity.getZ());
            entityObj.addProperty("distance", maid.distanceTo(entity));
            entitiesArray.add(entityObj);
            count++;
        }
        data.add("entities", entitiesArray);
        data.addProperty("total_nearby", count);
        return data;
    }

    private EntityMaid findMaidById(String maidId) {
        try {
            UUID uuid = UUID.fromString(maidId);
            for (ServerLevel level : minecraftServer.getAllLevels()) {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof EntityMaid maid) {
                    return maid;
                }
            }
        } catch (IllegalArgumentException e) {
            for (ServerLevel level : minecraftServer.getAllLevels()) {
                for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, new AABB(level.getWorldBorder().getMinX(), level.getMinBuildHeight(), level.getWorldBorder().getMinZ(), level.getWorldBorder().getMaxX(), level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ()))) {
                    if (maid.getStringUUID().equals(maidId)) {
                        return maid;
                    }
                }
            }
        }
        return null;
    }

    private boolean executeCommand(EntityMaid maid, String command, JsonObject args, JsonObject resultData) {
        try {
            switch (command) {
                case "switch_follow" -> {
                    boolean follow = args.has("follow") && args.get("follow").getAsBoolean();
                    boolean isHome = maid.isHomeModeEnable();
                    if (follow) {
                        boolean wasSitting = maid.isMaidInSittingPose();
                        if (wasSitting) {
                            maid.setInSittingPose(false);
                        }
                        if (!isHome) {
                            resultData.addProperty("state", wasSitting ? "following_stood_up" : "already_following");
                            return true;
                        }
                        maid.restrictTo(net.minecraft.core.BlockPos.ZERO,
                                com.github.tartaricacid.touhoulittlemaid.config.subconfig.MaidConfig.MAID_NON_HOME_RANGE.get());
                        maid.setHomeModeEnable(false);
                        resultData.addProperty("state", "following");
                        return true;
                    } else {
                        if (isHome) {
                            resultData.addProperty("state", "already_stopped");
                            return true;
                        }
                        maid.getSchedulePos().setHomeModeEnable(maid, maid.blockPosition());
                        maid.setHomeModeEnable(true);
                        resultData.addProperty("state", "stopped");
                        return true;
                    }
                }
                case "switch_sit" -> {
                    boolean sit = args.has("sit") && args.get("sit").getAsBoolean();
                    boolean isSitting = maid.isMaidInSittingPose();
                    if (sit) {
                        if (isSitting) {
                            resultData.addProperty("state", "already_sitting");
                            return true;
                        }
                        maid.setInSittingPose(true);
                        resultData.addProperty("state", "sitting");
                        return true;
                    } else {
                        if (!isSitting) {
                            resultData.addProperty("state", "already_standing");
                            return true;
                        }
                        maid.setInSittingPose(false);
                        resultData.addProperty("state", "standing");
                        return true;
                    }
                }
                case "switch_task" -> {
                    if (args.has("task")) {
                        String taskName = args.get("task").getAsString();
                        try {
                            ResourceLocation taskRL = ResourceLocation.parse(taskName);
                            var taskOpt = TaskManager.findTask(taskRL);
                            if (taskOpt.isPresent()) {
                                IMaidTask task = taskOpt.get();
                                FunctionCallSwitchResult result = task.onFunctionCallSwitch(maid);
                                maid.setTask(task);
                                resultData.addProperty("switch_result", result.name());
                                return true;
                            }
                            resultData.addProperty("error", "Task not found: " + taskName);
                            JsonArray taskList = new JsonArray();
                            for (IMaidTask t : TaskManager.getNotHiddenTaskList(maid)) {
                                taskList.add(t.getUid().toString());
                            }
                            resultData.add("available_tasks", taskList);
                            return false;
                        } catch (Exception e) {
                            LOGGER.error("Error switching task: {}", e.getMessage());
                            resultData.addProperty("error", "Error switching task: " + e.getMessage());
                            return false;
                        }
                    }
                    resultData.addProperty("error", "Missing required argument: task");
                    return false;
                }
                case "switch_schedule" -> {
                    if (args.has("schedule")) {
                        String schedule = args.get("schedule").getAsString();
                        try {
                            MaidSchedule maidSchedule = MaidSchedule.valueOf(schedule.toUpperCase());
                            maid.setSchedule(maidSchedule);
                            resultData.addProperty("schedule", maidSchedule.name());
                            return true;
                        } catch (IllegalArgumentException e) {
                            LOGGER.error("Invalid schedule: {}", schedule);
                            resultData.addProperty("error", "Invalid schedule: " + schedule + ". Valid values: DAY, NIGHT, ALL");
                            return false;
                        }
                    }
                    resultData.addProperty("error", "Missing required argument: schedule");
                    return false;
                }
                case "set_home" -> {
                    resultData.addProperty("error", "set_home command is not implemented yet");
                    return false;
                }
                case "equip_item" -> {
                    if (args.has("slot")) {
                        try {
                            int slot = args.get("slot").getAsInt();
                            var backpack = maid.getAvailableBackpackInv();
                            if (slot < 0 || slot >= backpack.getSlots()) {
                                resultData.addProperty("error", "Invalid slot: " + slot + ". Valid range: 0-" + (backpack.getSlots() - 1));
                                return false;
                            }
                            ItemStack targetItem = backpack.getStackInSlot(slot);
                            if (targetItem.isEmpty()) {
                                resultData.addProperty("error", "Slot " + slot + " is empty");
                                return false;
                            }
                            ItemStack mainHandItem = maid.getMainHandItem();
                            if (!mainHandItem.isEmpty()) {
                                backpack.setStackInSlot(slot, mainHandItem);
                            } else {
                                backpack.setStackInSlot(slot, ItemStack.EMPTY);
                            }
                            maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, targetItem.copy());
                            resultData.addProperty("equipped_item", BuiltInRegistries.ITEM.getKey(targetItem.getItem()).toString());
                            resultData.addProperty("slot", slot);
                            return true;
                        } catch (NumberFormatException e) {
                            resultData.addProperty("error", "Invalid slot number");
                            return false;
                        }
                    }
                    if (args.has("item")) {
                        String itemId = args.get("item").getAsString();
                        try {
                            ResourceLocation itemRL = ResourceLocation.parse(itemId);
                            var itemOpt = BuiltInRegistries.ITEM.getOptional(itemRL);
                            if (itemOpt.isEmpty()) {
                                resultData.addProperty("error", "Item not found: " + itemId);
                                return false;
                            }
                            var backpack = maid.getAvailableBackpackInv();
                            int foundSlot = -1;
                            for (int i = 0; i < backpack.getSlots(); i++) {
                                ItemStack stack = backpack.getStackInSlot(i);
                                if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemRL)) {
                                    foundSlot = i;
                                    break;
                                }
                            }
                            if (foundSlot < 0) {
                                resultData.addProperty("error", "Item " + itemId + " not found in maid inventory");
                                return false;
                            }
                            ItemStack targetItem = backpack.getStackInSlot(foundSlot);
                            ItemStack mainHandItem = maid.getMainHandItem();
                            if (!mainHandItem.isEmpty()) {
                                backpack.setStackInSlot(foundSlot, mainHandItem);
                            } else {
                                backpack.setStackInSlot(foundSlot, ItemStack.EMPTY);
                            }
                            maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, targetItem.copy());
                            resultData.addProperty("equipped_item", itemId);
                            resultData.addProperty("slot", foundSlot);
                            return true;
                        } catch (Exception e) {
                            resultData.addProperty("error", "Error equipping item: " + e.getMessage());
                            return false;
                        }
                    }
                    resultData.addProperty("error", "Missing required argument: slot (int) or item (item ID string)");
                    return false;
                }
                default -> {
                    resultData.addProperty("error", "Unknown command: " + command);
                    return false;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error executing command {}: {}", command, e.getMessage());
            return false;
        }
    }

    private JsonObject serializeMaid(EntityMaid maid) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", maid.getStringUUID());
        obj.addProperty("name", maid.getName().getString());
        obj.addProperty("health", maid.getHealth());
        obj.addProperty("max_health", maid.getMaxHealth());
        JsonObject pos = new JsonObject();
        pos.addProperty("x", maid.getX());
        pos.addProperty("y", maid.getY());
        pos.addProperty("z", maid.getZ());
        obj.add("position", pos);
        obj.addProperty("dimension", maid.level().dimension().location().toString());
        obj.addProperty("is_sitting", maid.isMaidInSittingPose());
        obj.addProperty("is_following", !maid.isHomeModeEnable());
        IMaidTask currentTask = maid.getTask();
        obj.addProperty("task", currentTask != null ? currentTask.getUid().toString() : "");
        if (maid.getOwner() != null) {
            obj.addProperty("owner", maid.getOwner().getName().getString());
        }
        String mainHand = maid.getMainHandItem().isEmpty() ? ""
                : BuiltInRegistries.ITEM.getKey(maid.getMainHandItem().getItem()).toString();
        String offHand = maid.getOffhandItem().isEmpty() ? ""
                : BuiltInRegistries.ITEM.getKey(maid.getOffhandItem().getItem()).toString();
        obj.addProperty("main_hand_item", mainHand);
        obj.addProperty("off_hand_item", offHand);
        JsonArray availableTasks = new JsonArray();
        for (IMaidTask t : TaskManager.getNotHiddenTaskList(maid)) {
            JsonObject taskObj = new JsonObject();
            taskObj.addProperty("id", t.getUid().toString());
            taskObj.addProperty("name", t.getName().getString());
            availableTasks.add(taskObj);
        }
        obj.add("available_tasks", availableTasks);
        return obj;
    }

    public void handleExecuteCommand(WebSocket conn, String requestId, JsonObject json) {
        if (minecraftServer == null) {
            sendError(conn, requestId, "Server not ready");
            return;
        }
        if (!ModConfig.COMMAND_EXECUTION_ENABLED.get()) {
            sendError(conn, requestId, "Command execution is disabled in config");
            return;
        }
        JsonObject data = json.has("data") ? json.getAsJsonObject("data") : new JsonObject();
        String command = data.has("command") ? data.get("command").getAsString() : "";

        if (command.isEmpty()) {
            sendError(conn, requestId, "Command is empty");
            return;
        }

        mainThreadTasks.add(() -> {
            NekoWebSocketServer wsServer = com.neko_tlm_bridge.tlm.NekoWebSocketServerHolder.getServer();
            if (wsServer == null) {
                sendError(conn, requestId, "WebSocket server not available");
                return;
            }
            PendingCommandManager manager = wsServer.getPendingCommandManager();
            String pendingId = manager.addPendingCommand(requestId, command, conn);
            NekoCommand.broadcastCommandRequest(minecraftServer, pendingId, command);
            LOGGER.info("Command execution request queued: {} (pending_id={}, request_id={})", command, pendingId, requestId);
        });
    }

    public void handleAttackTarget(WebSocket conn, String requestId, JsonObject json) {
        if (minecraftServer == null) {
            sendError(conn, requestId, "Server not ready");
            return;
        }
        JsonObject data = json.has("data") ? json.getAsJsonObject("data") : new JsonObject();
        String maidId = data.has("maid_id") ? data.get("maid_id").getAsString() : "";
        String targetEntityId = data.has("target_entity_id") ? data.get("target_entity_id").getAsString() : "";
        JsonArray targetEntityIds = data.has("target_entity_ids") ? data.getAsJsonArray("target_entity_ids") : new JsonArray();

        if (maidId.isEmpty()) {
            sendError(conn, requestId, "maid_id is required");
            return;
        }
        if (targetEntityId.isEmpty() && targetEntityIds.isEmpty()) {
            sendError(conn, requestId, "target_entity_id or target_entity_ids is required");
            return;
        }

        mainThreadTasks.add(() -> {
            EntityMaid maid = findMaidById(maidId);
            if (maid == null) {
                sendError(conn, requestId, "Maid not found: " + maidId);
                return;
            }

            if (maid.isMaidInSittingPose()) {
                maid.setInSittingPose(false);
                LOGGER.info("Attack target: maid {} was sitting, stood up", maid.getName().getString());
            }

            if (maid.isHomeModeEnable()) {
                maid.restrictTo(net.minecraft.core.BlockPos.ZERO,
                        com.github.tartaricacid.touhoulittlemaid.config.subconfig.MaidConfig.MAID_NON_HOME_RANGE.get());
                maid.setHomeModeEnable(false);
                LOGGER.info("Attack target: maid {} was in home mode, disabled home mode", maid.getName().getString());
            }

            List<NekoAttackTargetStore.TargetEntry> allEntries = new java.util.ArrayList<>();
            LivingEntity firstTarget = null;

            if (!targetEntityId.isEmpty()) {
                UUID targetUUID;
                try {
                    targetUUID = UUID.fromString(targetEntityId);
                } catch (IllegalArgumentException e) {
                    sendError(conn, requestId, "Invalid target_entity_id: " + targetEntityId);
                    return;
                }
                LivingEntity target = null;
                for (ServerLevel level : minecraftServer.getAllLevels()) {
                    Entity entity = level.getEntity(targetUUID);
                    if (entity instanceof LivingEntity living && living.isAlive()) {
                        target = living;
                        break;
                    }
                }
                if (target == null) {
                    sendError(conn, requestId, "Target entity not found or not alive: " + targetEntityId);
                    return;
                }
                allEntries.add(new NekoAttackTargetStore.TargetEntry(targetUUID, target.getName().getString()));
                firstTarget = target;
            }

            if (!targetEntityIds.isEmpty()) {
                for (int i = 0; i < targetEntityIds.size(); i++) {
                    String eid = targetEntityIds.get(i).getAsString();
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(eid);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    LivingEntity target = null;
                    for (ServerLevel level : minecraftServer.getAllLevels()) {
                        Entity entity = level.getEntity(uuid);
                        if (entity instanceof LivingEntity living && living.isAlive()) {
                            target = living;
                            break;
                        }
                    }
                    if (target != null) {
                        allEntries.add(new NekoAttackTargetStore.TargetEntry(uuid, target.getName().getString()));
                        if (firstTarget == null) {
                            firstTarget = target;
                        }
                    }
                }
            }

            if (allEntries.isEmpty()) {
                sendError(conn, requestId, "No valid target entities found");
                return;
            }

            if (allEntries.size() == 1) {
                NekoAttackTargetStore.setTarget(maid.getUUID(), allEntries.get(0).targetEntityId, allEntries.get(0).targetName);
            } else {
                NekoAttackTargetStore.setTargets(maid.getUUID(), allEntries);
            }

            try {
                IMaidTask currentTask = maid.getTask();
                String currentTaskId = currentTask != null ? currentTask.getUid().toString() : "";
                boolean isAttackTask = currentTaskId.endsWith(":attack")
                        || currentTaskId.endsWith(":ranged_attack")
                        || currentTaskId.endsWith(":crossbow_attack")
                        || currentTaskId.endsWith(":danmaku_attack")
                        || currentTaskId.endsWith(":trident_attack");

                if (!isAttackTask) {
                    String[] attackTaskIds = {
                            "touhou_little_maid:attack",
                            "touhou_little_maid:ranged_attack",
                            "touhou_little_maid:crossbow_attack",
                            "touhou_little_maid:danmaku_attack",
                            "touhou_little_maid:trident_attack"
                    };
                    IMaidTask foundTask = null;
                    for (String taskId : attackTaskIds) {
                        ResourceLocation taskRL = ResourceLocation.parse(taskId);
                        var taskOpt = TaskManager.findTask(taskRL);
                        if (taskOpt.isPresent()) {
                            IMaidTask t = taskOpt.get();
                            if (t.isEnable(maid)) {
                                foundTask = t;
                                break;
                            }
                        }
                    }
                    if (foundTask != null) {
                        foundTask.onFunctionCallSwitch(maid);
                        maid.setTask(foundTask);
                        LOGGER.info("Attack target: switched maid {} to attack task {}", maid.getName().getString(), foundTask.getUid());
                    } else {
                        LOGGER.warn("Attack target: no attack task available for maid {}", maid.getName().getString());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Attack target: failed to switch attack task: {}", e.getMessage());
            }

            maid.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, firstTarget);
            maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new net.minecraft.world.entity.ai.behavior.EntityTracker(firstTarget, true));

            JsonObject response = new JsonObject();
            response.addProperty("type", Protocol.TYPE_ATTACK_TARGET_RESULT);
            if (requestId != null) response.addProperty("request_id", requestId);
            JsonObject resultData = new JsonObject();
            resultData.addProperty("success", true);
            resultData.addProperty("maid_id", maidId);
            resultData.addProperty("target_count", allEntries.size());
            resultData.addProperty("target_name", firstTarget.getName().getString());
            JsonArray targetNames = new JsonArray();
            for (NekoAttackTargetStore.TargetEntry e : allEntries) {
                targetNames.add(e.targetName);
            }
            resultData.add("target_names", targetNames);
            response.add("data", resultData);
            conn.send(GSON.toJson(response));

            LOGGER.info("Set attack target for maid {} -> {} target(s): {}", maid.getName().getString(), allEntries.size(),
                    allEntries.stream().map(e -> e.targetName).reduce((a, b) -> a + ", " + b).orElse(""));
        });
    }

    public void handleGetConfig(WebSocket conn, String requestId) {
        JsonObject response = new JsonObject();
        response.addProperty("type", Protocol.TYPE_CONFIG);
        if (requestId != null) response.addProperty("request_id", requestId);
        JsonObject data = new JsonObject();
        data.addProperty("neko_mode_enabled", ModConfig.NEKO_MODE_ENABLED.get());
        data.addProperty("event_push_enabled", ModConfig.EVENT_PUSH_ENABLED.get());
        data.addProperty("command_execution_enabled", ModConfig.COMMAND_EXECUTION_ENABLED.get());
        data.addProperty("websocket_port", ModConfig.WEBSOCKET_PORT.get());
        response.add("data", data);
        conn.send(GSON.toJson(response));
    }

    public void handleUseSkill(WebSocket conn, String requestId, JsonObject json) {
        if (minecraftServer == null) {
            sendError(conn, requestId, "Server not ready");
            return;
        }
        JsonObject data = json.has("data") ? json.getAsJsonObject("data") : new JsonObject();
        String skillName = data.has("skill_name") ? data.get("skill_name").getAsString() : "";
        String maidId = data.has("maid_id") ? data.get("maid_id").getAsString() : "";

        mainThreadTasks.add(() -> {
            JsonObject response = new JsonObject();
            response.addProperty("type", Protocol.TYPE_SKILL_RESULT);
            if (requestId != null) response.addProperty("request_id", requestId);
            JsonObject resultData = new JsonObject();

            if (skillName.isEmpty()) {
                resultData.addProperty("success", false);
                resultData.addProperty("error", "skill_name is required");
                Map<String, SkillInstance> allSkills = SkillLoader.getAllSkills();
                JsonArray availableArray = new JsonArray();
                for (var entry : allSkills.entrySet()) {
                    availableArray.add(entry.getKey());
                }
                resultData.add("available_skills", availableArray);
                response.add("data", resultData);
                conn.send(GSON.toJson(response));
                return;
            }

            EntityMaid maid = maidId.isEmpty() ? findFirstMaid() : findMaidById(maidId);
            if (maid == null) {
                resultData.addProperty("success", false);
                resultData.addProperty("error", "Maid not found");
                response.add("data", resultData);
                conn.send(GSON.toJson(response));
                return;
            }

            SkillInstance skill = SkillLoader.getSkill(skillName);
            if (skill != null) {
                resultData.addProperty("success", true);
                resultData.addProperty("skill_name", skillName);
                resultData.addProperty("description", skill.description());
                resultData.addProperty("body", skill.body());
                if (skill.references() != null && !skill.references().isEmpty()) {
                    JsonObject refs = new JsonObject();
                    for (var entry : skill.references().entrySet()) {
                        refs.addProperty(entry.getKey(), entry.getValue());
                    }
                    resultData.add("references", refs);
                }
            } else {
                resultData.addProperty("success", false);
                resultData.addProperty("error", "Skill not found: " + skillName);
                Map<String, SkillInstance> allSkills = SkillLoader.getAllSkills();
                JsonArray availableArray = new JsonArray();
                for (var entry : allSkills.entrySet()) {
                    availableArray.add(entry.getKey());
                }
                resultData.add("available_skills", availableArray);
            }

            response.add("data", resultData);
            conn.send(GSON.toJson(response));
        });
    }

    private void sendError(WebSocket conn, String requestId, String errorMessage) {
        JsonObject error = new JsonObject();
        error.addProperty("type", Protocol.TYPE_ERROR);
        if (requestId != null) error.addProperty("request_id", requestId);
        JsonObject data = new JsonObject();
        data.addProperty("message", errorMessage);
        error.add("data", data);
        if (conn != null && conn.isOpen()) {
            conn.send(GSON.toJson(error));
        }
    }
}
