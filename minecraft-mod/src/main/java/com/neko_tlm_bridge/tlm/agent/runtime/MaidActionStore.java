package com.neko_tlm_bridge.tlm.agent.runtime;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.NekoWebSocketServerHolder;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.ActionStatus;
import com.neko_tlm_bridge.tlm.agent.MaidAction;
import com.neko_tlm_bridge.tlm.agent.MaidActionContext;
import com.neko_tlm_bridge.tlm.agent.MaidActionExecution;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.MaidActionResource;
import com.neko_tlm_bridge.tlm.agent.MaidActionTickResult;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Main-thread action registry, state machine, and sole terminal-state owner. */
public final class MaidActionStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("NekoTlmBridge");
    private static final MaidActionStore INSTANCE = new MaidActionStore();
    private static final long TERMINAL_TTL_MS = 10 * 60 * 1000L;
    private static final long MIN_TIMEOUT_MS = 1_000L;
    private static final long MAX_TIMEOUT_MS = 120_000L;
    private static final long PROGRESS_HEARTBEAT_TICKS = 40L;

    private final Map<MaidActionKind, MaidActionFactory> factories = new EnumMap<>(MaidActionKind.class);
    private final Map<MaidActionKind, IMaidTask> appliedTasks = new EnumMap<>(MaidActionKind.class);
    private final Map<UUID, ActiveAction> activeByMaid = new HashMap<>();
    private final Map<UUID, ActionRecord> recordsByAction = new HashMap<>();
    private final Map<UUID, Long> generationsByMaid = new HashMap<>();

    private MaidActionStore() {
    }

    public static MaidActionStore getInstance() {
        return INSTANCE;
    }

    public synchronized void registerFactory(MaidActionKind kind, MaidActionFactory factory) {
        registerFactory(kind, factory, TaskManager.getIdleTask());
    }

    /** Registers both the action constructor and the temporary TLM task it requires. */
    public synchronized void registerFactory(MaidActionKind kind, MaidActionFactory factory,
                                             IMaidTask appliedTask) {
        factories.put(Objects.requireNonNull(kind), Objects.requireNonNull(factory));
        appliedTasks.put(kind, Objects.requireNonNull(appliedTask));
    }

    public synchronized void unregisterFactory(MaidActionKind kind) {
        factories.remove(kind);
        appliedTasks.remove(kind);
    }

    public StartResult start(UUID actionId, EntityMaid maid, MaidActionKind kind,
                             JsonObject args, long timeoutMs, boolean replaceExisting) {
        requireServerThread(maid.getServer());
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(maid, "maid");
        Objects.requireNonNull(kind, "kind");
        recoverOrphanLease(maid);
        JsonObject safeArgs = args == null ? new JsonObject() : args.deepCopy();
        String requestFingerprint = requestFingerprint(maid.getUUID(), kind, safeArgs, timeoutMs, replaceExisting);

        ActionRecord existing = recordsByAction.get(actionId);
        if (existing != null) {
            if (existing.requestFingerprint.equals(requestFingerprint)) {
                return new StartResult(true, null, existing.snapshot());
            }
            return new StartResult(false, "ACTION_ID_CONFLICT", existing.snapshot());
        }

        MaidActionFactory factory = factories.get(kind);
        if (factory == null) {
            return new StartResult(false, "ACTION_KIND_UNAVAILABLE", null);
        }

        MaidAction action;
        try {
            action = Objects.requireNonNull(factory.create(maid, safeArgs), "factory returned null");
        } catch (RuntimeException invalid) {
            LOGGER.debug("Rejected maid action {}: {}", actionId, invalid.getMessage());
            return new StartResult(false, invalid.getMessage() == null ? "VALIDATION_FAILED" : invalid.getMessage(), null);
        }

        // Factory construction is the validation boundary. Never supersede a
        // healthy action merely because the replacement request is malformed.
        ActiveAction previous = activeByMaid.get(maid.getUUID());
        if (previous != null) {
            if (!replaceExisting) {
                return new StartResult(false, "MAID_BUSY", previous.snapshot());
            }
            terminate(previous, ActionStatus.SUPERSEDED, ActionEndReason.SUPERSEDED, new JsonObject());
        }

        long generation = generationsByMaid.merge(maid.getUUID(), 1L, Long::sum);
        long started = maid.level().getGameTime();
        long deadline;
        if (timeoutMs == 0L) {
            deadline = Long.MAX_VALUE;
        } else {
            long clampedTimeout = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeoutMs));
            deadline = started + Math.max(1L, (clampedTimeout + 49L) / 50L);
        }
        MaidBodyLease lease;
        try {
            lease = MaidBodyLease.acquire(maid, actionId, generation,
                    appliedTaskFor(kind, maid));
        } catch (RuntimeException failure) {
            LOGGER.error("Failed to acquire maid body lease for {}", actionId, failure);
            recoverOrphanLease(maid);
            return new StartResult(false, "LEASE_ACQUIRE_FAILED", null);
        }

        ActiveAction active = new ActiveAction(actionId, maid, kind, action, safeArgs,
                requestFingerprint, generation, started, deadline, lease);
        activeByMaid.put(maid.getUUID(), active);
        recordsByAction.put(actionId, active);
        try {
            action.start(active.context(started));
            transition(active, ActionStatus.RUNNING);
            if ("PENDING".equals(active.stage)) {
                active.stage = "RUNNING";
            }
            active.eventsEnabled = true;
            return new StartResult(true, null, active.snapshot());
        } catch (RuntimeException failure) {
            LOGGER.error("Failed to start maid action {}", actionId, failure);
            JsonObject result = new JsonObject();
            result.addProperty("message", failure.getMessage());
            terminate(active, ActionStatus.FAILED, ActionEndReason.INTERNAL_ERROR, result);
            return new StartResult(false, "INTERNAL_ERROR", active.snapshot());
        }
    }

    public CancelResult requestCancel(UUID actionId) {
        ActionRecord record = recordsByAction.get(actionId);
        if (!(record instanceof ActiveAction active)) {
            return new CancelResult(record != null, record == null ? "ACTION_NOT_FOUND" : null,
                    record == null ? null : record.snapshot());
        }
        if (active.status.isTerminal()) {
            return new CancelResult(true, null, active.snapshot());
        }
        if (active.status == ActionStatus.CANCEL_REQUESTED || active.status == ActionStatus.TERMINATING) {
            return new CancelResult(true, null, active.snapshot());
        }
        transition(active, ActionStatus.CANCEL_REQUESTED);
        active.stage = "CANCEL_REQUESTED";
        return new CancelResult(true, null, active.snapshot());
    }

    /**
     * Immediately terminates every active Agent action owned by the requesting
     * player. This is the server-authoritative emergency-stop path used by the
     * client key binding, so it deliberately does not depend on WebSocket,
     * Python, or waiting for the maid behavior's next tick.
     */
    public int emergencyStopOwnedBy(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        int stopped = 0;
        for (ActiveAction active : new ArrayList<>(activeByMaid.values())) {
            UUID maidOwnerId = active.maid.getOwnerUUID();
            if (!ownerId.equals(maidOwnerId)) {
                continue;
            }
            JsonObject result = new JsonObject();
            result.addProperty("source", "client_emergency_stop");
            terminate(active, ActionStatus.CANCELLED, ActionEndReason.REQUESTED, result);
            stopped++;
        }
        return stopped;
    }

    public Optional<JsonObject> getStatus(UUID actionId) {
        ActionRecord record = recordsByAction.get(actionId);
        return record == null ? Optional.empty() : Optional.of(record.snapshot());
    }

    public JsonArray listActive(UUID maidFilter) {
        JsonArray result = new JsonArray();
        for (ActiveAction active : activeByMaid.values()) {
            if (maidFilter == null || maidFilter.equals(active.maidId)) {
                result.add(active.snapshot());
            }
        }
        return result;
    }

    public boolean hasActiveResource(UUID maidId, MaidActionResource resource) {
        ActiveAction active = activeByMaid.get(maidId);
        return active != null && active.status == ActionStatus.RUNNING
                && active.action.resources().contains(resource);
    }

    /** Used by the TLM schedule guard during the maid's entity tick. */
    public boolean hasActiveMaidAction(UUID maidId) {
        ActiveAction active = activeByMaid.get(maidId);
        return active != null && !active.status.isTerminal();
    }

    /**
     * Reconciles an entity-load event with the volatile runtime. A different
     * entity instance carrying the same maid UUID means the old instance was
     * unloaded or changed dimension before the server tick observed removal.
     * Returns true only when this exact instance already owns the action.
     */
    public boolean reconcileLoadedEntity(EntityMaid maid) {
        Objects.requireNonNull(maid, "maid");
        ActiveAction active = activeByMaid.get(maid.getUUID());
        if (active == null || active.status.isTerminal()) {
            return false;
        }
        if (active.maid == maid) {
            return true;
        }
        if (active.kind == MaidActionKind.AUTONOMOUS_MINING) {
            suspendForRecovery(active, ActionEndReason.ENTITY_UNLOADED, false);
        } else {
            terminate(active, ActionStatus.FAILED, ActionEndReason.ENTITY_UNLOADED,
                    new JsonObject(), false);
        }
        return false;
    }

    public Optional<JsonObject> getActiveStatus(UUID maidId) {
        ActiveAction active = activeByMaid.get(maidId);
        return active == null ? Optional.empty() : Optional.of(active.snapshot());
    }

    /**
     * Raises, but never lowers, the generation counter before a persisted
     * long-running action is reconstructed. The next {@link #start} therefore
     * emits a generation strictly newer than every pre-restart event.
     */
    public void ensureGenerationAtLeast(UUID maidId, long generation) {
        Objects.requireNonNull(maidId, "maidId");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        generationsByMaid.merge(maidId, generation, Math::max);
    }

    public boolean attachHandLease(UUID actionId, long generation, HandLease handLease) {
        ActionRecord record = recordsByAction.get(actionId);
        if (!(record instanceof ActiveAction active) || active.generation != generation
                || active.status.isTerminal()) {
            return false;
        }
        active.lease.attachHandLease(active.maid, handLease);
        return true;
    }

    /** Called by the single NekoAgentBehavior on the server thread. */
    public boolean tickMaid(ServerLevel level, EntityMaid maid, long gameTime) {
        ActiveAction active = activeByMaid.get(maid.getUUID());
        if (active == null) {
            recoverOrphanLease(maid);
            return false;
        }
        if (active.generation != generationsByMaid.getOrDefault(maid.getUUID(), 0L)) {
            terminate(active, ActionStatus.SUPERSEDED, ActionEndReason.SUPERSEDED, new JsonObject());
            return false;
        }
        if (maid.isDeadOrDying() || !maid.isAlive()) {
            terminate(active, ActionStatus.FAILED, ActionEndReason.ENTITY_DEAD, new JsonObject());
            return false;
        }
        boolean drowning = maid.isUnderWater()
                && maid.getAirSupply() < Math.max(20, maid.getMaxAirSupply() / 2);
        if (maid.isOnFire() || maid.isInLava() || drowning
                || maid.getBrain().isActive(Activity.PANIC)) {
            terminate(active, ActionStatus.CANCELLED, ActionEndReason.SAFETY_PREEMPTED, new JsonObject());
            return false;
        }
        if (!active.lease.controlFieldsUnchanged(maid)) {
            terminate(active, ActionStatus.CANCELLED, ActionEndReason.USER_OVERRIDE, new JsonObject());
            return false;
        }
        if (active.lease.handLease() != null
                && active.lease.handLease().validate(maid) != HandLease.LeaseHealth.HEALTHY) {
            terminate(active, ActionStatus.FAILED, ActionEndReason.HAND_CONFLICT, new JsonObject());
            return false;
        }
        active.lease.maintainRestriction(maid);
        if (active.status == ActionStatus.CANCEL_REQUESTED) {
            terminate(active, ActionStatus.CANCELLED, ActionEndReason.REQUESTED, new JsonObject());
            return false;
        }
        if (gameTime >= active.deadlineGameTime) {
            terminate(active, ActionStatus.TIMEOUT, ActionEndReason.TIMEOUT, new JsonObject());
            return false;
        }

        try {
            MaidActionTickResult tickResult = active.action.tick(active.context(gameTime));
            if (tickResult == null || tickResult.outcome() == MaidActionTickResult.Outcome.RUNNING) {
                active.maybeHeartbeat(gameTime);
                return true;
            }
            if (tickResult.outcome() == MaidActionTickResult.Outcome.SUCCEEDED) {
                terminate(active, ActionStatus.SUCCEEDED, ActionEndReason.COMPLETED, tickResult.result());
            } else {
                terminate(active, ActionStatus.FAILED,
                        tickResult.reason() == null ? ActionEndReason.INTERNAL_ERROR : tickResult.reason(),
                        tickResult.result());
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Maid action tick failed: {}", active.actionId, failure);
            JsonObject result = new JsonObject();
            result.addProperty("message", failure.getMessage());
            terminate(active, ActionStatus.FAILED, ActionEndReason.INTERNAL_ERROR, result);
        }
        return false;
    }

    /** Server-tick maintenance: TTL cleanup and unloaded-entity termination. */
    public void tick(MinecraftServer server) {
        requireServerThread(server);
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, ActionRecord>> iterator = recordsByAction.entrySet().iterator();
        while (iterator.hasNext()) {
            ActionRecord record = iterator.next().getValue();
            if (record.status.isTerminal() && now - record.finishedAtMs >= TERMINAL_TTL_MS) {
                iterator.remove();
            }
        }

        for (ActiveAction active : new ArrayList<>(activeByMaid.values())) {
            if (active.maid.isDeadOrDying() || !active.maid.isAlive()) {
                terminate(active, ActionStatus.FAILED, ActionEndReason.ENTITY_DEAD,
                        new JsonObject());
            } else if (active.maid.isRemoved()) {
                if (active.kind == MaidActionKind.AUTONOMOUS_MINING) {
                    suspendForRecovery(active, ActionEndReason.ENTITY_UNLOADED, false);
                } else {
                    terminate(active, ActionStatus.FAILED, ActionEndReason.ENTITY_UNLOADED,
                            new JsonObject(), false);
                }
            }
        }
    }

    public void shutdown() {
        for (ActiveAction active : new ArrayList<>(activeByMaid.values())) {
            if (active.kind == MaidActionKind.AUTONOMOUS_MINING) {
                suspendForRecovery(active, ActionEndReason.SERVER_STATE_LOST, true);
            } else {
                terminate(active, ActionStatus.CANCELLED, ActionEndReason.REQUESTED,
                        new JsonObject());
            }
        }
        activeByMaid.clear();
        recordsByAction.clear();
        generationsByMaid.clear();
    }

    public boolean hasActiveLease(UUID actionId, long generation) {
        ActionRecord record = recordsByAction.get(actionId);
        return record instanceof ActiveAction active
                && active.generation == generation
                && !active.status.isTerminal()
                && activeByMaid.get(active.maidId) == active;
    }

    public void recoverOrphanLease(EntityMaid maid) {
        MaidBodyLease orphan = MaidBodyLease.fromPersistentData(maid);
        if (orphan == null || hasActiveLease(orphan.actionId(), orphan.generation())) {
            return;
        }
        LOGGER.warn("Recovering orphan maid action lease {} generation {} for {}",
                orphan.actionId(), orphan.generation(), maid.getUUID());
        generationsByMaid.merge(maid.getUUID(), orphan.generation(), Math::max);
        try {
            cleanupNavigation(maid);
        } catch (RuntimeException cleanupFailure) {
            LOGGER.error("Failed to clear navigation while recovering orphan lease for {}",
                    maid.getUUID(), cleanupFailure);
        }
        try {
            orphan.release(maid);
        } catch (RuntimeException releaseFailure) {
            // Keep the persisted lease as recovery evidence for a later retry.
            LOGGER.error("Failed to recover orphan maid lease for {}", maid.getUUID(), releaseFailure);
        }
    }

    private void terminate(ActiveAction active, ActionStatus terminalStatus,
                           ActionEndReason reason, JsonObject result) {
        terminate(active, terminalStatus, reason, result, true);
    }

    private void terminate(ActiveAction active, ActionStatus terminalStatus,
                           ActionEndReason reason, JsonObject result, boolean releaseLease) {
        if (active.status.isTerminal() || active.status == ActionStatus.TERMINATING) {
            return;
        }
        transition(active, ActionStatus.TERMINATING);
        try {
            active.action.stop(active.context(active.maid.level().getGameTime()), reason);
        } catch (RuntimeException cleanupFailure) {
            LOGGER.error("Action stop failed for {}", active.actionId, cleanupFailure);
            active.warnings.add("ACTION_STOP_FAILED");
        }
        JsonObject mergedResult = result == null ? new JsonObject() : result.deepCopy();
        try {
            JsonObject actionResult = active.action.terminationResult(
                    active.context(active.maid.level().getGameTime()), reason);
            mergeMissingFields(mergedResult, actionResult);
        } catch (RuntimeException snapshotFailure) {
            LOGGER.error("Action terminal snapshot failed for {}", active.actionId,
                    snapshotFailure);
            active.warnings.add("ACTION_TERMINAL_SNAPSHOT_FAILED");
        }
        try {
            cleanupNavigation(active.maid);
        } catch (RuntimeException cleanupFailure) {
            LOGGER.error("Navigation cleanup failed for {}", active.actionId, cleanupFailure);
            active.warnings.add("NAVIGATION_CLEANUP_FAILED");
        }
        if (releaseLease) {
            try {
                MaidBodyLease.ReleaseReport release = active.lease.release(active.maid);
                if (release.handConflict()) {
                    active.warnings.add("HAND_CONFLICT");
                }
            } catch (RuntimeException releaseFailure) {
                // Do not strand the state machine in TERMINATING. The NBT lease
                // intentionally remains so entity load can retry orphan recovery.
                LOGGER.error("Body lease release failed for {}", active.actionId, releaseFailure);
                active.warnings.add("LEASE_RELEASE_FAILED");
            }
        }

        try {
            transition(active, terminalStatus);
        } catch (RuntimeException transitionFailure) {
            LOGGER.error("Forcing terminal state {} for {} after transition failure",
                    terminalStatus, active.actionId, transitionFailure);
            active.status = terminalStatus;
            active.sequence++;
            active.warnings.add("TERMINAL_TRANSITION_FORCED");
        } finally {
            active.stage = terminalStatus.name();
            active.endReason = reason;
            active.result = mergedResult;
            active.finishedAtMs = System.currentTimeMillis();
            activeByMaid.remove(active.maidId, active);
            if (active.eventsEnabled) {
                broadcast("maid_action_finished", active.snapshot());
            }
        }
    }

    private static void cleanupNavigation(EntityMaid maid) {
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.PATH);
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        maid.setSwingingArms(false);
    }

    private static void mergeMissingFields(JsonObject target, JsonObject fallback) {
        if (fallback == null) {
            return;
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry : fallback.entrySet()) {
            if (!target.has(entry.getKey())) {
                target.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    /**
     * Removes a durable autonomous operation from the volatile runtime without
     * publishing a false terminal event. Its action persists the resumable
     * checkpoint from {@code stop}; entity load reconstructs a fresh runtime
     * generation later.
     */
    private void suspendForRecovery(ActiveAction active, ActionEndReason reason,
                                    boolean releaseLease) {
        if (active.status.isTerminal() || active.status == ActionStatus.TERMINATING) {
            return;
        }
        transition(active, ActionStatus.TERMINATING);
        active.eventsEnabled = false;
        try {
            active.action.stop(active.context(active.maid.level().getGameTime()), reason);
        } catch (RuntimeException failure) {
            LOGGER.error("Failed to suspend durable maid action {}", active.actionId,
                    failure);
        }
        try {
            cleanupNavigation(active.maid);
        } catch (RuntimeException failure) {
            LOGGER.error("Failed to clean navigation while suspending {}", active.actionId,
                    failure);
        }
        if (releaseLease) {
            try {
                active.lease.release(active.maid);
            } catch (RuntimeException failure) {
                LOGGER.error("Failed to release durable maid lease {}", active.actionId,
                        failure);
            }
        }
        activeByMaid.remove(active.maidId, active);
        recordsByAction.remove(active.actionId, active);
    }

    private static void transition(ActionRecord record, ActionStatus next) {
        if (!record.status.canTransitionTo(next)) {
            throw new IllegalStateException("Invalid maid action transition " + record.status + " -> " + next);
        }
        record.status = next;
        // Every observable state snapshot must advance the ordering token. In
        // particular, a cancel response must be newer than the last progress
        // event or Python will correctly discard it as a stale duplicate.
        record.sequence++;
    }

    private static String requestFingerprint(UUID maidId, MaidActionKind kind, JsonObject args,
                                             long timeoutMs, boolean replaceExisting) {
        return maidId + "|" + kind.wireName() + "|" + args + "|" + timeoutMs + "|" + replaceExisting;
    }

    private IMaidTask appliedTaskFor(MaidActionKind kind, EntityMaid maid) {
        if (kind == MaidActionKind.LEGACY_ATTACK && maid.getTask() != null) {
            String taskId = maid.getTask().getUid().toString();
            if (taskId.endsWith(":attack") || taskId.endsWith(":ranged_attack")
                    || taskId.endsWith(":crossbow_attack") || taskId.endsWith(":danmaku_attack")
                    || taskId.endsWith(":trident_attack")) {
                return maid.getTask();
            }
        }
        return appliedTasks.getOrDefault(kind, TaskManager.getIdleTask());
    }

    private static void broadcast(String type, JsonObject data) {
        if (NekoWebSocketServerHolder.getServer() != null) {
            NekoWebSocketServerHolder.getServer().broadcastMaidActionMessage(type, data);
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (server != null && !server.isSameThread()) {
            throw new IllegalStateException("MaidActionStore must run on the Minecraft server thread");
        }
    }

    public record StartResult(boolean accepted, String rejectionReason, JsonObject status) {
    }

    public record CancelResult(boolean accepted, String rejectionReason, JsonObject status) {
    }

    private static class ActionRecord {
        protected final UUID actionId;
        protected final UUID maidId;
        protected final MaidActionKind kind;
        protected final String requestFingerprint;
        protected final long generation;
        protected final long startedGameTime;
        protected ActionStatus status = ActionStatus.PENDING;
        protected ActionEndReason endReason;
        protected String stage = "PENDING";
        protected double progress;
        protected long sequence;
        protected long finishedAtMs;
        protected JsonObject result = new JsonObject();
        protected final List<String> warnings = new ArrayList<>();

        private ActionRecord(UUID actionId, UUID maidId, MaidActionKind kind,
                             String requestFingerprint, long generation, long startedGameTime) {
            this.actionId = actionId;
            this.maidId = maidId;
            this.kind = kind;
            this.requestFingerprint = requestFingerprint;
            this.generation = generation;
            this.startedGameTime = startedGameTime;
        }

        protected JsonObject snapshot() {
            JsonObject json = new JsonObject();
            json.addProperty("action_id", actionId.toString());
            json.addProperty("maid_id", maidId.toString());
            json.addProperty("generation", generation);
            json.addProperty("sequence", sequence);
            json.addProperty("kind", kind.wireName());
            json.addProperty("status", status.name());
            json.addProperty("stage", stage);
            json.addProperty("progress", progress);
            json.addProperty("started_game_time", startedGameTime);
            json.addProperty("timestamp", System.currentTimeMillis());
            if (endReason != null) {
                json.addProperty("end_reason", endReason.name());
            }
            json.add("result", result.deepCopy());
            JsonArray warningArray = new JsonArray();
            warnings.forEach(warningArray::add);
            json.add("warnings", warningArray);
            return json;
        }
    }

    private final class ActiveAction extends ActionRecord implements MaidActionExecution {
        private final EntityMaid maid;
        private final MaidAction action;
        private final JsonObject args;
        private final long deadlineGameTime;
        private final MaidBodyLease lease;
        private long lastProgressGameTime;
        private boolean eventsEnabled;

        private ActiveAction(UUID actionId, EntityMaid maid, MaidActionKind kind,
                             MaidAction action, JsonObject args, String requestFingerprint,
                             long generation, long startedGameTime, long deadlineGameTime,
                             MaidBodyLease lease) {
            super(actionId, maid.getUUID(), kind, requestFingerprint, generation, startedGameTime);
            this.maid = maid;
            this.action = action;
            this.args = args;
            this.deadlineGameTime = deadlineGameTime;
            this.lease = lease;
        }

        private MaidActionContext context(long gameTime) {
            return new MaidActionContext((ServerLevel) maid.level(), maid, gameTime, this);
        }

        private void maybeHeartbeat(long gameTime) {
            if (gameTime - lastProgressGameTime >= PROGRESS_HEARTBEAT_TICKS) {
                emitProgress(false, stage, progress, new JsonObject(), gameTime);
            }
        }

        @Override
        public UUID actionId() {
            return actionId;
        }

        @Override
        public UUID maidId() {
            return maidId;
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public long startedGameTime() {
            return startedGameTime;
        }

        @Override
        public long deadlineGameTime() {
            return deadlineGameTime;
        }

        @Override
        public void reportProgress(String stage, double progress, JsonObject detail) {
            emitProgress(!Objects.equals(this.stage, stage), stage, progress, detail,
                    maid.level().getGameTime());
        }

        private void emitProgress(boolean stageChanged, String newStage, double newProgress,
                                  JsonObject detail, long gameTime) {
            if (!eventsEnabled) {
                this.stage = newStage == null ? this.stage : newStage;
                this.progress = Math.max(0.0, Math.min(1.0, newProgress));
                return;
            }
            if (!stageChanged && gameTime - lastProgressGameTime < PROGRESS_HEARTBEAT_TICKS) {
                this.progress = Math.max(0.0, Math.min(1.0, newProgress));
                return;
            }
            this.stage = newStage == null ? this.stage : newStage;
            this.progress = Math.max(0.0, Math.min(1.0, newProgress));
            this.sequence++;
            this.lastProgressGameTime = gameTime;
            JsonObject data = snapshot();
            if (detail != null && !detail.isEmpty()) {
                data.add("detail", detail.deepCopy());
            }
            broadcast("maid_action_progress", data);
        }
    }
}
