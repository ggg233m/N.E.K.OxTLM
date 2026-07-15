package com.neko_tlm_bridge.tlm.agent.world;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/** Reconstructs durable autonomous mining actions when their maid loads. */
public final class AutonomousMiningRecovery {
    private static final Logger LOGGER = LoggerFactory.getLogger("NekoTlmBridge");

    private AutonomousMiningRecovery() {
    }

    /**
     * Returns true when an operation is already active or was restarted. A
     * BLOCKED checkpoint is deliberately left idle for an explicit LLM or
     * player decision. PAUSED currently represents a server/chunk suspension
     * and is therefore safe to reconstruct automatically.
     */
    public static boolean recover(EntityMaid maid) {
        Objects.requireNonNull(maid, "maid");
        if (!(maid.level() instanceof ServerLevel level)) {
            return false;
        }
        MaidActionStore store = MaidActionStore.getInstance();
        if (store.reconcileLoadedEntity(maid)) {
            return true;
        }

        // A suspended operation is dimension-bound. If the maid was moved to
        // another dimension while unloaded, terminate the old checkpoint
        // instead of unexpectedly resuming it after a later return.
        for (ServerLevel otherLevel : level.getServer().getAllLevels()) {
            if (otherLevel == level) {
                continue;
            }
            MiningWorldModelSavedData otherModel = MiningWorldModelSavedData.get(otherLevel);
            otherModel.findResumableByMaid(maid.getUUID()).ifPresent(snapshot -> {
                try {
                    otherModel.markTerminal(snapshot.operationId(),
                            MiningWorldModelSavedData.OperationStatus.FAILED,
                            "entity_changed_dimension", otherLevel.getGameTime());
                } catch (RuntimeException failure) {
                    LOGGER.error("Unable to terminate cross-dimension mining operation {}",
                            snapshot.operationId(), failure);
                }
            });
        }

        Optional<MiningWorldModelSavedData.OperationSnapshot> candidate =
                MiningWorldModelSavedData.findResumableByMaid(level, maid.getUUID());
        // Restore an orphan body/hand lease even when no durable operation can
        // be resumed or when that operation is intentionally paused/blocked.
        store.recoverOrphanLease(maid);
        if (candidate.isEmpty()) {
            return false;
        }

        MiningWorldModelSavedData.OperationSnapshot snapshot = candidate.get();
        if (snapshot.blocked()) {
            return false;
        }
        store.ensureGenerationAtLeast(maid.getUUID(), snapshot.generation());
        MaidActionStore.StartResult start = store.start(
                snapshot.operationId(), maid, MaidActionKind.AUTONOMOUS_MINING,
                snapshot.normalizedArgs(), 0L, false);
        if (start.accepted()) {
            LOGGER.info("Resumed autonomous mining operation {} for maid {}",
                    snapshot.operationId(), maid.getUUID());
            return true;
        }

        String reason = start.rejectionReason() == null
                ? "unknown" : start.rejectionReason();
        LOGGER.error("Unable to resume autonomous mining operation {} for maid {}: {}",
                snapshot.operationId(), maid.getUUID(), reason);
        try {
            MiningWorldModelSavedData.get(level).markBlocked(
                    snapshot.operationId(), "resume_rejected_" + reason,
                    level.getGameTime());
        } catch (RuntimeException persistenceFailure) {
            LOGGER.error("Unable to persist autonomous mining resume failure {}",
                    snapshot.operationId(), persistenceFailure);
        }
        return false;
    }
}
