package com.neko_tlm_bridge.ws.handler;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.ws.Protocol;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.java_websocket.WebSocket;

import java.util.List;

/** 游戏上下文查询处理器 — 处理 get_game_context 请求，支持 status/world/equipment/user/effects/position/nearby_entities/awareness 等 category */
public class GameContextHandler implements MessageHandlerInterface {
    private final MinecraftServer server;

    public GameContextHandler(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public JsonObject handle(JsonObject request, WebSocket conn) {
        String requestId = request.has("request_id") ? request.get("request_id").getAsString() : null;
        if (server == null) {
            return createErrorResponse(requestId, "Server not ready");
        }
        JsonObject data = request.has("data") ? request.getAsJsonObject("data") : new JsonObject();
        String category = data.has("category") ? data.get("category").getAsString() : "world";
        String maidId = data.has("maid_id") ? data.get("maid_id").getAsString() : "";

        JsonObject response = new JsonObject();
        response.addProperty("type", Protocol.TYPE_GAME_CONTEXT);
        if (requestId != null) response.addProperty("request_id", requestId);
        response.addProperty("category", category);
        JsonObject contextData;

        EntityMaid maid = maidId.isEmpty() ? MaidHelper.findFirstMaid(server) : MaidHelper.findMaidById(server, maidId);

        switch (category) {
            case "status" -> contextData = collectStatusContext(maid);
            case "world" -> contextData = collectWorldContext(maid);
            case "equipment" -> contextData = collectEquipmentContext(maid);
            case "user" -> contextData = collectUserContext(maid);
            case "effects" -> contextData = collectEffectsContext(maid);
            case "position" -> contextData = collectPositionContext(maid, maidId);
            case "nearby_entities" -> contextData = collectNearbyEntitiesContext(maid);
            case "awareness" -> contextData = collectAwarenessContext(maid);
            default -> {
                return createErrorResponse(requestId, "Unknown category: " + category);
            }
        }

        response.add("data", contextData);
        return response;
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

    private JsonObject collectWorldContext(EntityMaid maid) {
        JsonObject data = new JsonObject();
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
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
        LivingEntity owner = maid == null ? null : maid.getOwner();
        if (owner instanceof ServerPlayer player) {
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
        ItemStack mainHand = maid.getMainHandItem();
        data.addProperty("main_hand", mainHand.isEmpty() ? ""
                : BuiltInRegistries.ITEM.getKey(mainHand.getItem()).toString());
        data.addProperty("off_hand", maid.getOffhandItem().isEmpty() ? ""
                : BuiltInRegistries.ITEM.getKey(maid.getOffhandItem().getItem()).toString());
        data.add("combat_task_compatibility",
                collectCombatTaskCompatibility(
                        maid, mainHand, TaskManager.getNotHiddenTaskList(maid)));

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

    static JsonObject collectCombatTaskCompatibility(
            EntityMaid maid, ItemStack mainHand, Iterable<IMaidTask> tasks) {
        JsonObject compatibility = new JsonObject();
        for (IMaidTask task : tasks) {
            if (task instanceof IAttackTask attackTask) {
                compatibility.addProperty(
                        task.getUid().toString(), attackTask.isWeapon(maid, mainHand));
            }
        }
        return compatibility;
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
            data.addProperty("on_fire", player.isOnFire());
            data.addProperty("is_drowning", player.getAirSupply() < player.getMaxAirSupply() * 0.4);
            data.addProperty("air_supply", player.getAirSupply());
            data.addProperty("max_air", player.getMaxAirSupply());
            data.addProperty("food_level", player.getFoodData().getFoodLevel());
            data.addProperty("saturation", player.getFoodData().getSaturationLevel());
            JsonArray effectsArray = new JsonArray();
            for (var effect : player.getActiveEffects()) {
                JsonObject effectObj = new JsonObject();
                effectObj.addProperty("effect", effect.getEffect().value().getDescriptionId());
                effectObj.addProperty("amplifier", effect.getAmplifier());
                effectObj.addProperty("duration", effect.getDuration());
                effectsArray.add(effectObj);
            }
            data.add("effects", effectsArray);
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

    private JsonObject collectPositionContext(EntityMaid maid, String maidId) {
        JsonObject data = new JsonObject();
        if (maid == null) {
            MaidHelper.UnloadedMaid unloaded = MaidHelper.findUnloadedMaid(server, maidId);
            if (unloaded == null) {
                data.addProperty("error", "No maid found");
                return data;
            }
            data.addProperty("maid_loaded", false);
            data.addProperty("maid_id", unloaded.info().getEntityId().toString());
            JsonObject maidPos = new JsonObject();
            maidPos.addProperty("x", unloaded.info().getChunkPos().getX());
            maidPos.addProperty("y", unloaded.info().getChunkPos().getY());
            maidPos.addProperty("z", unloaded.info().getChunkPos().getZ());
            data.add("maid_position", maidPos);
            data.addProperty("maid_dimension", unloaded.info().getDimension());
            data.addProperty("maid_last_seen_ms", unloaded.info().getTimestamp());
            addOwnerRange(data, unloaded.info().getChunkPos(),
                    unloaded.info().getDimension(), unloaded.owner());
            // 实体不存在于所有 ServerLevel 时无法运行 TLM 跟随任务，
            // 不论最后记录的数值距离是多少。
            data.addProperty("within_owner_simulation_distance", false);
            return data;
        }
        data.addProperty("maid_loaded", true);
        data.addProperty("maid_id", maid.getStringUUID());
        JsonObject maidPos = new JsonObject();
        maidPos.addProperty("x", maid.getX());
        maidPos.addProperty("y", maid.getY());
        maidPos.addProperty("z", maid.getZ());
        data.add("maid_position", maidPos);
        data.addProperty("maid_dimension", maid.level().dimension().location().toString());
        data.addProperty("light_level", maid.level().getMaxLocalRawBrightness(maid.blockPosition()));
        data.addProperty("is_underground", maid.getY() < maid.level().getSeaLevel() - 1
                && !maid.level().canSeeSky(maid.blockPosition()));

        LivingEntity owner = maid.getOwner();
        if (owner != null) {
            addOwnerRange(data, maid.blockPosition(),
                    maid.level().dimension().location().toString(), owner);
            if (maid.level().dimension().equals(owner.level().dimension())) {
                data.addProperty("distance", maid.distanceTo(owner));
            }
        }
        return data;
    }

    private void addOwnerRange(
            JsonObject data, net.minecraft.core.BlockPos maidPosition,
            String maidDimension, LivingEntity owner) {
        JsonObject ownerPos = new JsonObject();
        ownerPos.addProperty("x", owner.getX());
        ownerPos.addProperty("y", owner.getY());
        ownerPos.addProperty("z", owner.getZ());
        data.add("owner_position", ownerPos);
        String ownerDimension = owner.level().dimension().location().toString();
        data.addProperty("owner_dimension", ownerDimension);
        if (!maidDimension.equals(ownerDimension)) {
            data.addProperty("distance", -1);
            data.addProperty("within_owner_simulation_distance", false);
            return;
        }
        ChunkPos maidChunk = new ChunkPos(maidPosition);
        ChunkPos ownerChunk = new ChunkPos(owner.blockPosition());
        int chunkDistance = Math.max(
                Math.abs(maidChunk.x - ownerChunk.x),
                Math.abs(maidChunk.z - ownerChunk.z));
        int simulationDistance = server.getPlayerList().getSimulationDistance();
        double dx = maidPosition.getX() + 0.5D - owner.getX();
        double dy = maidPosition.getY() - owner.getY();
        double dz = maidPosition.getZ() + 0.5D - owner.getZ();
        data.addProperty("distance", Math.sqrt(dx * dx + dy * dy + dz * dz));
        data.addProperty("owner_chunk_distance", chunkDistance);
        data.addProperty("server_simulation_distance", simulationDistance);
        data.addProperty("within_owner_simulation_distance",
                chunkDistance <= simulationDistance);
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
            if (entity instanceof Player
                    && (maid.getOwner() == null
                    || !maid.getOwner().getUUID().equals(entity.getUUID()))) {
                continue;
            }
            if (count >= 20) break;
            JsonObject entityObj = new JsonObject();
            entityObj.addProperty("entity_id", entity.getStringUUID());
            entityObj.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            entityObj.addProperty("name", entity.getName().getString());
            entityObj.addProperty("health", entity.getHealth());
            entityObj.addProperty("hostile", isHostileEntity(entity));
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

    private JsonObject collectAwarenessContext(EntityMaid maid) {
        JsonObject data = new JsonObject();
        if (maid == null) {
            data.addProperty("error", "No maid found");
            return data;
        }

        data.addProperty("maid_health", maid.getHealth());
        data.addProperty("maid_max_health", maid.getMaxHealth());

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld != null) {
            long dayTime = overworld.getDayTime();
            data.addProperty("is_raining", overworld.isRaining());
            data.addProperty("is_thundering", overworld.isThundering());
            data.addProperty("time_of_day", dayTime % 24000);
        }

        data.addProperty("maid_light_level", maid.level().getMaxLocalRawBrightness(maid.blockPosition()));
        data.addProperty("maid_is_underground", maid.getY() < maid.level().getSeaLevel() - 1
                && !maid.level().canSeeSky(maid.blockPosition()));

        LivingEntity owner = maid.getOwner();
        if (owner instanceof ServerPlayer player) {
            data.addProperty("player_health", player.getHealth());
            data.addProperty("player_max_health", player.getMaxHealth());
            data.addProperty("player_on_fire", player.isOnFire());
            data.addProperty("player_is_drowning", player.getAirSupply() < player.getMaxAirSupply() * 0.4);
            data.addProperty("player_food_level", player.getFoodData().getFoodLevel());
            data.addProperty("player_saturation", player.getFoodData().getSaturationLevel());
            data.addProperty("player_dimension", player.level().dimension().location().toString());

            data.addProperty("player_x", player.getX());
            data.addProperty("player_y", player.getY());
            data.addProperty("player_z", player.getZ());
            data.addProperty("maid_player_distance", maid.distanceTo(player));
            data.addProperty("player_light_level", player.level().getMaxLocalRawBrightness(player.blockPosition()));
            data.addProperty("player_is_underground", player.getY() < player.level().getSeaLevel() - 1
                    && !player.level().canSeeSky(player.blockPosition()));

            ItemStack heldItem = player.getMainHandItem();
            if (!heldItem.isEmpty()) {
                data.addProperty("player_held_item", BuiltInRegistries.ITEM.getKey(heldItem.getItem()).toString());
                data.addProperty("player_held_item_count", heldItem.getCount());
            } else {
                data.addProperty("player_held_item", "");
                data.addProperty("player_held_item_count", 0);
            }

            data.addProperty("player_experience_level", player.experienceLevel);
            data.addProperty("player_experience_progress", player.experienceProgress);

            JsonArray equipmentArray = new JsonArray();
            ItemStack[] playerEquipment = {
                player.getMainHandItem(),
                player.getOffhandItem(),
                player.getItemBySlot(EquipmentSlot.HEAD),
                player.getItemBySlot(EquipmentSlot.CHEST),
                player.getItemBySlot(EquipmentSlot.LEGS),
                player.getItemBySlot(EquipmentSlot.FEET)
            };
            String[] equipmentSlots = {"main_hand", "off_hand", "head", "chest", "legs", "feet"};
            for (int i = 0; i < playerEquipment.length; i++) {
                ItemStack stack = playerEquipment[i];
                if (!stack.isEmpty() && stack.isDamageableItem()) {
                    JsonObject equip = new JsonObject();
                    equip.addProperty("slot", equipmentSlots[i]);
                    equip.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    equip.addProperty("durability", stack.getMaxDamage() - stack.getDamageValue());
                    equip.addProperty("max_durability", stack.getMaxDamage());
                    double ratio = (double) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage();
                    equip.addProperty("durability_ratio", Math.round(ratio * 100.0) / 100.0);
                    equipmentArray.add(equip);
                }
            }
            data.add("player_equipment_durability", equipmentArray);
        }

        JsonArray structuresArray = new JsonArray();
        try {
            ServerLevel serverLevel = (ServerLevel) maid.level();
            net.minecraft.core.BlockPos maidPos = maid.blockPosition();
            ChunkPos maidChunk = new ChunkPos(maidPos);
            int chunkRadius = 4;
            java.util.Set<String> seen = new java.util.HashSet<>();
            var structureRegistry = serverLevel.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            for (int cx = maidChunk.x - chunkRadius; cx <= maidChunk.x + chunkRadius; cx++) {
                for (int cz = maidChunk.z - chunkRadius; cz <= maidChunk.z + chunkRadius; cz++) {
                    ChunkPos cp = new ChunkPos(cx, cz);
                    var starts = serverLevel.structureManager().startsForStructure(cp, s -> true);
                    for (var start : starts) {
                        net.minecraft.core.BlockPos structPos = start.getBoundingBox().getCenter();
                        double dist = Math.sqrt(maidPos.distSqr(structPos));
                        if (dist <= 128) {
                            String structName = structureRegistry.getResourceKey(start.getStructure())
                                    .map(k -> k.location().toString())
                                    .orElse("unknown");
                            if (seen.add(structName)) {
                                JsonObject structObj = new JsonObject();
                                structObj.addProperty("name", structName);
                                structObj.addProperty("distance", Math.round(dist * 10.0) / 10.0);
                                structuresArray.add(structObj);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        data.add("nearby_structures", structuresArray);

        JsonArray inventoryArray = new JsonArray();
        ItemStackHandler maidInv = maid.getMaidInv();
        for (int i = 0; i < maidInv.getSlots(); i++) {
            ItemStack stack = maidInv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                JsonObject invItem = new JsonObject();
                invItem.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                invItem.addProperty("count", stack.getCount());
                inventoryArray.add(invItem);
            }
        }
        data.add("inventory", inventoryArray);

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
            entityObj.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            entityObj.addProperty("name", entity.getName().getString());
            entityObj.addProperty("hostile", isHostileEntity(entity));
            entityObj.addProperty("distance", maid.distanceTo(entity));
            entitiesArray.add(entityObj);
            count++;
        }
        data.add("entities", entitiesArray);

        return data;
    }

    private boolean isHostileEntity(LivingEntity entity) {
        return entity.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER;
    }
}
