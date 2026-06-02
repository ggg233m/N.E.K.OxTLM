package com.neko_tlm_bridge.tlm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NekoAttackTargetStore {
    private static final long EXPIRE_MS = 120_000;
    private static final Map<UUID, TargetQueue> TARGETS = new ConcurrentHashMap<>();

    public static class TargetEntry {
        public final UUID targetEntityId;
        public final String targetName;
        public final long setAt;

        public TargetEntry(UUID targetEntityId, String targetName) {
            this.targetEntityId = targetEntityId;
            this.targetName = targetName;
            this.setAt = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - setAt > EXPIRE_MS;
        }
    }

    public static class TargetQueue {
        public final List<TargetEntry> entries = new ArrayList<>();
        public int currentIndex = 0;

        public TargetEntry current() {
            if (currentIndex < entries.size()) {
                return entries.get(currentIndex);
            }
            return null;
        }

        public boolean advance() {
            currentIndex++;
            return currentIndex < entries.size();
        }

        public boolean isDone() {
            return currentIndex >= entries.size();
        }
    }

    public static void setTarget(UUID maidId, UUID targetEntityId, String targetName) {
        TargetQueue queue = new TargetQueue();
        queue.entries.add(new TargetEntry(targetEntityId, targetName));
        TARGETS.put(maidId, queue);
    }

    public static void setTargets(UUID maidId, List<TargetEntry> newEntries) {
        TargetQueue queue = new TargetQueue();
        queue.entries.addAll(newEntries);
        TARGETS.put(maidId, queue);
    }

    public static TargetEntry getCurrentTarget(UUID maidId) {
        TargetQueue queue = TARGETS.get(maidId);
        if (queue == null) return null;
        return queue.current();
    }

    public static boolean advanceTarget(UUID maidId) {
        TargetQueue queue = TARGETS.get(maidId);
        if (queue == null) return false;
        return queue.advance();
    }

    public static int getRemainingCount(UUID maidId) {
        TargetQueue queue = TARGETS.get(maidId);
        if (queue == null) return 0;
        return queue.entries.size() - queue.currentIndex;
    }

    public static void removeTarget(UUID maidId) {
        TARGETS.remove(maidId);
    }

    public static void clearAll() {
        TARGETS.clear();
    }

    public static void tickCleanup() {
        long now = System.currentTimeMillis();
        var iter = TARGETS.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            TargetQueue queue = entry.getValue();
            queue.entries.removeIf(e -> now - e.setAt > EXPIRE_MS);
            if (queue.isDone() || queue.entries.isEmpty()) {
                iter.remove();
            }
        }
    }
}
