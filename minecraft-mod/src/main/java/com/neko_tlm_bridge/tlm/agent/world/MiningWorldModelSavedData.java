package com.neko_tlm_bridge.tlm.agent.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Compact, per-dimension persistent world model for autonomous mining.
 *
 * <p>The model deliberately stores only semantic tunnel nodes, aggregate
 * tunnel segments, hazards and one loop-erased return breadcrumb route. Full
 * planner paths, scanned blocks and other live navigation caches remain
 * runtime-only.</p>
 *
 * <p>Instances obtained through {@link #get(ServerLevel)} are bound to the
 * Minecraft server thread. Every mutating API checks that ownership before
 * changing SavedData.</p>
 */
public final class MiningWorldModelSavedData extends SavedData {
    public static final int SCHEMA_VERSION = 1;
    public static final String DATA_NAME = "neko_tlm_mining_world_model";

    private static final String KEY_SCHEMA = "SchemaVersion";
    private static final String KEY_DIMENSION = "Dimension";
    private static final String KEY_OPERATIONS = "Operations";
    private static final String KEY_OPERATION_ID = "OperationId";
    private static final String KEY_MAID_ID = "MaidId";
    private static final String KEY_OPERATION_DIMENSION = "OperationDimension";
    private static final String KEY_GENERATION = "Generation";
    private static final String KEY_STATUS = "Status";
    private static final String KEY_ACTIVE = "Active";
    private static final String KEY_BLOCKED = "Blocked";
    private static final String KEY_TERMINAL = "Terminal";
    private static final String KEY_BLOCKED_REASON = "BlockedReason";
    private static final String KEY_NORMALIZED_ARGS_JSON = "NormalizedArgsJson";
    private static final String KEY_SELECTOR_JSON = "SelectorJson";
    private static final String KEY_TARGET_COUNT = "TargetCount";
    private static final String KEY_COLLECTED_COUNT = "CollectedCount";
    private static final String KEY_PHASE = "Phase";
    private static final String KEY_ORIGIN = "Origin";
    private static final String KEY_CURRENT_WORKFACE = "CurrentWorkface";
    private static final String KEY_MAIN_DIRECTION = "MainDirection";
    private static final String KEY_SHAPE = "Shape";
    private static final String KEY_SEGMENT_LENGTH = "SegmentLength";
    private static final String KEY_SEGMENTS_DUG = "SegmentsDug";
    private static final String KEY_CLEARED_BLOCKS = "ClearedBlocks";
    private static final String KEY_PLACEMENTS_USED = "PlacementsUsed";
    private static final String KEY_BRIDGE_SUPPORTS_PLACED = "BridgeSupportsPlaced";
    private static final String KEY_WATER_SEALS_PLACED = "WaterSealsPlaced";
    private static final String KEY_VEIN_MEMBERS = "VeinMembers";
    private static final String KEY_VEIN_HARVESTED_MEMBERS = "VeinHarvestedMembers";
    private static final String KEY_ROUTE_BREADCRUMBS = "RouteBreadcrumbs";
    private static final String KEY_CREATED = "CreatedGameTime";
    private static final String KEY_UPDATED = "UpdatedGameTime";
    private static final String KEY_ENTRY_NODE = "EntryNode";
    private static final String KEY_WORKFACE_NODE = "ActiveWorkfaceNode";
    private static final String KEY_NODES = "Nodes";
    private static final String KEY_SEGMENTS = "Segments";
    private static final String KEY_DANGERS = "Dangers";
    private static final String KEY_ID = "Id";
    private static final String KEY_TYPE = "Type";
    private static final String KEY_POS = "Pos";
    private static final String KEY_RESOURCE = "Resource";
    private static final String KEY_ESTIMATE = "Estimate";
    private static final String KEY_FROM = "From";
    private static final String KEY_TO = "To";
    private static final String KEY_LENGTH = "Length";
    private static final String KEY_WIDTH = "Width";
    private static final String KEY_HEIGHT = "Height";
    private static final String KEY_TRAVERSABLE = "Traversable";
    private static final String KEY_SEVERITY = "Severity";
    private static final String KEY_RADIUS = "Radius";
    private static final String KEY_RESOLVED = "Resolved";
    private static final String KEY_NOTE = "Note";

    private final Map<UUID, MutableOperation> operations = new LinkedHashMap<>();
    private String dimensionId;
    private transient Thread ownerThread;

    private MiningWorldModelSavedData() {
        this("");
    }

    /** Public primarily for deterministic serialization tests and offline tools. */
    public MiningWorldModelSavedData(String dimensionId) {
        this.dimensionId = normalizeText(dimensionId, 256);
    }

    public static SavedData.Factory<MiningWorldModelSavedData> factory() {
        return new SavedData.Factory<>(
                MiningWorldModelSavedData::new,
                MiningWorldModelSavedData::load,
                DataFixTypes.LEVEL);
    }

    /** Returns the graph stored in this exact dimension, never the overworld's graph. */
    public static MiningWorldModelSavedData get(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException(
                    "Mining world model must be obtained on the Minecraft server thread");
        }
        MiningWorldModelSavedData data = level.getDataStorage()
                .computeIfAbsent(factory(), DATA_NAME);
        data.bind(level.dimension().location().toString(), Thread.currentThread());
        return data;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public Optional<OperationSnapshot> operation(UUID operationId) {
        MutableOperation operation = operations.get(Objects.requireNonNull(operationId));
        return operation == null ? Optional.empty() : Optional.of(operation.snapshot());
    }

    public List<OperationSnapshot> operations() {
        return operations.values().stream()
                .sorted(Comparator.comparing(value -> value.operationId.toString()))
                .map(MutableOperation::snapshot)
                .toList();
    }

    /**
     * Loads or creates the action-owned mining session in this dimension.
     * The outer maid action UUID is the operation's durable primary key.
     */
    public static OperationSnapshot getOrCreate(
            ServerLevel level, UUID actionId, UUID maidId, JsonObject normalizedArgs) {
        Objects.requireNonNull(level, "level");
        return get(level).getOrCreateOperation(
                actionId, maidId, normalizedArgs, level.getGameTime());
    }

    public static Optional<OperationSnapshot> findResumableByMaid(
            ServerLevel level, UUID maidId) {
        return get(level).findResumableByMaid(maidId);
    }

    /**
     * Newest operation, including a terminal one, that owns at least one
     * recorded route edge for this maid in this dimension.
     */
    public static Optional<OperationSnapshot> latestByMaidWithRoute(
            ServerLevel level, UUID maidId) {
        return get(level).latestByMaidWithRoute(maidId);
    }

    /**
     * Instance form used by actions and deterministic unit tests. Existing
     * checkpoints are authoritative and reject action-ID reuse with different
     * maid or normalized planning arguments.
     */
    public OperationSnapshot getOrCreateOperation(
            UUID actionId, UUID maidId, JsonObject normalizedArgs, long gameTime) {
        assertMutationThread();
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(maidId, "maidId");
        Objects.requireNonNull(normalizedArgs, "normalizedArgs");
        SessionArguments arguments = SessionArguments.from(normalizedArgs);
        MutableOperation existing = operations.get(actionId);
        if (existing != null) {
            if (!existing.maidId.equals(maidId)
                    || !existing.normalizedArgsJson.equals(arguments.normalizedArgsJson)) {
                throw new IllegalArgumentException(
                        "action UUID is already bound to different mining arguments");
            }
            return existing.snapshot();
        }

        // One maid owns at most one durable mining operation. Starting a new
        // action is the explicit decision that supersedes any older paused or
        // blocked checkpoint, preventing unbounded resumable-session buildup.
        for (MutableOperation other : operations.values()) {
            if (!other.maidId.equals(maidId) || other.status.terminal()) {
                continue;
            }
            other.status = OperationStatus.SUPERSEDED;
            other.phase = "terminal";
            other.blockedReason = "superseded_by_new_operation";
            other.updatedGameTime = gameTime;
        }

        MutableOperation created = new MutableOperation(
                actionId, maidId, dimensionId, 0L, OperationStatus.ACTIVE,
                arguments.normalizedArgsJson, arguments.selectorJson,
                arguments.targetCount, 0,
                "validating", null, null,
                arguments.mainDirection, arguments.shape, arguments.segmentLength,
                0L, 0L, 0L, 0L, 0L, List.of(), List.of(), List.of(), "",
                gameTime, gameTime, null, null);
        operations.put(actionId, created);
        setDirty();
        return created.snapshot();
    }

    /** Newest nonterminal session for this maid in this dimension. */
    public Optional<OperationSnapshot> findResumableByMaid(UUID maidId) {
        Objects.requireNonNull(maidId, "maidId");
        return operations.values().stream()
                .filter(value -> value.maidId.equals(maidId) && value.status.resumable())
                .max(Comparator.comparingLong((MutableOperation value) -> value.updatedGameTime)
                        .thenComparing(value -> value.operationId.toString()))
                .map(MutableOperation::snapshot);
    }

    /**
     * Terminal operations remain eligible because their route is still useful
     * after mining succeeds, fails, or is intentionally stopped.
     */
    public Optional<OperationSnapshot> latestByMaidWithRoute(UUID maidId) {
        Objects.requireNonNull(maidId, "maidId");
        return operations.values().stream()
                .filter(value -> value.maidId.equals(maidId)
                        && value.routeBreadcrumbs.size() >= 2)
                .max(Comparator.comparingLong(
                                (MutableOperation value) -> value.updatedGameTime)
                        .thenComparingLong(value -> value.createdGameTime)
                        .thenComparing(value -> value.operationId.toString()))
                .map(MutableOperation::snapshot);
    }

    /**
     * Idempotently creates an operation and its deterministic ENTRY node.
     * Reusing an operation UUID for another maid is rejected.
     */
    public OperationSnapshot createOperation(
            UUID operationId, UUID maidId, BlockPos entrance, long gameTime) {
        assertMutationThread();
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(maidId, "maidId");
        Objects.requireNonNull(entrance, "entrance");
        MutableOperation existing = operations.get(operationId);
        if (existing != null) {
            if (!existing.maidId.equals(maidId)) {
                throw new IllegalArgumentException(
                        "operation UUID is already owned by another maid");
            }
            return existing.snapshot();
        }

        UUID entryId = deterministicEntryId(operationId);
        MutableOperation created = new MutableOperation(
                operationId, maidId, dimensionId, 0L, OperationStatus.ACTIVE,
                "{}", "{}", 0, 0, "validating", entrance.immutable(), null,
                "auto", "auto", 8, 0L, 0L, 0L, 0L, 0L,
                List.of(), List.of(), List.of(entrance.immutable()), "",
                gameTime, gameTime, entryId, null);
        created.nodes.put(entryId, new TunnelNode(
                entryId, NodeType.ENTRY, entrance.immutable(), "", 0,
                gameTime, gameTime));
        operations.put(operationId, created);
        setDirty();
        return created.snapshot();
    }

    public UUID addNode(
            UUID operationId, NodeType type, BlockPos position,
            String resourceKey, int estimate, long gameTime) {
        return recordNode(operationId, UUID.randomUUID(), type, position,
                resourceKey, estimate, gameTime);
    }

    /** Upserts a caller-owned node ID, making action replay idempotent. */
    public UUID recordNode(
            UUID operationId, UUID nodeId, NodeType type, BlockPos position,
            String resourceKey, int estimate, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(position, "position");
        String normalizedResource = normalizeText(resourceKey, 256);
        int normalizedEstimate = Math.max(0, estimate);
        TunnelNode current = operation.nodes.get(nodeId);
        if (current != null
                && (current.type() != type || !current.position().equals(position))) {
            throw new IllegalArgumentException(
                    "node UUID is already bound to different geometry");
        }
        long discovered = current == null ? gameTime : current.discoveredGameTime();
        TunnelNode replacement = new TunnelNode(
                nodeId, type, position.immutable(), normalizedResource,
                normalizedEstimate, discovered, gameTime);
        if (!replacement.equals(current)) {
            operation.nodes.put(nodeId, replacement);
            operation.updatedGameTime = gameTime;
            setDirty();
        }
        return nodeId;
    }

    public void setActiveWorkface(UUID operationId, UUID nodeId, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        TunnelNode node = operation.nodes.get(Objects.requireNonNull(nodeId));
        if (node == null || node.type() != NodeType.WORKFACE) {
            throw new IllegalArgumentException("active workface must reference a WORKFACE node");
        }
        if (!nodeId.equals(operation.activeWorkfaceNodeId)
                || !node.position().equals(operation.currentWorkfacePos)) {
            operation.activeWorkfaceNodeId = nodeId;
            operation.currentWorkfacePos = node.position();
            operation.updatedGameTime = gameTime;
            setDirty();
        }
    }

    public UUID addSegment(
            UUID operationId, UUID fromNodeId, UUID toNodeId,
            int length, int width, int height, boolean traversable,
            long gameTime) {
        return recordSegment(operationId, UUID.randomUUID(), fromNodeId, toNodeId,
                length, width, height, traversable, gameTime);
    }

    /** Records only aggregate segment geometry; no per-block path is accepted. */
    public UUID recordSegment(
            UUID operationId, UUID segmentId, UUID fromNodeId, UUID toNodeId,
            int length, int width, int height, boolean traversable,
            long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        Objects.requireNonNull(segmentId, "segmentId");
        Objects.requireNonNull(fromNodeId, "fromNodeId");
        Objects.requireNonNull(toNodeId, "toNodeId");
        if (fromNodeId.equals(toNodeId)) {
            throw new IllegalArgumentException("a tunnel segment requires distinct nodes");
        }
        if (!operation.nodes.containsKey(fromNodeId)
                || !operation.nodes.containsKey(toNodeId)) {
            throw new IllegalArgumentException("tunnel segment endpoints must exist");
        }
        TunnelSegment replacement = new TunnelSegment(
                segmentId, fromNodeId, toNodeId,
                positive(length, "length"), bounded(width, "width", 1, 8),
                bounded(height, "height", 1, 8), traversable, gameTime);
        TunnelSegment current = operation.segments.get(segmentId);
        if (current != null
                && (!current.fromNodeId().equals(fromNodeId)
                || !current.toNodeId().equals(toNodeId))) {
            throw new IllegalArgumentException(
                    "segment UUID is already bound to different endpoints");
        }
        if (!replacement.equals(current)) {
            operation.segments.put(segmentId, replacement);
            operation.updatedGameTime = gameTime;
            setDirty();
        }
        return segmentId;
    }

    public UUID addDanger(
            UUID operationId, DangerType type, DangerSeverity severity,
            BlockPos anchor, int radius, String note, long gameTime) {
        return recordDanger(operationId, UUID.randomUUID(), type, severity,
                anchor, radius, false, note, gameTime);
    }

    public UUID recordDanger(
            UUID operationId, UUID dangerId, DangerType type,
            DangerSeverity severity, BlockPos anchor, int radius,
            boolean resolved, String note, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        Objects.requireNonNull(dangerId, "dangerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(anchor, "anchor");
        DangerRecord replacement = new DangerRecord(
                dangerId, type, severity, anchor.immutable(),
                bounded(radius, "radius", 0, 64), resolved,
                normalizeText(note, 512), gameTime);
        DangerRecord current = operation.dangers.get(dangerId);
        if (!replacement.equals(current)) {
            operation.dangers.put(dangerId, replacement);
            operation.updatedGameTime = gameTime;
            setDirty();
        }
        return dangerId;
    }

    public void resolveDanger(UUID operationId, UUID dangerId, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        DangerRecord current = operation.dangers.get(Objects.requireNonNull(dangerId));
        if (current == null) {
            throw new IllegalArgumentException("unknown danger UUID");
        }
        if (!current.resolved()) {
            operation.dangers.put(dangerId, new DangerRecord(
                    current.id(), current.type(), current.severity(),
                    current.anchor(), current.radius(), true,
                    current.note(), gameTime));
            operation.updatedGameTime = gameTime;
            setDirty();
        }
    }

    public void setOperationStatus(
            UUID operationId, OperationStatus status, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        Objects.requireNonNull(status, "status");
        if (operation.status != status) {
            operation.status = status;
            operation.updatedGameTime = gameTime;
            setDirty();
        }
    }

    /** Raises, but never lowers, the generation floor remembered across restarts. */
    public void updateGeneration(UUID operationId, long generation, long gameTime) {
        assertMutationThread();
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        MutableOperation operation = requireOperation(operationId);
        if (generation > operation.generation) {
            operation.generation = generation;
            touch(operation, gameTime);
        }
    }

    public void updatePhase(UUID operationId, String phase, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        String normalized = normalizeText(phase, 64).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("phase must not be empty");
        }
        if (!normalized.equals(operation.phase)) {
            operation.phase = normalized;
            touch(operation, gameTime);
        }
    }

    /** Absolute monotonic counters reported by the autonomous action. */
    public void updateCounts(
            UUID operationId, int collectedCount, long segmentsDug,
            long clearedBlocks, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        if (collectedCount < operation.collectedCount
                || segmentsDug < operation.segmentsDug
                || clearedBlocks < operation.clearedBlocks) {
            throw new IllegalArgumentException("mining counters must be monotonic");
        }
        if (collectedCount < 0 || segmentsDug < 0L || clearedBlocks < 0L) {
            throw new IllegalArgumentException("mining counters must be non-negative");
        }
        if (operation.collectedCount != collectedCount
                || operation.segmentsDug != segmentsDug
                || operation.clearedBlocks != clearedBlocks) {
            operation.collectedCount = collectedCount;
            operation.segmentsDug = segmentsDug;
            operation.clearedBlocks = clearedBlocks;
            touch(operation, gameTime);
        }
    }

    /**
     * Persists the current connected-vein commitment.  The lists are not
     * artificially capped: abandoning a large vein because a checkpoint
     * budget was reached would turn a persistence detail into mining policy.
     */
    public void updateVeinState(
            UUID operationId,
            Collection<BlockPos> knownMembers,
            Collection<BlockPos> harvestedMembers,
            long gameTime) {
        assertMutationThread();
        Objects.requireNonNull(knownMembers, "knownMembers");
        Objects.requireNonNull(harvestedMembers, "harvestedMembers");
        MutableOperation operation = requireOperation(operationId);
        List<BlockPos> known = immutableDistinctPositions(knownMembers);
        List<BlockPos> harvested = immutableDistinctPositions(harvestedMembers);
        if (!known.equals(operation.veinMembers)
                || !harvested.equals(operation.veinHarvestedMembers)) {
            operation.veinMembers = known;
            operation.veinHarvestedMembers = harvested;
            touch(operation, gameTime);
        }
    }

    /** Absolute monotonic construction counters reported by the autonomous action. */
    public void updateConstructionCounts(
            UUID operationId, long placementsUsed,
            long bridgeSupportsPlaced, long waterSealsPlaced, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        if (placementsUsed < operation.placementsUsed
                || bridgeSupportsPlaced < operation.bridgeSupportsPlaced
                || waterSealsPlaced < operation.waterSealsPlaced) {
            throw new IllegalArgumentException(
                    "construction counters must be monotonic");
        }
        if (placementsUsed < 0L || bridgeSupportsPlaced < 0L
                || waterSealsPlaced < 0L
                || bridgeSupportsPlaced + waterSealsPlaced > placementsUsed) {
            throw new IllegalArgumentException(
                    "construction counters must be non-negative and consistent");
        }
        if (operation.placementsUsed != placementsUsed
                || operation.bridgeSupportsPlaced != bridgeSupportsPlaced
                || operation.waterSealsPlaced != waterSealsPlaced) {
            operation.placementsUsed = placementsUsed;
            operation.bridgeSupportsPlaced = bridgeSupportsPlaced;
            operation.waterSealsPlaced = waterSealsPlaced;
            touch(operation, gameTime);
        }
    }

    public void setOrigin(UUID operationId, BlockPos origin, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        Objects.requireNonNull(origin, "origin");
        UUID entryId = operation.entryNodeId != null
                ? operation.entryNodeId : deterministicEntryId(operationId);
        recordNode(operationId, entryId, NodeType.ENTRY, origin, "", 0, gameTime);
        boolean changed = !origin.equals(operation.originPos)
                || !entryId.equals(operation.entryNodeId);
        operation.originPos = origin.immutable();
        operation.entryNodeId = entryId;
        if (operation.routeBreadcrumbs.isEmpty()
                || !operation.routeBreadcrumbs.getFirst().equals(origin)) {
            operation.routeBreadcrumbs = new ArrayList<>(List.of(origin.immutable()));
            operation.rebuildRouteBreadcrumbIndices();
            changed = true;
        }
        if (changed) {
            touch(operation, gameTime);
        }
    }

    /**
     * Records one physically completed, player-walkable landing position.
     *
     * <p>The stored list is always the simple path from the operation entrance
     * to the most recent landing point. Revisiting an earlier breadcrumb erases
     * the loop suffix. Non-adjacent and vertical-shaft transitions are rejected
     * without mutating the durable route; callers can then keep mining while
     * reporting that no trustworthy return breadcrumb was committed.</p>
     *
     * @return {@code true} when the position is already current or was safely
     * appended/loop-erased, {@code false} for a discontinuous transition
     */
    public boolean appendRouteBreadcrumb(
            UUID operationId, BlockPos position, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        BlockPos next = Objects.requireNonNull(position, "position").immutable();
        if (operation.routeBreadcrumbs.isEmpty()) {
            operation.routeBreadcrumbs = new ArrayList<>(List.of(next));
            operation.rebuildRouteBreadcrumbIndices();
            touch(operation, gameTime);
            return true;
        }

        List<BlockPos> route = operation.routeBreadcrumbs;
        BlockPos current = route.getLast();
        if (current.equals(next)) {
            return true;
        }
        Integer previousIndex = operation.routeBreadcrumbIndices.get(next.asLong());
        if (previousIndex != null) {
            operation.routeBreadcrumbs = new ArrayList<>(
                    route.subList(0, previousIndex + 1));
            operation.rebuildRouteBreadcrumbIndices();
            touch(operation, gameTime);
            return true;
        }
        if (!isPlayerWalkableTransition(current, next)) {
            return false;
        }
        route.add(next);
        operation.routeBreadcrumbIndices.put(next.asLong(), route.size() - 1);
        touch(operation, gameTime);
        return true;
    }

    /** A breadcrumb edge is a cardinal one-block move with at most one Y step. */
    static boolean isPlayerWalkableTransition(BlockPos from, BlockPos to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        int horizontal = Math.abs(to.getX() - from.getX())
                + Math.abs(to.getZ() - from.getZ());
        return horizontal == 1 && Math.abs(to.getY() - from.getY()) <= 1;
    }

    /**
     * Updates the single moving workface node. Completed tunnel geometry is
     * represented by aggregate segments/junctions; retaining one WORKFACE node
     * per traversed block would turn this compact model into a path-node dump.
     */
    public UUID updateWorkface(UUID operationId, BlockPos position, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        Objects.requireNonNull(position, "position");
        UUID nodeId = deterministicWorkfaceId(operationId);
        TunnelNode previous = operation.nodes.get(nodeId);
        if (previous != null && previous.position().equals(position)
                && position.equals(operation.currentWorkfacePos)
                && nodeId.equals(operation.activeWorkfaceNodeId)) {
            return nodeId;
        }
        long discovered = previous == null ? gameTime : previous.discoveredGameTime();
        TunnelNode replacement = new TunnelNode(
                nodeId, NodeType.WORKFACE, position.immutable(), "", 0,
                discovered, gameTime);
        if (!replacement.equals(previous)) {
            operation.nodes.put(nodeId, replacement);
        }
        boolean changed = !position.equals(operation.currentWorkfacePos)
                || !nodeId.equals(operation.activeWorkfaceNodeId);
        operation.currentWorkfacePos = position.immutable();
        operation.activeWorkfaceNodeId = nodeId;
        if (changed) {
            touch(operation, gameTime);
        }
        return nodeId;
    }

    public void updateMainRoute(
            UUID operationId, String mainDirection, String shape,
            int segmentLength, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        String normalizedDirection = normalizedPlanText(mainDirection, "mainDirection");
        String normalizedShape = normalizedPlanText(shape, "shape");
        int normalizedLength = bounded(segmentLength, "segmentLength", 1, 64);
        if (!operation.mainDirection.equals(normalizedDirection)
                || !operation.shape.equals(normalizedShape)
                || operation.segmentLength != normalizedLength) {
            operation.mainDirection = normalizedDirection;
            operation.shape = normalizedShape;
            operation.segmentLength = normalizedLength;
            touch(operation, gameTime);
        }
    }

    public void markBlocked(UUID operationId, String reason, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        if (operation.status.terminal()) {
            throw new IllegalStateException("terminal mining operation cannot be blocked");
        }
        String normalized = normalizeText(reason, 256);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("blocked reason must not be empty");
        }
        operation.status = OperationStatus.BLOCKED;
        operation.phase = "blocked";
        operation.blockedReason = normalized;
        touch(operation, gameTime);
    }

    /**
     * Marks an intentional/terminal end. Normal server shutdown must not call
     * this; it releases the body lease while leaving the session resumable.
     */
    public void markTerminal(
            UUID operationId, OperationStatus terminalStatus,
            String reason, long gameTime) {
        assertMutationThread();
        MutableOperation operation = requireOperation(operationId);
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (!terminalStatus.terminal()) {
            throw new IllegalArgumentException("terminalStatus must be terminal");
        }
        if (operation.status.terminal() && operation.status != terminalStatus) {
            throw new IllegalStateException("mining operation already has a terminal status");
        }
        operation.status = terminalStatus;
        operation.phase = terminalStatus == OperationStatus.SUCCEEDED
                || terminalStatus == OperationStatus.COMPLETED
                ? "completed" : "terminal";
        operation.blockedReason = normalizeText(reason, 256);
        touch(operation, gameTime);
    }

    public boolean removeOperation(UUID operationId) {
        assertMutationThread();
        if (operations.remove(Objects.requireNonNull(operationId)) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public static MiningWorldModelSavedData load(
            CompoundTag root, HolderLookup.@NotNull Provider provider) {
        Objects.requireNonNull(root, "root");
        MiningWorldModelSavedData data = new MiningWorldModelSavedData(
                root.getString(KEY_DIMENSION));
        int schema = root.contains(KEY_SCHEMA, Tag.TAG_INT)
                ? root.getInt(KEY_SCHEMA) : 0;
        if (schema != SCHEMA_VERSION) {
            return data;
        }
        ListTag operationTags = root.getList(KEY_OPERATIONS, Tag.TAG_COMPOUND);
        for (int index = 0; index < operationTags.size(); index++) {
            MutableOperation operation = readOperation(
                    operationTags.getCompound(index), data.dimensionId);
            if (operation != null) {
                data.operations.putIfAbsent(operation.operationId, operation);
            }
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(
            CompoundTag root, HolderLookup.@NotNull Provider provider) {
        root.putInt(KEY_SCHEMA, SCHEMA_VERSION);
        root.putString(KEY_DIMENSION, dimensionId);
        ListTag operationTags = new ListTag();
        operations.values().stream()
                .sorted(Comparator.comparing(value -> value.operationId.toString()))
                .map(MiningWorldModelSavedData::writeOperation)
                .forEach(operationTags::add);
        root.put(KEY_OPERATIONS, operationTags);
        return root;
    }

    private void bind(String expectedDimension, Thread thread) {
        if (dimensionId.isEmpty()) {
            dimensionId = expectedDimension;
            setDirty();
        } else if (!dimensionId.equals(expectedDimension)) {
            throw new IllegalStateException(
                    "Mining graph dimension mismatch: " + dimensionId
                            + " != " + expectedDimension);
        }
        if (ownerThread != null && ownerThread != thread) {
            throw new IllegalStateException("Mining graph was rebound to another thread");
        }
        ownerThread = thread;
    }

    private void assertMutationThread() {
        if (ownerThread != null && ownerThread != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Mining world model mutations must run on the Minecraft server thread");
        }
    }

    private MutableOperation requireOperation(UUID operationId) {
        MutableOperation operation = operations.get(Objects.requireNonNull(operationId));
        if (operation == null) {
            throw new IllegalArgumentException("unknown mining operation UUID");
        }
        return operation;
    }

    private static UUID deterministicEntryId(UUID operationId) {
        return UUID.nameUUIDFromBytes(
                ("neko_tlm:mine_entry:" + operationId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static UUID deterministicWorkfaceId(UUID operationId) {
        return UUID.nameUUIDFromBytes(("neko_tlm:mine_workface:" + operationId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private void touch(MutableOperation operation, long gameTime) {
        operation.updatedGameTime = gameTime;
        setDirty();
    }

    private static CompoundTag writeOperation(MutableOperation operation) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(KEY_OPERATION_ID, operation.operationId);
        tag.putUUID(KEY_MAID_ID, operation.maidId);
        tag.putString(KEY_OPERATION_DIMENSION, operation.dimensionId);
        tag.putLong(KEY_GENERATION, operation.generation);
        tag.putString(KEY_STATUS, operation.status.serializedName());
        tag.putBoolean(KEY_ACTIVE, operation.status.active());
        tag.putBoolean(KEY_BLOCKED, operation.status.blocked());
        tag.putBoolean(KEY_TERMINAL, operation.status.terminal());
        if (!operation.blockedReason.isEmpty()) {
            tag.putString(KEY_BLOCKED_REASON, operation.blockedReason);
        }
        tag.putString(KEY_NORMALIZED_ARGS_JSON, operation.normalizedArgsJson);
        tag.putString(KEY_SELECTOR_JSON, operation.selectorJson);
        tag.putInt(KEY_TARGET_COUNT, operation.targetCount);
        tag.putInt(KEY_COLLECTED_COUNT, operation.collectedCount);
        tag.putString(KEY_PHASE, operation.phase);
        if (operation.originPos != null) {
            tag.put(KEY_ORIGIN, NbtUtils.writeBlockPos(operation.originPos));
        }
        if (operation.currentWorkfacePos != null) {
            tag.put(KEY_CURRENT_WORKFACE, NbtUtils.writeBlockPos(operation.currentWorkfacePos));
        }
        tag.putString(KEY_MAIN_DIRECTION, operation.mainDirection);
        tag.putString(KEY_SHAPE, operation.shape);
        tag.putInt(KEY_SEGMENT_LENGTH, operation.segmentLength);
        tag.putLong(KEY_SEGMENTS_DUG, operation.segmentsDug);
        tag.putLong(KEY_CLEARED_BLOCKS, operation.clearedBlocks);
        tag.putLong(KEY_PLACEMENTS_USED, operation.placementsUsed);
        tag.putLong(KEY_BRIDGE_SUPPORTS_PLACED, operation.bridgeSupportsPlaced);
        tag.putLong(KEY_WATER_SEALS_PLACED, operation.waterSealsPlaced);
        tag.putLongArray(KEY_VEIN_MEMBERS, operation.veinMembers.stream()
                .mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray(KEY_VEIN_HARVESTED_MEMBERS,
                operation.veinHarvestedMembers.stream()
                        .mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray(KEY_ROUTE_BREADCRUMBS,
                operation.routeBreadcrumbs.stream()
                        .mapToLong(BlockPos::asLong).toArray());
        tag.putLong(KEY_CREATED, operation.createdGameTime);
        tag.putLong(KEY_UPDATED, operation.updatedGameTime);
        if (operation.entryNodeId != null) {
            tag.putUUID(KEY_ENTRY_NODE, operation.entryNodeId);
        }
        if (operation.activeWorkfaceNodeId != null) {
            tag.putUUID(KEY_WORKFACE_NODE, operation.activeWorkfaceNodeId);
        }

        ListTag nodes = new ListTag();
        operation.nodes.values().stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .map(MiningWorldModelSavedData::writeNode)
                .forEach(nodes::add);
        tag.put(KEY_NODES, nodes);

        ListTag segments = new ListTag();
        operation.segments.values().stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .map(MiningWorldModelSavedData::writeSegment)
                .forEach(segments::add);
        tag.put(KEY_SEGMENTS, segments);

        ListTag dangers = new ListTag();
        operation.dangers.values().stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .map(MiningWorldModelSavedData::writeDanger)
                .forEach(dangers::add);
        tag.put(KEY_DANGERS, dangers);
        return tag;
    }

    private static List<BlockPos> immutableDistinctPositions(
            Collection<BlockPos> positions) {
        return positions.stream()
                .map(pos -> Objects.requireNonNull(pos, "position").immutable())
                .distinct()
                .sorted(Comparator.comparingLong(BlockPos::asLong))
                .toList();
    }

    private static List<BlockPos> readPositions(long[] packedPositions) {
        return immutableDistinctPositions(Arrays.stream(packedPositions)
                .mapToObj(BlockPos::of)
                .toList());
    }

    /** Restores only the longest valid loop-erased breadcrumb prefix. */
    private static List<BlockPos> readRouteBreadcrumbs(long[] packedPositions) {
        List<BlockPos> restored = new ArrayList<>();
        Map<Long, Integer> indices = new HashMap<>();
        for (long packed : packedPositions) {
            BlockPos next = BlockPos.of(packed).immutable();
            if (restored.isEmpty()) {
                restored.add(next);
                indices.put(next.asLong(), 0);
                continue;
            }
            BlockPos current = restored.getLast();
            if (current.equals(next)) {
                continue;
            }
            Integer previousIndex = indices.get(next.asLong());
            if (previousIndex != null) {
                restored.subList(previousIndex + 1, restored.size()).clear();
                indices.clear();
                for (int index = 0; index < restored.size(); index++) {
                    indices.put(restored.get(index).asLong(), index);
                }
                continue;
            }
            if (!isPlayerWalkableTransition(current, next)) {
                break;
            }
            restored.add(next);
            indices.put(next.asLong(), restored.size() - 1);
        }
        return restored;
    }

    private static MutableOperation readOperation(CompoundTag tag, String rootDimensionId) {
        if (!tag.hasUUID(KEY_OPERATION_ID) || !tag.hasUUID(KEY_MAID_ID)) {
            return null;
        }
        UUID operationId = tag.getUUID(KEY_OPERATION_ID);
        UUID maidId = tag.getUUID(KEY_MAID_ID);
        OperationStatus status = OperationStatus.fromTag(tag);
        String operationDimension = normalizeText(
                tag.getString(KEY_OPERATION_DIMENSION), 256);
        if (operationDimension.isEmpty()) {
            operationDimension = rootDimensionId;
        }
        UUID entryId = tag.hasUUID(KEY_ENTRY_NODE) ? tag.getUUID(KEY_ENTRY_NODE) : null;
        UUID workfaceId = tag.hasUUID(KEY_WORKFACE_NODE) ? tag.getUUID(KEY_WORKFACE_NODE) : null;
        BlockPos origin = NbtUtils.readBlockPos(tag, KEY_ORIGIN).orElse(null);
        BlockPos workface = NbtUtils.readBlockPos(tag, KEY_CURRENT_WORKFACE).orElse(null);
        MutableOperation operation = new MutableOperation(
                operationId, maidId, operationDimension,
                Math.max(0L, tag.getLong(KEY_GENERATION)), status,
                normalizedArgsJson(tag.getString(KEY_NORMALIZED_ARGS_JSON)),
                normalizedSelectorJson(tag.getString(KEY_SELECTOR_JSON)),
                Math.max(0, tag.getInt(KEY_TARGET_COUNT)),
                Math.max(0, tag.getInt(KEY_COLLECTED_COUNT)),
                normalizedPhase(tag.getString(KEY_PHASE)),
                origin, workface,
                defaultPlanText(tag.getString(KEY_MAIN_DIRECTION), "auto"),
                defaultPlanText(tag.getString(KEY_SHAPE), "auto"),
                Math.max(1, Math.min(64, tag.getInt(KEY_SEGMENT_LENGTH))),
                Math.max(0L, tag.getLong(KEY_SEGMENTS_DUG)),
                Math.max(0L, tag.getLong(KEY_CLEARED_BLOCKS)),
                Math.max(0L, tag.getLong(KEY_PLACEMENTS_USED)),
                Math.max(0L, tag.getLong(KEY_BRIDGE_SUPPORTS_PLACED)),
                Math.max(0L, tag.getLong(KEY_WATER_SEALS_PLACED)),
                readPositions(tag.getLongArray(KEY_VEIN_MEMBERS)),
                readPositions(tag.getLongArray(KEY_VEIN_HARVESTED_MEMBERS)),
                readRouteBreadcrumbs(tag.getLongArray(KEY_ROUTE_BREADCRUMBS)),
                normalizeText(tag.getString(KEY_BLOCKED_REASON), 256),
                tag.getLong(KEY_CREATED), tag.getLong(KEY_UPDATED),
                entryId, workfaceId);
        if (operation.originPos != null
                && (operation.routeBreadcrumbs.isEmpty()
                || !operation.routeBreadcrumbs.getFirst()
                .equals(operation.originPos))) {
            operation.routeBreadcrumbs = new ArrayList<>(
                    List.of(operation.originPos));
            operation.rebuildRouteBreadcrumbIndices();
        }

        ListTag nodes = tag.getList(KEY_NODES, Tag.TAG_COMPOUND);
        for (int index = 0; index < nodes.size(); index++) {
            TunnelNode node = readNode(nodes.getCompound(index));
            if (node != null) {
                operation.nodes.putIfAbsent(node.id(), node);
            }
        }
        if (operation.entryNodeId == null
                || !operation.nodes.containsKey(operation.entryNodeId)) {
            operation.entryNodeId = operation.nodes.values().stream()
                    .filter(node -> node.type() == NodeType.ENTRY)
                    .map(TunnelNode::id)
                    .findFirst().orElse(null);
        }
        if (operation.originPos == null && operation.entryNodeId != null) {
            operation.originPos = operation.nodes.get(operation.entryNodeId).position();
        }
        if (operation.activeWorkfaceNodeId != null) {
            TunnelNode workfaceNode = operation.nodes.get(operation.activeWorkfaceNodeId);
            if (workfaceNode == null || workfaceNode.type() != NodeType.WORKFACE) {
                operation.activeWorkfaceNodeId = null;
            } else if (operation.currentWorkfacePos == null) {
                operation.currentWorkfacePos = workfaceNode.position();
            }
        }

        ListTag segments = tag.getList(KEY_SEGMENTS, Tag.TAG_COMPOUND);
        for (int index = 0; index < segments.size(); index++) {
            TunnelSegment segment = readSegment(segments.getCompound(index));
            if (segment != null
                    && operation.nodes.containsKey(segment.fromNodeId())
                    && operation.nodes.containsKey(segment.toNodeId())
                    && !segment.fromNodeId().equals(segment.toNodeId())) {
                operation.segments.putIfAbsent(segment.id(), segment);
            }
        }

        ListTag dangers = tag.getList(KEY_DANGERS, Tag.TAG_COMPOUND);
        for (int index = 0; index < dangers.size(); index++) {
            DangerRecord danger = readDanger(dangers.getCompound(index));
            if (danger != null) {
                operation.dangers.putIfAbsent(danger.id(), danger);
            }
        }
        return operation;
    }

    private static CompoundTag writeNode(TunnelNode node) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(KEY_ID, node.id());
        tag.putString(KEY_TYPE, node.type().serializedName());
        tag.put(KEY_POS, NbtUtils.writeBlockPos(node.position()));
        if (!node.resourceKey().isEmpty()) {
            tag.putString(KEY_RESOURCE, node.resourceKey());
        }
        if (node.estimate() > 0) {
            tag.putInt(KEY_ESTIMATE, node.estimate());
        }
        tag.putLong(KEY_CREATED, node.discoveredGameTime());
        tag.putLong(KEY_UPDATED, node.updatedGameTime());
        return tag;
    }

    private static TunnelNode readNode(CompoundTag tag) {
        if (!tag.hasUUID(KEY_ID)) {
            return null;
        }
        Optional<NodeType> type = NodeType.tryParse(tag.getString(KEY_TYPE));
        BlockPos position = NbtUtils.readBlockPos(tag, KEY_POS).orElse(null);
        if (type.isEmpty() || position == null) {
            return null;
        }
        return new TunnelNode(
                tag.getUUID(KEY_ID), type.get(), position.immutable(),
                normalizeText(tag.getString(KEY_RESOURCE), 256),
                Math.max(0, tag.getInt(KEY_ESTIMATE)),
                tag.getLong(KEY_CREATED), tag.getLong(KEY_UPDATED));
    }

    private static CompoundTag writeSegment(TunnelSegment segment) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(KEY_ID, segment.id());
        tag.putUUID(KEY_FROM, segment.fromNodeId());
        tag.putUUID(KEY_TO, segment.toNodeId());
        tag.putInt(KEY_LENGTH, segment.length());
        tag.putInt(KEY_WIDTH, segment.width());
        tag.putInt(KEY_HEIGHT, segment.height());
        tag.putBoolean(KEY_TRAVERSABLE, segment.traversable());
        tag.putLong(KEY_UPDATED, segment.updatedGameTime());
        return tag;
    }

    private static TunnelSegment readSegment(CompoundTag tag) {
        if (!tag.hasUUID(KEY_ID) || !tag.hasUUID(KEY_FROM) || !tag.hasUUID(KEY_TO)) {
            return null;
        }
        int length = tag.getInt(KEY_LENGTH);
        int width = tag.getInt(KEY_WIDTH);
        int height = tag.getInt(KEY_HEIGHT);
        if (length < 1 || width < 1 || width > 8 || height < 1 || height > 8) {
            return null;
        }
        return new TunnelSegment(
                tag.getUUID(KEY_ID), tag.getUUID(KEY_FROM), tag.getUUID(KEY_TO),
                length, width, height, tag.getBoolean(KEY_TRAVERSABLE),
                tag.getLong(KEY_UPDATED));
    }

    private static CompoundTag writeDanger(DangerRecord danger) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(KEY_ID, danger.id());
        tag.putString(KEY_TYPE, danger.type().serializedName());
        tag.putString(KEY_SEVERITY, danger.severity().serializedName());
        tag.put(KEY_POS, NbtUtils.writeBlockPos(danger.anchor()));
        tag.putInt(KEY_RADIUS, danger.radius());
        tag.putBoolean(KEY_RESOLVED, danger.resolved());
        if (!danger.note().isEmpty()) {
            tag.putString(KEY_NOTE, danger.note());
        }
        tag.putLong(KEY_UPDATED, danger.updatedGameTime());
        return tag;
    }

    private static DangerRecord readDanger(CompoundTag tag) {
        if (!tag.hasUUID(KEY_ID)) {
            return null;
        }
        BlockPos anchor = NbtUtils.readBlockPos(tag, KEY_POS).orElse(null);
        if (anchor == null) {
            return null;
        }
        return new DangerRecord(
                tag.getUUID(KEY_ID),
                DangerType.fromSerialized(tag.getString(KEY_TYPE)),
                DangerSeverity.fromSerialized(tag.getString(KEY_SEVERITY)),
                anchor.immutable(), Math.max(0, Math.min(64, tag.getInt(KEY_RADIUS))),
                tag.getBoolean(KEY_RESOLVED),
                normalizeText(tag.getString(KEY_NOTE), 512),
                tag.getLong(KEY_UPDATED));
    }

    private static int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int bounded(int value, String name, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static String normalizeText(String value, int maximumLength) {
        String normalized = String.valueOf(value == null ? "" : value).trim();
        return normalized.length() <= maximumLength
                ? normalized : normalized.substring(0, maximumLength);
    }

    private static String normalizedSelectorJson(String value) {
        String normalized = normalizeText(value, 2048);
        return normalized.isEmpty() ? "{}" : normalized;
    }

    private static String normalizedArgsJson(JsonObject value) {
        JsonObject migrated = Objects.requireNonNull(value, "value").deepCopy();
        // Canonicalize newly introduced autonomous-mining defaults so an old
        // crash checkpoint can be resumed by a newer action factory without
        // being rejected as an action-ID/argument conflict.
        if (migrated.has("selector") && migrated.has("target_count")) {
            if (!migrated.has("placement_policy")) {
                migrated.addProperty(
                        "placement_policy", "disabled");
            }
            if (!migrated.has("max_placements")) {
                migrated.addProperty("max_placements", 0);
            }
        }
        String serialized = canonicalJson(migrated).toString();
        if (serialized.length() > 8192) {
            throw new IllegalArgumentException("normalized mining args exceed 8192 characters");
        }
        return serialized;
    }

    private static String normalizedArgsJson(String value) {
        String normalized = String.valueOf(value == null ? "" : value).trim();
        if (normalized.isEmpty()) {
            return "{}";
        }
        if (normalized.length() > 8192) {
            return "{}";
        }
        try {
            JsonElement parsed = JsonParser.parseString(normalized);
            return parsed.isJsonObject()
                    ? normalizedArgsJson(parsed.getAsJsonObject()) : "{}";
        } catch (RuntimeException invalid) {
            return "{}";
        }
    }

    private static JsonElement canonicalJson(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            value.getAsJsonObject().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.add(
                            entry.getKey(), canonicalJson(entry.getValue())));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) {
                result.add(canonicalJson(child));
            }
            return result;
        }
        return value.deepCopy();
    }

    private static String normalizedPhase(String value) {
        return defaultPlanText(value, "validating");
    }

    private static String normalizedPlanText(String value, String name) {
        String normalized = normalizeText(value, 64).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return normalized;
    }

    private static String defaultPlanText(String value, String fallback) {
        String normalized = normalizeText(value, 64).toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? fallback : normalized;
    }

    public enum NodeType {
        ENTRY,
        JUNCTION,
        WORKFACE,
        VEIN,
        SUPPLY;

        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<NodeType> tryParse(String value) {
            try {
                return Optional.of(valueOf(String.valueOf(value).toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
    }

    public enum OperationStatus {
        ACTIVE,
        BLOCKED,
        PAUSED,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        SUPERSEDED,
        COMPLETED,
        ABANDONED;

        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean active() {
            return !terminal();
        }

        public boolean blocked() {
            return this == BLOCKED;
        }

        public boolean terminal() {
            return switch (this) {
                case SUCCEEDED, FAILED, CANCELLED, SUPERSEDED,
                        COMPLETED, ABANDONED -> true;
                case ACTIVE, BLOCKED, PAUSED -> false;
            };
        }

        public boolean resumable() {
            return !terminal();
        }

        static OperationStatus fromSerialized(String value) {
            try {
                return valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return PAUSED;
            }
        }

        static OperationStatus fromTag(CompoundTag tag) {
            String serialized = tag.getString(KEY_STATUS);
            if (!serialized.isEmpty()) {
                return fromSerialized(serialized);
            }
            if (tag.getBoolean(KEY_TERMINAL)) {
                return FAILED;
            }
            if (tag.getBoolean(KEY_BLOCKED)) {
                return BLOCKED;
            }
            return tag.getBoolean(KEY_ACTIVE) ? ACTIVE : PAUSED;
        }
    }

    public enum DangerType {
        LAVA,
        WATER,
        FALL,
        HOSTILE,
        UNSTABLE,
        PROTECTED,
        OBSTRUCTION,
        UNKNOWN;

        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        static DangerType fromSerialized(String value) {
            try {
                return valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return UNKNOWN;
            }
        }
    }

    public enum DangerSeverity {
        INFO,
        CAUTION,
        DANGEROUS,
        FATAL;

        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        static DangerSeverity fromSerialized(String value) {
            try {
                return valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return CAUTION;
            }
        }
    }

    public record TunnelNode(
            UUID id,
            NodeType type,
            BlockPos position,
            String resourceKey,
            int estimate,
            long discoveredGameTime,
            long updatedGameTime) {
        public TunnelNode {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            position = Objects.requireNonNull(position, "position").immutable();
            resourceKey = normalizeText(resourceKey, 256);
            estimate = Math.max(0, estimate);
        }
    }

    public record TunnelSegment(
            UUID id,
            UUID fromNodeId,
            UUID toNodeId,
            int length,
            int width,
            int height,
            boolean traversable,
            long updatedGameTime) {
        public TunnelSegment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(fromNodeId, "fromNodeId");
            Objects.requireNonNull(toNodeId, "toNodeId");
        }
    }

    public record DangerRecord(
            UUID id,
            DangerType type,
            DangerSeverity severity,
            BlockPos anchor,
            int radius,
            boolean resolved,
            String note,
            long updatedGameTime) {
        public DangerRecord {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(severity, "severity");
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
            radius = Math.max(0, Math.min(64, radius));
            note = normalizeText(note, 512);
        }
    }

    public record OperationSnapshot(
            UUID operationId,
            UUID maidId,
            String dimensionId,
            long generation,
            OperationStatus status,
            String normalizedArgsJson,
            String selectorJson,
            int targetCount,
            int collectedCount,
            String phase,
            BlockPos originPos,
            BlockPos currentWorkfacePos,
            String mainDirection,
            String shape,
            int segmentLength,
            long segmentsDug,
            long clearedBlocks,
            long placementsUsed,
            long bridgeSupportsPlaced,
            long waterSealsPlaced,
            List<BlockPos> veinMembers,
            List<BlockPos> veinHarvestedMembers,
            List<BlockPos> routeBreadcrumbs,
            String blockedReason,
            long createdGameTime,
            long updatedGameTime,
            UUID entryNodeId,
            UUID activeWorkfaceNodeId,
            Map<UUID, TunnelNode> nodes,
            Map<UUID, TunnelSegment> segments,
            Map<UUID, DangerRecord> dangers) {
        public OperationSnapshot {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(maidId, "maidId");
            dimensionId = normalizeText(dimensionId, 256);
            Objects.requireNonNull(status, "status");
            normalizedArgsJson = MiningWorldModelSavedData
                    .normalizedArgsJson(normalizedArgsJson);
            selectorJson = normalizedSelectorJson(selectorJson);
            phase = normalizedPhase(phase);
            originPos = originPos == null ? null : originPos.immutable();
            currentWorkfacePos = currentWorkfacePos == null
                    ? null : currentWorkfacePos.immutable();
            mainDirection = defaultPlanText(mainDirection, "auto");
            shape = defaultPlanText(shape, "auto");
            veinMembers = immutableDistinctPositions(veinMembers);
            veinHarvestedMembers = immutableDistinctPositions(veinHarvestedMembers);
            routeBreadcrumbs = List.copyOf(
                    Objects.requireNonNull(routeBreadcrumbs, "routeBreadcrumbs"));
            blockedReason = normalizeText(blockedReason, 256);
            nodes = Map.copyOf(nodes);
            segments = Map.copyOf(segments);
            dangers = Map.copyOf(dangers);
        }

        public boolean active() {
            return status.active();
        }

        public boolean blocked() {
            return status.blocked();
        }

        public boolean terminal() {
            return status.terminal();
        }

        /** Deep copy suitable for replaying the same autonomous action factory. */
        public JsonObject normalizedArgs() {
            return JsonParser.parseString(normalizedArgsJson)
                    .getAsJsonObject().deepCopy();
        }
    }

    private static final class MutableOperation {
        private final UUID operationId;
        private final UUID maidId;
        private final String dimensionId;
        private long generation;
        private OperationStatus status;
        private final String normalizedArgsJson;
        private final String selectorJson;
        private final int targetCount;
        private int collectedCount;
        private String phase;
        private BlockPos originPos;
        private BlockPos currentWorkfacePos;
        private String mainDirection;
        private String shape;
        private int segmentLength;
        private long segmentsDug;
        private long clearedBlocks;
        private long placementsUsed;
        private long bridgeSupportsPlaced;
        private long waterSealsPlaced;
        private List<BlockPos> veinMembers;
        private List<BlockPos> veinHarvestedMembers;
        private List<BlockPos> routeBreadcrumbs;
        private final Map<Long, Integer> routeBreadcrumbIndices = new HashMap<>();
        private String blockedReason;
        private final long createdGameTime;
        private long updatedGameTime;
        private UUID entryNodeId;
        private UUID activeWorkfaceNodeId;
        private final Map<UUID, TunnelNode> nodes = new LinkedHashMap<>();
        private final Map<UUID, TunnelSegment> segments = new LinkedHashMap<>();
        private final Map<UUID, DangerRecord> dangers = new LinkedHashMap<>();

        private MutableOperation(
                UUID operationId, UUID maidId, String dimensionId,
                long generation, OperationStatus status,
                String normalizedArgsJson, String selectorJson,
                int targetCount, int collectedCount,
                String phase, BlockPos originPos, BlockPos currentWorkfacePos,
                String mainDirection, String shape, int segmentLength,
                long segmentsDug, long clearedBlocks,
                long placementsUsed, long bridgeSupportsPlaced,
                long waterSealsPlaced, List<BlockPos> veinMembers,
                List<BlockPos> veinHarvestedMembers,
                List<BlockPos> routeBreadcrumbs, String blockedReason,
                long createdGameTime, long updatedGameTime,
                UUID entryNodeId, UUID activeWorkfaceNodeId) {
            this.operationId = operationId;
            this.maidId = maidId;
            this.dimensionId = normalizeText(dimensionId, 256);
            this.generation = Math.max(0L, generation);
            this.status = status;
            this.normalizedArgsJson = normalizedArgsJson(normalizedArgsJson);
            this.selectorJson = normalizedSelectorJson(selectorJson);
            this.targetCount = Math.max(0, targetCount);
            this.collectedCount = Math.max(0, collectedCount);
            this.phase = normalizedPhase(phase);
            this.originPos = originPos == null ? null : originPos.immutable();
            this.currentWorkfacePos = currentWorkfacePos == null
                    ? null : currentWorkfacePos.immutable();
            this.mainDirection = defaultPlanText(mainDirection, "auto");
            this.shape = defaultPlanText(shape, "auto");
            this.segmentLength = Math.max(1, Math.min(64, segmentLength));
            this.segmentsDug = Math.max(0L, segmentsDug);
            this.clearedBlocks = Math.max(0L, clearedBlocks);
            this.placementsUsed = Math.max(0L, placementsUsed);
            this.bridgeSupportsPlaced = Math.max(0L, bridgeSupportsPlaced);
            this.waterSealsPlaced = Math.max(0L, waterSealsPlaced);
            this.veinMembers = immutableDistinctPositions(veinMembers);
            this.veinHarvestedMembers = immutableDistinctPositions(
                    veinHarvestedMembers);
            this.routeBreadcrumbs = new ArrayList<>(
                    Objects.requireNonNull(routeBreadcrumbs, "routeBreadcrumbs"));
            rebuildRouteBreadcrumbIndices();
            this.blockedReason = normalizeText(blockedReason, 256);
            this.createdGameTime = createdGameTime;
            this.updatedGameTime = updatedGameTime;
            this.entryNodeId = entryNodeId;
            this.activeWorkfaceNodeId = activeWorkfaceNodeId;
        }

        private void rebuildRouteBreadcrumbIndices() {
            routeBreadcrumbIndices.clear();
            for (int index = 0; index < routeBreadcrumbs.size(); index++) {
                routeBreadcrumbIndices.put(routeBreadcrumbs.get(index).asLong(), index);
            }
        }

        private OperationSnapshot snapshot() {
            return new OperationSnapshot(
                    operationId, maidId, dimensionId, generation, status,
                    normalizedArgsJson, selectorJson, targetCount, collectedCount, phase,
                    originPos, currentWorkfacePos, mainDirection, shape,
                    segmentLength, segmentsDug, clearedBlocks,
                    placementsUsed, bridgeSupportsPlaced, waterSealsPlaced,
                    veinMembers, veinHarvestedMembers, routeBreadcrumbs,
                    blockedReason,
                    createdGameTime, updatedGameTime,
                    entryNodeId, activeWorkfaceNodeId,
                    new LinkedHashMap<>(nodes),
                    new LinkedHashMap<>(segments),
                    new LinkedHashMap<>(dangers));
        }
    }

    private record SessionArguments(
            String normalizedArgsJson,
            String selectorJson,
            int targetCount,
            String mainDirection,
            String shape,
            int segmentLength) {
        private static SessionArguments from(JsonObject args) {
            String normalizedArgsJson = MiningWorldModelSavedData
                    .normalizedArgsJson(args);
            JsonElement selector = args.get("selector");
            String selectorJson = selector == null || selector.isJsonNull()
                    ? "{}" : normalizedSelectorJson(selector.toString());
            int targetCount = intArgument(args, "target_count",
                    intArgument(args, "max_blocks", 1));
            if (targetCount < 1) {
                throw new IllegalArgumentException("target_count must be positive");
            }
            String direction = stringArgument(args, "direction", "auto");
            String shape = stringArgument(args, "shape", "auto");
            int segmentLength = intArgument(args, "segment_length", 8);
            if (segmentLength < 1 || segmentLength > 64) {
                throw new IllegalArgumentException(
                        "segment_length must be between 1 and 64");
            }
            return new SessionArguments(normalizedArgsJson, selectorJson, targetCount,
                    normalizedPlanText(direction, "direction"),
                    normalizedPlanText(shape, "shape"), segmentLength);
        }

        private static int intArgument(JsonObject args, String name, int fallback) {
            if (!args.has(name)) {
                return fallback;
            }
            try {
                return args.get(name).getAsInt();
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(name + " must be an integer", invalid);
            }
        }

        private static String stringArgument(
                JsonObject args, String name, String fallback) {
            if (!args.has(name)) {
                return fallback;
            }
            try {
                return args.get(name).getAsString();
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(name + " must be a string", invalid);
            }
        }
    }
}
