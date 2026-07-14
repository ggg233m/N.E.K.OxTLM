package com.neko_tlm_bridge.tlm.agent.runtime;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * Write-ahead, persistent ownership lease for the maid control fields that an
 * agent temporarily changes.  Release is a per-field compare-and-set: a field
 * is restored only while it still equals the value applied by the agent.
 */
public final class MaidBodyLease {
    private static final Logger LOGGER = LoggerFactory.getLogger("NekoTlmBridge");
    public static final String PERSISTENT_TAG = "NekoMaidAgentLease";
    private static final int SCHEMA_VERSION = 1;
    private static final int AGENT_RESTRICTION_RADIUS = 64;

    private final UUID actionId;
    private final long generation;
    private final Snapshot original;
    private final Snapshot applied;
    private HandLease handLease;
    private boolean released;

    private MaidBodyLease(UUID actionId, long generation, Snapshot original,
                          Snapshot applied, HandLease handLease) {
        this.actionId = Objects.requireNonNull(actionId);
        this.generation = generation;
        this.original = Objects.requireNonNull(original);
        this.applied = Objects.requireNonNull(applied);
        this.handLease = handLease;
    }

    public static MaidBodyLease acquire(EntityMaid maid, UUID actionId, long generation) {
        return acquire(maid, actionId, generation, TaskManager.getIdleTask());
    }

    public static MaidBodyLease acquire(EntityMaid maid, UUID actionId, long generation,
                                        IMaidTask appliedTask) {
        Objects.requireNonNull(maid, "maid");
        Objects.requireNonNull(appliedTask, "appliedTask");
        Snapshot original = Snapshot.capture(maid);
        Snapshot applied = new Snapshot(
                appliedTask.getUid().toString(),
                MaidSchedule.ALL,
                false,
                true,
                maid.blockPosition(),
                AGENT_RESTRICTION_RADIUS);
        MaidBodyLease lease = new MaidBodyLease(actionId, generation, original, applied, null);

        // Write-ahead ordering: a crash after any following setter can recover.
        lease.persist(maid);
        lease.apply(maid);
        return lease;
    }

    public static MaidBodyLease fromPersistentData(EntityMaid maid) {
        CompoundTag persistent = maid.getPersistentData();
        if (!persistent.contains(PERSISTENT_TAG)) {
            return null;
        }
        CompoundTag tag = persistent.getCompound(PERSISTENT_TAG);
        try {
            int schema = tag.getInt("schema");
            if (schema != SCHEMA_VERSION) {
                LOGGER.warn("Attempting best-effort recovery of maid lease schema {} (supported {})",
                        schema, SCHEMA_VERSION);
            }
            UUID actionId = UUID.fromString(tag.getString("action_id"));
            long generation = tag.getLong("generation");
            Snapshot original = Snapshot.fromTag(tag.getCompound("original"));
            Snapshot applied = Snapshot.fromTag(tag.getCompound("applied"));
            HandLease hand = tag.contains("hand") ? HandLease.fromTag(tag.getCompound("hand")) : null;
            return new MaidBodyLease(actionId, generation, original, applied, hand);
        } catch (RuntimeException malformed) {
            // Never destroy the only recovery evidence. A later compatible
            // version or administrator can still inspect/recover this tag.
            LOGGER.error("Unable to parse persisted maid body lease; preserving NBT", malformed);
            return null;
        }
    }

    public UUID actionId() {
        return actionId;
    }

    public long generation() {
        return generation;
    }

    public HandLease handLease() {
        return handLease;
    }

    public void attachHandLease(EntityMaid maid, HandLease lease) {
        requireOpen();
        if (handLease != null) {
            throw new IllegalStateException("A hand lease is already attached");
        }
        handLease = Objects.requireNonNull(lease);
        persist(maid);
    }

    /** Returns false when a player or another controller changed a control field. */
    public boolean controlFieldsUnchanged(EntityMaid maid) {
        requireOpen();
        return applied.taskId.equals(taskId(maid))
                && applied.schedule == maid.getSchedule()
                && applied.sitting == maid.isMaidInSittingPose()
                && applied.homeMode == maid.isHomeModeEnable();
    }

    /** Reasserts only the derived restriction that TLM's schedule tick may overwrite. */
    public void maintainRestriction(EntityMaid maid) {
        requireOpen();
        maid.restrictTo(applied.restrictCenter, Math.round(applied.restrictRadius));
    }

    public ReleaseReport release(EntityMaid maid) {
        if (released) {
            return new ReleaseReport(false, false, false, false, false, true);
        }

        boolean handConflict = false;
        if (handLease != null) {
            handConflict = handLease.release(maid) == HandLease.ReleaseResult.HAND_CONFLICT;
        }

        boolean taskRestored = false;
        boolean scheduleRestored = false;
        boolean sittingRestored = false;
        boolean homeRestored = false;
        boolean restrictionRestored = false;

        // Restore cheap fields first and task last because setTask refreshes the brain.
        if (maid.getSchedule() == applied.schedule) {
            maid.setSchedule(original.schedule);
            scheduleRestored = true;
        }
        if (maid.isMaidInSittingPose() == applied.sitting) {
            maid.setInSittingPose(original.sitting);
            sittingRestored = true;
        }
        if (maid.isHomeModeEnable() == applied.homeMode) {
            maid.setHomeModeEnable(original.homeMode);
            homeRestored = true;
        }
        if (maid.getRestrictCenter().equals(applied.restrictCenter)
                && Float.compare(maid.getRestrictRadius(), applied.restrictRadius) == 0) {
            maid.restrictTo(original.restrictCenter, Math.round(original.restrictRadius));
            restrictionRestored = true;
        }
        if (applied.taskId.equals(taskId(maid))) {
            java.util.Optional<IMaidTask> originalTask = resolveTask(original.taskId);
            if (originalTask.isPresent()) {
                maid.setTask(originalTask.get());
                taskRestored = true;
            }
        }

        maid.getPersistentData().remove(PERSISTENT_TAG);
        released = true;
        return new ReleaseReport(taskRestored, scheduleRestored, sittingRestored,
                homeRestored, restrictionRestored, handConflict);
    }

    private void apply(EntityMaid maid) {
        maid.setSchedule(applied.schedule);
        maid.setInSittingPose(applied.sitting);
        maid.setHomeModeEnable(applied.homeMode);
        maid.restrictTo(applied.restrictCenter, Math.round(applied.restrictRadius));
        resolveTask(applied.taskId).ifPresent(maid::setTask);
    }

    private void persist(EntityMaid maid) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SCHEMA_VERSION);
        tag.putString("action_id", actionId.toString());
        tag.putLong("generation", generation);
        tag.put("original", original.toTag());
        tag.put("applied", applied.toTag());
        if (handLease != null) {
            tag.put("hand", handLease.toTag());
        }
        maid.getPersistentData().put(PERSISTENT_TAG, tag);
    }

    private static java.util.Optional<IMaidTask> resolveTask(String taskId) {
        try {
            return TaskManager.findTask(ResourceLocation.parse(taskId));
        } catch (RuntimeException malformed) {
            return java.util.Optional.empty();
        }
    }

    private static String taskId(EntityMaid maid) {
        IMaidTask task = maid.getTask();
        return task == null ? "" : task.getUid().toString();
    }

    private void requireOpen() {
        if (released) {
            throw new IllegalStateException("Body lease is already released");
        }
    }

    public record ReleaseReport(boolean taskRestored, boolean scheduleRestored,
                                boolean sittingRestored, boolean homeRestored,
                                boolean restrictionRestored, boolean handConflict) {
    }

    private record Snapshot(String taskId, MaidSchedule schedule, boolean sitting,
                            boolean homeMode, BlockPos restrictCenter, float restrictRadius) {
        private static Snapshot capture(EntityMaid maid) {
            return new Snapshot(MaidBodyLease.taskId(maid), maid.getSchedule(),
                    maid.isMaidInSittingPose(), maid.isHomeModeEnable(),
                    maid.getRestrictCenter(), maid.getRestrictRadius());
        }

        private CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("task", taskId);
            tag.putString("schedule", schedule.name());
            tag.putBoolean("sitting", sitting);
            tag.putBoolean("home", homeMode);
            tag.put("restrict_center", NbtUtils.writeBlockPos(restrictCenter));
            tag.putFloat("restrict_radius", restrictRadius);
            return tag;
        }

        private static Snapshot fromTag(CompoundTag tag) {
            BlockPos center = NbtUtils.readBlockPos(tag, "restrict_center").orElse(BlockPos.ZERO);
            return new Snapshot(tag.getString("task"),
                    MaidSchedule.valueOf(tag.getString("schedule")),
                    tag.getBoolean("sitting"), tag.getBoolean("home"), center,
                    tag.getFloat("restrict_radius"));
        }
    }
}
