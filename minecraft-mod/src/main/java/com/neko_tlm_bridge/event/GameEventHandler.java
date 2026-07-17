package com.neko_tlm_bridge.event;

import com.github.tartaricacid.touhoulittlemaid.api.game.gomoku.Point;
import com.github.tartaricacid.touhoulittlemaid.api.game.gomoku.Statue;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityGomoku;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityWChess;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityCChess;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityJoy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.config.ModConfig;
import com.neko_tlm_bridge.ws.handler.MaidHelper;
import com.neko_tlm_bridge.ws.NekoWebSocketServer;
import com.neko_tlm_bridge.ws.Protocol;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@EventBusSubscriber
public class GameEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("NekoTlmBridge");
    private static volatile NekoWebSocketServer webSocketServer;

    // Track maid inventory snapshots when player opens backpack
    private static final Map<String, Map<Integer, String>> openInventorySnapshots = new HashMap<>();
    // The maid_id that the plugin wants us to monitor for inventory changes
    private static volatile String monitoredMaidId = "";
    private static final Map<String, BlockActivityAggregate> blockActivityAggregates = new HashMap<>();
    private static final BehaviorAggregate hurtAggregate = new BehaviorAggregate(Protocol.EVENT_PLAYER_HURT);
    private static final BehaviorAggregate killAggregate = new BehaviorAggregate(Protocol.EVENT_PLAYER_KILL_ENTITY);

    private static class BlockActivityAggregate {
        final String action;
        final String playerId;
        final String playerName;
        final Map<String, Integer> blocks = new HashMap<>();
        long startTick;
        long endTick;
        int count;

        BlockActivityAggregate(String action, Player player, long tick) {
            this.action = action;
            this.playerId = player.getStringUUID();
            this.playerName = player.getName().getString();
            this.startTick = tick;
            this.endTick = tick;
        }

        void add(String blockId, long tick) {
            blocks.put(blockId, blocks.getOrDefault(blockId, 0) + 1);
            count++;
            endTick = tick;
        }
    }

    private static class BehaviorAggregate {
        final String eventType;
        final Map<String, Integer> targets = new HashMap<>();
        String playerId = "";
        String playerName = "";
        String lastTarget = "";
        String lastAttacker = "";
        long startTick;
        long endTick;
        int count;
        float totalDamage;
        float lastHealth;
        float lastMaxHealth;
        boolean includesMaid;
        String lastDamageType;

        BehaviorAggregate(String eventType) {
            this.eventType = eventType;
        }

        void recordHurt(String scopedPlayerId, String targetName, boolean maid,
                        float damage, float health, float maxHealth,
                        String attacker, String damageType, long tick) {
            beginIfNeeded(tick);
            playerId = scopedPlayerId == null ? "" : scopedPlayerId;
            targets.put(targetName, targets.getOrDefault(targetName, 0) + 1);
            lastTarget = targetName;
            lastAttacker = attacker;
            lastDamageType = damageType;
            endTick = tick;
            count++;
            totalDamage += damage;
            lastHealth = health;
            lastMaxHealth = maxHealth;
            includesMaid = includesMaid || maid;
        }

        void recordKill(String scopedPlayerId, String player,
                        String targetType, String targetName, long tick) {
            beginIfNeeded(tick);
            playerId = scopedPlayerId == null ? "" : scopedPlayerId;
            playerName = player;
            String target = targetType == null || targetType.isEmpty() ? targetName : targetType;
            targets.put(target, targets.getOrDefault(target, 0) + 1);
            lastTarget = targetName;
            endTick = tick;
            count++;
        }

        boolean isActive() {
            return count > 0;
        }

        void reset() {
            targets.clear();
            playerId = "";
            playerName = "";
            lastTarget = "";
            lastAttacker = "";
            lastDamageType = "";
            startTick = 0;
            endTick = 0;
            count = 0;
            totalDamage = 0;
            lastHealth = 0;
            lastMaxHealth = 0;
            includesMaid = false;
        }

        private void beginIfNeeded(long tick) {
            if (count == 0) {
                startTick = tick;
                endTick = tick;
            }
        }
    }

    public static void setMonitoredMaidId(String maidId) {
        String nextMaidId = maidId == null ? "" : maidId.trim();
        if (!monitoredMaidId.equals(nextMaidId)) {
            openInventorySnapshots.clear();
            blockActivityAggregates.clear();
            hurtAggregate.reset();
            killAggregate.reset();
            lastReportedBiome = "";
            candidateBiome = "";
            candidateBiomeStartTick = 0;
            lastPlayerDimension = "";
            currentChessGame = null;
            endedBoardPos = null;
        }
        monitoredMaidId = nextMaidId;
    }

    public static String getMonitoredMaidId() {
        return monitoredMaidId;
    }

    public static void setWebSocketServer(NekoWebSocketServer server) {
        webSocketServer = server;
    }

    public static void flushPendingBehaviorEvents() {
        flushBehaviorAggregates(Long.MAX_VALUE, true);
    }

    /** 清理所有静态状态，避免存档切换/服务端重启时残留状态导致误报或内存泄漏 */
    public static void resetState() {
        openInventorySnapshots.clear();
        monitoredMaidId = "";
        blockActivityAggregates.clear();
        hurtAggregate.reset();
        killAggregate.reset();
        lastRaining = false;
        lastThundering = false;
        lastIsNight = false;
        lastWeatherCheckTick = 0;
        lastReportedBiome = "";
        candidateBiome = "";
        candidateBiomeStartTick = 0;
        lastPlayerDimension = "";
        lastChessCheckTick = 0;
        currentChessGame = null;
        endedBoardPos = null;
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        Player player = event.getPlayer();
        if (player == null || player.level().isClientSide()) return;
        if (!shouldTrackPlayerBlockActivity(player)) return;
        String blockId = BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()).toString();
        recordBlockActivity("break", player, blockId, player.level().getGameTime());
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) return;
        if (!shouldTrackPlayerBlockActivity(player)) return;
        String blockId = BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock()).toString();
        recordBlockActivity("place", player, blockId, player.level().getGameTime());
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingIncomingDamageEvent event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player player) {
            EntityMaid maid = monitoredOwnerMaid(player);
            if (maid == null) return;
            hurtAggregate.recordHurt(
                    player.getStringUUID(),
                    player.getName().getString(),
                    false,
                    event.getAmount(),
                    Math.max(0, player.getHealth() - event.getAmount()),
                    player.getMaxHealth(),
                    attackerName(event),
                    event.getSource().getMsgId(),
                    player.level().getGameTime()
            );
        } else if (entity instanceof EntityMaid maid) {
            if (monitoredMaidId.isEmpty()
                    || !monitoredMaidId.equals(maid.getStringUUID())) return;
            String damageType = event.getSource().getMsgId();
            JsonObject eventData = new JsonObject();
            eventData.addProperty("event_type", Protocol.EVENT_MAID_HURT);
            eventData.addProperty("maid_id", maid.getStringUUID());
            eventData.addProperty("maid_name", maid.getName().getString());
            eventData.addProperty("damage", event.getAmount());
            eventData.addProperty("health", Math.max(0, maid.getHealth() - event.getAmount()));
            eventData.addProperty("max_health", maid.getMaxHealth());
            eventData.addProperty("attacker", attackerName(event));
            eventData.addProperty("damage_type", damageType);
            webSocketServer.broadcastEvent(eventData);
            hurtAggregate.recordHurt(
                    maid.getOwnerUUID() == null
                            ? "" : maid.getOwnerUUID().toString(),
                    maid.getName().getString(),
                    true,
                    event.getAmount(),
                    Math.max(0, maid.getHealth() - event.getAmount()),
                    maid.getMaxHealth(),
                    attackerName(event),
                    damageType,
                    maid.level().getGameTime()
            );
        }
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        net.minecraft.server.level.ServerPlayer player = event.getPlayer();
        if (monitoredOwnerMaid(player) == null) return;
        String message = event.getRawText();
        JsonObject chatData = new JsonObject();
        chatData.addProperty("event_type", "chat");
        chatData.addProperty("sender", player.getName().getString());
        chatData.addProperty("message", message);
        chatData.addProperty("x", player.getX());
        chatData.addProperty("y", player.getY());
        chatData.addProperty("z", player.getZ());
        webSocketServer.broadcastChatMessage(chatData);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (webSocketServer == null || !webSocketServer.hasClients()) return;
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        EntityMaid maid = monitoredOwnerMaid(player);
        if (maid == null) return;
        JsonObject eventData = new JsonObject();
        eventData.addProperty("event_type", Protocol.EVENT_PLAYER_LOGIN);
        eventData.addProperty("player_uuid", player.getStringUUID());
        eventData.addProperty("dimension", player.level().dimension().location().toString());
        eventData.addProperty("x", player.getX());
        eventData.addProperty("y", player.getY());
        eventData.addProperty("z", player.getZ());
        addPlayerScope(eventData, maid, player);
        webSocketServer.broadcastEvent(eventData);
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;

        LivingEntity entity = event.getEntity();

        // Maid death
        if (entity instanceof EntityMaid maid) {
            if (monitoredMaidId.isEmpty()
                    || !monitoredMaidId.equals(maid.getStringUUID())) return;
            JsonObject eventData = new JsonObject();
            eventData.addProperty("event_type", Protocol.EVENT_MAID_DEATH);
            eventData.addProperty("maid_id", maid.getStringUUID());
            eventData.addProperty("maid_name", maid.getName().getString());
            eventData.addProperty("cause", event.getSource().getMsgId());
            eventData.addProperty("death_x", maid.blockPosition().getX());
            eventData.addProperty("death_y", maid.blockPosition().getY());
            eventData.addProperty("death_z", maid.blockPosition().getZ());
            if (event.getSource().getEntity() instanceof LivingEntity killer) {
                eventData.addProperty("killer", killer.getName().getString());
            }
            webSocketServer.broadcastEvent(eventData);
        }

        // Player death
        if (entity instanceof Player player) {
            EntityMaid maid = monitoredOwnerMaid(player);
            if (maid == null) return;
            JsonObject eventData = new JsonObject();
            eventData.addProperty("event_type", Protocol.EVENT_PLAYER_DEATH);
            addPlayerScope(eventData, maid, player);
            eventData.addProperty("cause", event.getSource().getMsgId());
            eventData.addProperty("death_x", player.blockPosition().getX());
            eventData.addProperty("death_y", player.blockPosition().getY());
            eventData.addProperty("death_z", player.blockPosition().getZ());
            if (event.getSource().getEntity() instanceof LivingEntity killer) {
                eventData.addProperty("killer", killer.getName().getString());
            }
            webSocketServer.broadcastEvent(eventData);
        }

        if (!(entity instanceof Player) && !(entity instanceof EntityMaid) && event.getSource().getEntity() instanceof Player player) {
            EntityMaid maid = monitoredOwnerMaid(player);
            if (maid == null) return;
            killAggregate.recordKill(
                    player.getStringUUID(),
                    player.getName().getString(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    entity.getName().getString(),
                    entity.level().getGameTime()
            );
        }
    }

    // Weather and time tracking
    private static boolean lastRaining = false;
    private static boolean lastThundering = false;
    private static boolean lastIsNight = false;
    private static long lastWeatherCheckTick = 0;
    private static final long WEATHER_CHECK_INTERVAL = 100; // Check every 5 seconds (100 ticks)

    // Biome change tracking with debounce to prevent bouncing at boundaries
    private static String lastReportedBiome = "";
    private static String candidateBiome = "";
    private static long candidateBiomeStartTick = 0;
    private static final long BIOME_DEBOUNCE_TICKS = 200; // Must stay in new biome for 10 seconds

    private static String lastPlayerDimension = "";

    // Chess game tracking
    private static final String BOARD_GAMES_TASK_UID = "touhou_little_maid:board_games";
    private static final long CHESS_CHECK_INTERVAL = 20; // Check every 1 second (20 ticks)
    private static final int CHESS_SEARCH_RANGE = 4; // Search for chess boards within 4 blocks
    private static long lastChessCheckTick = 0;

    // Current chess game state (null = no game in progress)
    private static ChessGameState currentChessGame = null;
    // Board positions that have ended but not yet reset — skip these to prevent re-triggering
    private static BlockPos endedBoardPos = null;

    /** Tracks the state of an ongoing chess game */
    private static class ChessGameState {
        String gameType;       // "gomoku", "wchess", "cchess"
        BlockPos boardPos;
        int lastMoveCount;
        boolean lastPlayerTurn;
        boolean gameEndNotified;
        // For mid-game commentary: next move count at which to trigger commentary
        int nextCommentaryAt;

        ChessGameState(String gameType, BlockPos boardPos, int moveCount, boolean playerTurn) {
            this.gameType = gameType;
            this.boardPos = boardPos;
            this.lastMoveCount = moveCount;
            this.lastPlayerTurn = playerTurn;
            this.gameEndNotified = false;
            this.nextCommentaryAt = moveCount + 3 + (int)(Math.random() * 6); // 3-8 moves later
        }
    }

    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;

        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) return;

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        long currentTick = overworld.getGameTime();

        flushBehaviorAggregates(currentTick, false);
        flushExpiredBlockActivities(currentTick, false);

        // 查询一次 maid 引用，供维度检测、生物群系检测、棋局检测共用
        EntityMaid maid = null;
        if (!monitoredMaidId.isEmpty()) {
            maid = findMaidById(monitoredMaidId, server);
        }

        // Dimension change detection
        if (maid != null && maid.getOwner() instanceof net.minecraft.server.level.ServerPlayer player) {
            String currentDimension = player.level().dimension().location().toString();
            if (!lastPlayerDimension.isEmpty() && !lastPlayerDimension.equals(currentDimension)) {
                JsonObject eventData = new JsonObject();
                eventData.addProperty("event_type", Protocol.EVENT_DIMENSION_CHANGE);
                addPlayerScope(eventData, maid, player);
                eventData.addProperty("from_dimension", lastPlayerDimension);
                eventData.addProperty("to_dimension", currentDimension);
                webSocketServer.broadcastEvent(eventData);
            }
            lastPlayerDimension = currentDimension;
        }

        // Biome change detection (runs every tick for debounce accuracy)
        if (maid != null) {
            String currentBiome = maid.level().getBiome(maid.blockPosition())
                    .unwrapKey()
                    .map(k -> k.location().toString())
                    .orElse("unknown");
            if (currentBiome.equals(lastReportedBiome)) {
                // Back to reported biome, reset candidate
                candidateBiome = "";
            } else if (currentBiome.equals(candidateBiome)) {
                // Still in candidate biome, check debounce
                if (currentTick - candidateBiomeStartTick >= BIOME_DEBOUNCE_TICKS) {
                    JsonObject eventData = new JsonObject();
                    eventData.addProperty("event_type", Protocol.EVENT_BIOME_CHANGE);
                    eventData.addProperty("maid_id", maid.getStringUUID());
                    eventData.addProperty("maid_name", maid.getName().getString());
                    eventData.addProperty("biome", currentBiome);
                    eventData.addProperty("old_biome", lastReportedBiome);
                    webSocketServer.broadcastEvent(eventData);
                    lastReportedBiome = currentBiome;
                    candidateBiome = "";
                }
            } else {
                // New biome detected, start debounce timer
                candidateBiome = currentBiome;
                candidateBiomeStartTick = currentTick;
            }

            // Chess game detection (throttled)
            if (currentTick - lastChessCheckTick >= CHESS_CHECK_INTERVAL) {
                lastChessCheckTick = currentTick;
                checkChessGame(maid);
            }
        }

        // Weather and time checks (throttled)
        if (!ModConfig.WEATHER_EVENT_ENABLED.get() && !ModConfig.TIME_EVENT_ENABLED.get()) return;
        if (currentTick - lastWeatherCheckTick < WEATHER_CHECK_INTERVAL) return;
        lastWeatherCheckTick = currentTick;

        // Weather change detection
        if (ModConfig.WEATHER_EVENT_ENABLED.get()) {
            boolean isRaining = overworld.isRaining();
            boolean isThundering = overworld.isThundering();
            if (isRaining != lastRaining || isThundering != lastThundering) {
                JsonObject eventData = new JsonObject();
                eventData.addProperty("event_type", Protocol.EVENT_WEATHER_CHANGE);
                eventData.addProperty("raining", isRaining);
                eventData.addProperty("thundering", isThundering);
                webSocketServer.broadcastEvent(eventData);
                lastRaining = isRaining;
                lastThundering = isThundering;
            }
        }

        // Time phase change detection (day/night)
        if (ModConfig.TIME_EVENT_ENABLED.get()) {
            long dayTime = overworld.getDayTime() % 24000;
            boolean isNight = dayTime >= 12542 && dayTime < 23460;
            if (isNight != lastIsNight) {
                JsonObject eventData = new JsonObject();
                eventData.addProperty("event_type", Protocol.EVENT_TIME_PHASE_CHANGE);
                eventData.addProperty("phase", isNight ? "night" : "day");
                eventData.addProperty("day_time", overworld.getDayTime() % 24000);
                webSocketServer.broadcastEvent(eventData);
                lastIsNight = isNight;
            }
        }
    }

    @SubscribeEvent
    public static void onAdvancement(net.neoforged.neoforge.event.entity.player.AdvancementEvent.AdvancementEarnEvent event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        Player player = event.getEntity();
        EntityMaid maid = monitoredOwnerMaid(player);
        if (maid == null) return;
        net.minecraft.advancements.AdvancementHolder holder = event.getAdvancement();
        net.minecraft.advancements.Advancement advancement = holder.value();
        // Only report displayable advancements (visible in toast)
        if (advancement.display().isEmpty()) return;

        JsonObject eventData = new JsonObject();
        eventData.addProperty("event_type", Protocol.EVENT_ADVANCEMENT);
        addPlayerScope(eventData, maid, player);
        net.minecraft.advancements.DisplayInfo display = advancement.display().get();
        eventData.addProperty("title", display.getTitle().getString());
        eventData.addProperty("description", display.getDescription().getString());
        webSocketServer.broadcastEvent(eventData);
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        if (monitoredMaidId.isEmpty()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        // Find the monitored maid
        EntityMaid maid = findMaidById(monitoredMaidId, player.getServer());
        if (maid == null) return;

        // Only snapshot if the player is the maid's owner
        if (maid.getOwner() == null || !maid.getOwner().getUUID().equals(player.getUUID())) return;
        broadcastContainerInteraction(player, maid, "open", event.getContainer());

        Map<Integer, String> snapshot = new HashMap<>();
        ItemStackHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                snapshot.put(i, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() + "|" + stack.getCount());
            }
        }
        openInventorySnapshots.put(player.getStringUUID(), snapshot);
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        if (monitoredMaidId.isEmpty()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        Map<Integer, String> oldSnapshot = openInventorySnapshots.remove(player.getStringUUID());
        if (oldSnapshot == null) return;

        // Find the monitored maid
        EntityMaid maid = findMaidById(monitoredMaidId, player.getServer());
        if (maid == null) return;

        // Only track if the player is the maid's owner
        if (maid.getOwner() == null || !maid.getOwner().getUUID().equals(player.getUUID())) return;
        broadcastContainerInteraction(player, maid, "close", event.getContainer());

        // Take new snapshot
        Map<Integer, String> newSnapshot = new HashMap<>();
        ItemStackHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                newSnapshot.put(i, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() + "|" + stack.getCount());
            }
        }

        // Calculate diff (format: "item_id|count", e.g. "minecraft:diamond_sword|2")
        java.util.List<String> added = new java.util.ArrayList<>();
        java.util.List<String> removed = new java.util.ArrayList<>();

        for (Map.Entry<Integer, String> newEntry : newSnapshot.entrySet()) {
            String oldVal = oldSnapshot.get(newEntry.getKey());
            if (!newEntry.getValue().equals(oldVal)) {
                String newVal = newEntry.getValue();
                int sepIdx = newVal.lastIndexOf('|');
                String itemName = sepIdx > 0 ? newVal.substring(0, sepIdx) : newVal;
                int newCount = sepIdx > 0 ? Integer.parseInt(newVal.substring(sepIdx + 1)) : 1;
                if (oldVal == null) {
                    added.add(itemName + "x" + newCount);
                } else {
                    int oldSepIdx = oldVal.lastIndexOf('|');
                    String oldItemName = oldSepIdx > 0 ? oldVal.substring(0, oldSepIdx) : oldVal;
                    int oldCount = oldSepIdx > 0 ? Integer.parseInt(oldVal.substring(oldSepIdx + 1)) : 1;
                    if (itemName.equals(oldItemName)) {
                        if (newCount > oldCount) {
                            added.add(itemName + "x" + (newCount - oldCount));
                        } else if (newCount < oldCount) {
                            removed.add(itemName + "x" + (oldCount - newCount));
                        }
                    } else {
                        removed.add(oldItemName + "x" + oldCount);
                        added.add(itemName + "x" + newCount);
                    }
                }
            }
        }

        for (Map.Entry<Integer, String> oldEntry : oldSnapshot.entrySet()) {
            if (!newSnapshot.containsKey(oldEntry.getKey())) {
                String oldVal = oldEntry.getValue();
                int sepIdx = oldVal.lastIndexOf('|');
                String itemName = sepIdx > 0 ? oldVal.substring(0, sepIdx) : oldVal;
                int count = sepIdx > 0 ? Integer.parseInt(oldVal.substring(sepIdx + 1)) : 1;
                removed.add(itemName + "x" + count);
            }
        }

        if (added.isEmpty() && removed.isEmpty()) return;

        JsonObject eventData = new JsonObject();
        eventData.addProperty("event_type", Protocol.EVENT_INVENTORY_CHANGE);
        eventData.addProperty("maid_id", maid.getStringUUID());
        eventData.addProperty("maid_name", maid.getName().getString());
        eventData.addProperty("player_name", player.getName().getString());

        JsonArray addedArray = new JsonArray();
        for (String item : added) addedArray.add(item);
        eventData.add("added", addedArray);

        JsonArray removedArray = new JsonArray();
        for (String item : removed) removedArray.add(item);
        eventData.add("removed", removedArray);

        webSocketServer.broadcastEvent(eventData);
    }

    @SubscribeEvent
    public static void onFishingRodUse(PlayerInteractEvent.RightClickItem event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        Player player = event.getEntity();
        if (player == null || player.level().isClientSide()) return;
        if (!(event.getItemStack().getItem() instanceof FishingRodItem)) return;
        EntityMaid maid = trackedOwnerMaid(player);
        if (maid == null) return;

        JsonObject eventData = new JsonObject();
        eventData.addProperty("event_type", Protocol.EVENT_FISHING_START);
        eventData.addProperty("maid_id", maid.getStringUUID());
        eventData.addProperty("maid_name", maid.getName().getString());
        eventData.addProperty("player_name", player.getName().getString());
        eventData.addProperty("x", player.getX());
        eventData.addProperty("y", player.getY());
        eventData.addProperty("z", player.getZ());
        webSocketServer.broadcastEvent(eventData);
    }

    @SubscribeEvent
    public static void onItemFished(ItemFishedEvent event) {
        if (!ModConfig.EVENT_PUSH_ENABLED.get() || webSocketServer == null || !webSocketServer.hasClients()) return;
        Player player = event.getEntity();
        if (player == null || player.level().isClientSide()) return;
        EntityMaid maid = trackedOwnerMaid(player);
        if (maid == null) return;

        JsonObject eventData = new JsonObject();
        eventData.addProperty("event_type", Protocol.EVENT_ITEM_FISHED);
        eventData.addProperty("maid_id", maid.getStringUUID());
        eventData.addProperty("maid_name", maid.getName().getString());
        eventData.addProperty("player_name", player.getName().getString());
        eventData.addProperty("rod_damage", event.getRodDamage());
        JsonArray drops = new JsonArray();
        for (ItemStack stack : event.getDrops()) {
            if (!stack.isEmpty()) {
                JsonObject item = new JsonObject();
                item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                item.addProperty("count", stack.getCount());
                drops.add(item);
            }
        }
        eventData.add("drops", drops);
        webSocketServer.broadcastEvent(eventData);
    }

    private static void broadcastContainerInteraction(Player player, EntityMaid maid, String action, AbstractContainerMenu container) {
        JsonObject eventData = new JsonObject();
        eventData.addProperty("event_type", Protocol.EVENT_CONTAINER_INTERACTION);
        eventData.addProperty("maid_id", maid.getStringUUID());
        eventData.addProperty("maid_name", maid.getName().getString());
        eventData.addProperty("player_name", player.getName().getString());
        eventData.addProperty("action", action);
        eventData.addProperty("container_type", container.getClass().getSimpleName());
        webSocketServer.broadcastEvent(eventData);
    }

    private static EntityMaid findMaidById(String maidId, net.minecraft.server.MinecraftServer server) {
        return MaidHelper.findMaidById(server, maidId);
    }

    private static boolean shouldTrackPlayerBlockActivity(Player player) {
        // Maid terrain construction uses an owner-profile FakePlayer so claim
        // and protection hooks can apply the owner's permissions. It is not a
        // real player behavior signal and must not become companion evidence
        // such as "the owner placed nine blocks".
        if (player.isFakePlayer()) return false;
        EntityMaid maid = trackedOwnerMaid(player);
        if (maid == null) return false;
        return maid.distanceTo(player) <= 64;
    }

    private static EntityMaid trackedOwnerMaid(Player player) {
        EntityMaid maid = monitoredOwnerMaid(player);
        if (maid == null) return null;
        if (!maid.level().dimension().equals(player.level().dimension())) return null;
        return maid;
    }

    private static EntityMaid monitoredOwnerMaid(Player player) {
        if (monitoredMaidId.isEmpty() || player == null
                || player.getServer() == null) return null;
        EntityMaid maid = findMaidById(monitoredMaidId, player.getServer());
        if (maid == null || maid.getOwner() == null) return null;
        return maid.getOwner().getUUID().equals(player.getUUID()) ? maid : null;
    }

    private static void addPlayerScope(
            JsonObject eventData, EntityMaid maid, Player player) {
        eventData.addProperty("maid_id", maid.getStringUUID());
        eventData.addProperty("maid_name", maid.getName().getString());
        eventData.addProperty("player_id", player.getStringUUID());
        eventData.addProperty("player_name", player.getName().getString());
    }

    private static String attackerName(LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof LivingEntity attacker) {
            return attacker.getName().getString();
        }
        return event.getSource().getMsgId();
    }

    private static void flushBehaviorAggregates(long currentTick, boolean force) {
        if (shouldFlushBehaviorAggregate(hurtAggregate, currentTick, force)) {
            flushBehaviorAggregate(hurtAggregate);
        }
        if (shouldFlushBehaviorAggregate(killAggregate, currentTick, force)) {
            flushBehaviorAggregate(killAggregate);
        }
    }

    private static boolean shouldFlushBehaviorAggregate(BehaviorAggregate aggregate, long currentTick, boolean force) {
        return aggregate.isActive() && (force
                || currentTick - aggregate.endTick >= ModConfig.BEHAVIOR_AGGREGATE_IDLE_TICKS.get()
                || currentTick - aggregate.startTick >= ModConfig.BEHAVIOR_AGGREGATE_MAX_WINDOW_TICKS.get());
    }

    private static void flushBehaviorAggregate(BehaviorAggregate aggregate) {
        if (!aggregate.isActive()) return;
        if (monitoredMaidId.isEmpty()) {
            aggregate.reset();
            return;
        }

        JsonObject eventData = new JsonObject();
        eventData.addProperty("event_type", aggregate.eventType);
        eventData.addProperty("maid_id", monitoredMaidId);
        if (!aggregate.playerId.isEmpty()) {
            eventData.addProperty("player_id", aggregate.playerId);
        }
        eventData.addProperty("count", aggregate.count);
        eventData.addProperty("start_tick", aggregate.startTick);
        eventData.addProperty("end_tick", aggregate.endTick);
        eventData.addProperty("duration_ticks", Math.max(0, aggregate.endTick - aggregate.startTick));
        eventData.addProperty("primary_target", primaryTarget(aggregate));

        if (Protocol.EVENT_PLAYER_HURT.equals(aggregate.eventType)) {
            eventData.addProperty("total_damage", aggregate.totalDamage);
            eventData.addProperty("last_health", aggregate.lastHealth);
            eventData.addProperty("last_max_health", aggregate.lastMaxHealth);
            eventData.addProperty("last_target", aggregate.lastTarget);
            eventData.addProperty("last_attacker", aggregate.lastAttacker);
            eventData.addProperty("last_damage_type", aggregate.lastDamageType == null ? "" : aggregate.lastDamageType);
            eventData.addProperty("includes_maid", aggregate.includesMaid);
        } else if (Protocol.EVENT_PLAYER_KILL_ENTITY.equals(aggregate.eventType)) {
            eventData.addProperty("player_name", aggregate.playerName);
            eventData.addProperty("last_target", aggregate.lastTarget);
        }

        JsonArray targets = new JsonArray();
        java.util.List<Map.Entry<String, Integer>> entries = new java.util.ArrayList<>(aggregate.targets.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(5, entries.size()); i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            JsonObject target = new JsonObject();
            target.addProperty("target", entry.getKey());
            target.addProperty("count", entry.getValue());
            targets.add(target);
        }
        eventData.add("targets", targets);
        webSocketServer.broadcastEvent(eventData);
        aggregate.reset();
    }

    private static String primaryTarget(BehaviorAggregate aggregate) {
        String target = "";
        int count = 0;
        for (Map.Entry<String, Integer> entry : aggregate.targets.entrySet()) {
            if (entry.getValue() > count) {
                target = entry.getKey();
                count = entry.getValue();
            }
        }
        return target;
    }

    private static void recordBlockActivity(String action, Player player, String blockId, long tick) {
        String key = player.getStringUUID() + ":" + action;
        BlockActivityAggregate aggregate = blockActivityAggregates.get(key);
        if (aggregate == null) {
            aggregate = new BlockActivityAggregate(action, player, tick);
            blockActivityAggregates.put(key, aggregate);
        }
        aggregate.add(blockId, tick);
        if (tick - aggregate.startTick >= ModConfig.BLOCK_ACTIVITY_MAX_WINDOW_TICKS.get()) {
            flushBlockActivity(key, aggregate);
        }
    }

    private static void flushExpiredBlockActivities(long currentTick, boolean force) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Map.Entry<String, BlockActivityAggregate> entry : blockActivityAggregates.entrySet()) {
            BlockActivityAggregate aggregate = entry.getValue();
            if (force || currentTick - aggregate.endTick >= ModConfig.BLOCK_ACTIVITY_IDLE_TICKS.get()) {
                keys.add(entry.getKey());
            }
        }
        for (String key : keys) {
            BlockActivityAggregate aggregate = blockActivityAggregates.get(key);
            if (aggregate != null) {
                flushBlockActivity(key, aggregate);
            }
        }
    }

    private static void flushBlockActivity(String key, BlockActivityAggregate aggregate) {
        if (aggregate.count < ModConfig.BLOCK_ACTIVITY_MIN_COUNT.get()) {
            blockActivityAggregates.remove(key);
            return;
        }

        JsonObject eventData = new JsonObject();
        eventData.addProperty("event_type", Protocol.EVENT_BLOCK_ACTIVITY);
        eventData.addProperty("maid_id", monitoredMaidId);
        eventData.addProperty("action", aggregate.action);
        eventData.addProperty("player_id", aggregate.playerId);
        eventData.addProperty("player_name", aggregate.playerName);
        eventData.addProperty("count", aggregate.count);
        eventData.addProperty("start_tick", aggregate.startTick);
        eventData.addProperty("end_tick", aggregate.endTick);
        eventData.addProperty("duration_ticks", Math.max(0, aggregate.endTick - aggregate.startTick));
        eventData.addProperty("primary_block", primaryBlock(aggregate));
        eventData.addProperty("tendency", inferBlockTendency(aggregate));

        JsonArray topBlocks = new JsonArray();
        java.util.List<Map.Entry<String, Integer>> entries = new java.util.ArrayList<>(aggregate.blocks.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(5, entries.size()); i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            JsonObject block = new JsonObject();
            block.addProperty("block", entry.getKey());
            block.addProperty("count", entry.getValue());
            topBlocks.add(block);
        }
        eventData.add("top_blocks", topBlocks);
        webSocketServer.broadcastEvent(eventData);
        blockActivityAggregates.remove(key);
    }

    private static String primaryBlock(BlockActivityAggregate aggregate) {
        String block = "";
        int count = 0;
        for (Map.Entry<String, Integer> entry : aggregate.blocks.entrySet()) {
            if (entry.getValue() > count) {
                block = entry.getKey();
                count = entry.getValue();
            }
        }
        return block;
    }

    private static String inferBlockTendency(BlockActivityAggregate aggregate) {
        if ("place".equals(aggregate.action)) return "building";
        int mining = 0;
        int gathering = 0;
        for (Map.Entry<String, Integer> entry : aggregate.blocks.entrySet()) {
            String blockId = entry.getKey();
            int count = entry.getValue();
            String tool = inferToolType(blockId);
            if ("pickaxe".equals(tool)) {
                mining += count;
            } else if ("axe".equals(tool) || "shovel".equals(tool)) {
                gathering += count;
            } else {
                if (blockId.contains("ore") || blockId.contains("stone") || blockId.contains("deepslate") || blockId.contains("netherrack") || blockId.contains("tuff")) {
                    mining += count;
                }
                if (blockId.contains("log") || blockId.contains("leaves") || blockId.contains("dirt") || blockId.contains("sand") || blockId.contains("gravel")) {
                    gathering += count;
                }
            }
        }
        if (mining >= Math.max(2, aggregate.count / 2)) return "mining";
        if (gathering >= Math.max(2, aggregate.count / 2)) return "gathering";
        return "digging";
    }

    private static String inferToolType(String blockId) {
        try {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(blockId);
            if (rl == null) return "none";
            var holder = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getHolder(rl).orElse(null);
            if (holder == null) return "none";
            var state = holder.value().defaultBlockState();
            if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) return "pickaxe";
            if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)) return "axe";
            if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) return "shovel";
            if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_HOE)) return "hoe";
            return "none";
        } catch (Exception e) {
            return "none";
        }
    }

    // ── Chess game detection ──

    private static void checkChessGame(EntityMaid maid) {
        boolean isPlayingChess = BOARD_GAMES_TASK_UID.equals(maid.getTask().getUid().toString());

        if (!isPlayingChess) {
            // Maid is not on board_games task — clear all chess state
            if (currentChessGame != null) {
                LOGGER.info("[Chess] Maid left board_games task, clearing game state (was: {})", currentChessGame.gameType);
                currentChessGame = null;
            }
            endedBoardPos = null;
            return;
        }

        // Maid is on board_games task — find nearby chess board
        TileEntityJoy boardEntity = findNearbyChessBoard(maid);
        if (boardEntity == null) {
            // No board found nearby — maid might be walking to one
            return;
        }

        BlockPos boardPos = boardEntity.getWorldPosition();

        // Skip boards that have ended but not yet been reset
        if (endedBoardPos != null && endedBoardPos.equals(boardPos)) {
            // Check if the board has been reset (game is in progress again)
            if (!isGameEnded(boardEntity)) {
                // Board has been reset — allow new game detection
                LOGGER.info("[Chess] Board at {} has been reset, allowing new game", boardPos);
                endedBoardPos = null;
            } else {
                // Still ended — skip to prevent re-triggering
                return;
            }
        }

        String gameType = getGameType(boardEntity);

        if (currentChessGame == null || !currentChessGame.boardPos.equals(boardPos)) {
            // Check if this board is already in an ended state — skip it
            if (isGameEnded(boardEntity)) {
                LOGGER.info("[Chess] Board at {} is already ended, skipping", boardPos);
                endedBoardPos = boardPos;
                return;
            }

            // New game detected
            currentChessGame = new ChessGameState(gameType, boardPos,
                    getMoveCount(boardEntity), isPlayerTurn(boardEntity));

            // Find the opponent (player near the board)
            String opponent = findNearbyPlayerName(boardEntity, maid);

            LOGGER.info("[Chess] Game start: type={}, opponent={}, boardPos={}", gameType, opponent, boardPos);

            JsonObject eventData = new JsonObject();
            eventData.addProperty("event_type", Protocol.EVENT_CHESS_GAME_START);
            eventData.addProperty("maid_id", maid.getStringUUID());
            eventData.addProperty("maid_name", maid.getName().getString());
            eventData.addProperty("game_type", gameType);
            eventData.addProperty("opponent", opponent);
            if ("gomoku".equals(gameType)) {
                eventData.addProperty("maid_skill", maid.getGameRecordManager().getGomokuWinCount());
            }
            webSocketServer.broadcastEvent(eventData);
            return;
        }

        // Existing game — check for state changes
        int currentMoveCount = getMoveCount(boardEntity);
        boolean currentPlayerTurn = isPlayerTurn(boardEntity);

        // 棋盘被重置（moveCount 回退），视为新游戏
        if (currentMoveCount < currentChessGame.lastMoveCount) {
            currentChessGame = new ChessGameState(gameType, boardPos,
                    currentMoveCount, currentPlayerTurn);
            return;
        }

        // Check if game ended
        boolean gameEnded = isGameEnded(boardEntity);
        if (gameEnded && !currentChessGame.gameEndNotified) {
            currentChessGame.gameEndNotified = true;
            String result = getGameResult(boardEntity, maid);
            String opponent = findNearbyPlayerName(boardEntity, maid);

            LOGGER.info("[Chess] Game end: type={}, result={}, moves={}, opponent={}", currentChessGame.gameType, result, currentMoveCount, opponent);

            JsonObject eventData = new JsonObject();
            eventData.addProperty("event_type", Protocol.EVENT_CHESS_GAME_END);
            eventData.addProperty("maid_id", maid.getStringUUID());
            eventData.addProperty("maid_name", maid.getName().getString());
            eventData.addProperty("game_type", currentChessGame.gameType);
            eventData.addProperty("result", result);
            eventData.addProperty("opponent", opponent);
            eventData.addProperty("move_count", currentMoveCount);
            if ("gomoku".equals(currentChessGame.gameType)) {
                eventData.addProperty("maid_skill", maid.getGameRecordManager().getGomokuWinCount());
            }
            addBoardData(eventData, boardEntity);
            webSocketServer.broadcastEvent(eventData);

            // Mark this board as ended to prevent re-triggering until reset
            endedBoardPos = boardPos;
            currentChessGame = null;
            return;
        }

        // Check for mid-game commentary trigger
        if (!currentChessGame.gameEndNotified
                && currentMoveCount > currentChessGame.lastMoveCount
                && currentMoveCount >= currentChessGame.nextCommentaryAt) {

            LOGGER.info("[Chess] Mid-game commentary: type={}, move={}, maidTurn={}, nextAt={}",
                    currentChessGame.gameType, currentMoveCount, !currentPlayerTurn,
                    currentMoveCount + 3 + (int)(Math.random() * 6));

            JsonObject eventData = new JsonObject();
            eventData.addProperty("event_type", Protocol.EVENT_CHESS_MID_GAME);
            eventData.addProperty("maid_id", maid.getStringUUID());
            eventData.addProperty("maid_name", maid.getName().getString());
            eventData.addProperty("game_type", currentChessGame.gameType);
            eventData.addProperty("is_maid_turn", !currentPlayerTurn);
            eventData.addProperty("move_count", currentMoveCount);
            if ("gomoku".equals(currentChessGame.gameType)) {
                eventData.addProperty("maid_skill", maid.getGameRecordManager().getGomokuWinCount());
            }
            addBoardData(eventData, boardEntity);
            webSocketServer.broadcastEvent(eventData);

            // Schedule next commentary
            currentChessGame.nextCommentaryAt = currentMoveCount + 3 + (int)(Math.random() * 6);
        }

        // Update tracked state
        currentChessGame.lastMoveCount = currentMoveCount;
        currentChessGame.lastPlayerTurn = currentPlayerTurn;
    }

    private static TileEntityJoy findNearbyChessBoard(EntityMaid maid) {
        BlockPos maidPos = maid.blockPosition();
        for (int dx = -CHESS_SEARCH_RANGE; dx <= CHESS_SEARCH_RANGE; dx++) {
            for (int dy = -CHESS_SEARCH_RANGE; dy <= CHESS_SEARCH_RANGE; dy++) {
                for (int dz = -CHESS_SEARCH_RANGE; dz <= CHESS_SEARCH_RANGE; dz++) {
                    BlockPos checkPos = maidPos.offset(dx, dy, dz);
                    BlockEntity be = maid.level().getBlockEntity(checkPos);
                    if (be instanceof TileEntityGomoku || be instanceof TileEntityWChess || be instanceof TileEntityCChess) {
                        return (TileEntityJoy) be;
                    }
                }
            }
        }
        return null;
    }

    private static String getGameType(TileEntityJoy board) {
        if (board instanceof TileEntityGomoku) return "gomoku";
        if (board instanceof TileEntityWChess) return "wchess";
        if (board instanceof TileEntityCChess) return "cchess";
        return "unknown";
    }

    private static int getMoveCount(TileEntityJoy board) {
        if (board instanceof TileEntityGomoku gomoku) return gomoku.getChessCounter();
        if (board instanceof TileEntityWChess wchess) return wchess.getChessCounter();
        if (board instanceof TileEntityCChess cchess) return cchess.getChessCounter();
        return 0;
    }

    private static boolean isPlayerTurn(TileEntityJoy board) {
        if (board instanceof TileEntityGomoku gomoku) return gomoku.isPlayerTurn();
        if (board instanceof TileEntityWChess wchess) return wchess.isPlayerTurn();
        if (board instanceof TileEntityCChess cchess) return cchess.isPlayerTurn();
        return true;
    }

    private static boolean isGameEnded(TileEntityJoy board) {
        if (board instanceof TileEntityGomoku gomoku) {
            return gomoku.getStatue() != Statue.IN_PROGRESS;
        }
        if (board instanceof TileEntityWChess wchess) {
            return wchess.isCheckmate() || wchess.isRepeat() || wchess.isMoveNumberLimit();
        }
        if (board instanceof TileEntityCChess cchess) {
            return cchess.isCheckmate() || cchess.isRepeat() || cchess.isMoveNumberLimit();
        }
        return false;
    }

    private static String getGameResult(TileEntityJoy board, EntityMaid maid) {
        if (board instanceof TileEntityGomoku gomoku) {
            Statue statue = gomoku.getStatue();
            if (statue == Statue.DRAW) return "draw";
            // In gomoku, if maid's game record shows WIN, maid won; otherwise lost
            return maid.getGameRecordManager().isWin() ? "win" : "lose";
        }
        if (board instanceof TileEntityWChess wchess) {
            if (wchess.isRepeat() || wchess.isMoveNumberLimit()) return "draw";
            return maid.getGameRecordManager().isWin() ? "win" : "lose";
        }
        if (board instanceof TileEntityCChess cchess) {
            if (cchess.isRepeat() || cchess.isMoveNumberLimit()) return "draw";
            return maid.getGameRecordManager().isWin() ? "win" : "lose";
        }
        return "unknown";
    }

    private static String findNearbyPlayerName(
            TileEntityJoy board, EntityMaid maid) {
        BlockPos boardPos = board.getWorldPosition();
        LivingEntity owner = maid.getOwner();
        if (owner instanceof Player player
                && board.getLevel() != null
                && player.level() == board.getLevel()
                && player.blockPosition().distManhattan(boardPos) <= 4) {
            return player.getName().getString();
        }
        return "";
    }

    private static void addBoardData(JsonObject eventData, TileEntityJoy board) {
        if (board instanceof TileEntityGomoku gomoku) {
            StringBuilder sb = new StringBuilder(225);
            byte[][] data = gomoku.getChessData();
            for (int x = 0; x < 15; x++) {
                for (int y = 0; y < 15; y++) {
                    sb.append(data[x][y]);
                }
            }
            eventData.addProperty("board", sb.toString());
        } else if (board instanceof TileEntityWChess wchess) {
            eventData.addProperty("fen", wchess.getChessData().toFen());
        } else if (board instanceof TileEntityCChess cchess) {
            eventData.addProperty("fen", cchess.getChessData().toFen());
        }
    }
}
