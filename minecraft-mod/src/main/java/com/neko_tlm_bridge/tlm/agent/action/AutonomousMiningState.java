package com.neko_tlm_bridge.tlm.agent.action;

import java.util.Locale;
import java.util.Objects;

/**
 * World-independent state and accounting for one unbounded autonomous mining
 * action.  Minecraft-facing code owns path/search objects; this class owns the
 * long-lived phase contract and makes counter transitions independently
 * testable.
 */
final class AutonomousMiningState {
    private final int targetCount;

    private Phase phase = Phase.VALIDATING;
    private int collectedCount;
    private long segmentsDug;
    private long clearedBlocks;
    private String blockedReason = "none";
    private boolean decisionRequired;

    AutonomousMiningState(int targetCount) {
        if (targetCount < 1) {
            throw new IllegalArgumentException("targetCount must be positive");
        }
        this.targetCount = targetCount;
    }

    static AutonomousMiningState restore(int targetCount, int collectedCount,
                                         long segmentsDug, long clearedBlocks) {
        AutonomousMiningState restored = new AutonomousMiningState(targetCount);
        if (collectedCount < 0 || segmentsDug < 0L || clearedBlocks < 0L) {
            throw new IllegalArgumentException("restored counters must be non-negative");
        }
        restored.collectedCount = collectedCount;
        restored.segmentsDug = segmentsDug;
        restored.clearedBlocks = clearedBlocks;
        return restored;
    }

    Phase phase() {
        return phase;
    }

    int targetCount() {
        return targetCount;
    }

    int collectedCount() {
        return collectedCount;
    }

    long segmentsDug() {
        return segmentsDug;
    }

    long clearedBlocks() {
        return clearedBlocks;
    }

    String blockedReason() {
        return blockedReason;
    }

    boolean decisionRequired() {
        return decisionRequired;
    }

    boolean goalReached() {
        return collectedCount >= targetCount;
    }

    void transitionTo(Phase next) {
        Objects.requireNonNull(next, "next");
        if (phase.terminal()) {
            throw new IllegalStateException("terminal mining state cannot transition");
        }
        if (next == Phase.COMPLETED && !goalReached()) {
            throw new IllegalStateException("mining goal is not complete");
        }
        if (next == Phase.BLOCKED) {
            throw new IllegalArgumentException("use block(reason) for BLOCKED");
        }
        phase = next;
    }

    void recordExcavationStep(int newlyClearedBlocks) {
        if (newlyClearedBlocks < 0) {
            throw new IllegalArgumentException("newlyClearedBlocks must be non-negative");
        }
        requireActive();
        segmentsDug++;
        clearedBlocks = Math.addExact(clearedBlocks, (long) newlyClearedBlocks);
    }

    void recordRouteClearance(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        requireActive();
        clearedBlocks = Math.addExact(clearedBlocks, (long) count);
    }

    void recordHarvest() {
        requireActive();
        collectedCount = Math.addExact(collectedCount, 1);
    }

    void complete() {
        requireActive();
        if (!goalReached()) {
            throw new IllegalStateException("mining goal is not complete");
        }
        phase = Phase.COMPLETED;
    }

    void block(String reason) {
        requireActive();
        String normalized = normalizeReason(reason);
        if ("none".equals(normalized)) {
            throw new IllegalArgumentException("blocked reason must be meaningful");
        }
        blockedReason = normalized;
        decisionRequired = true;
        phase = Phase.BLOCKED;
    }

    private void requireActive() {
        if (phase.terminal()) {
            throw new IllegalStateException("terminal mining state cannot be mutated");
        }
    }

    static String normalizeReason(String reason) {
        String value = Objects.requireNonNull(reason, "reason").trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return value.isEmpty() ? "none" : value;
    }

    enum Phase {
        VALIDATING,
        SELECTING_SITE,
        EXCAVATING,
        SCANNING,
        HARVESTING,
        CONTINUING,
        COMPLETED,
        BLOCKED;

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        boolean terminal() {
            return this == COMPLETED || this == BLOCKED;
        }
    }
}
