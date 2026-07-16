package com.neko_tlm_bridge.tlm.agent.action;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Tracks one 26-neighbour connected block vein across local rescans. */
public final class MaidVeinTracker {
    public static final int DEFAULT_LIMIT = 64;
    private static final int[][] NEIGHBOUR_OFFSETS = createNeighbourOffsets();

    private final Set<BlockPos> members = new LinkedHashSet<>();
    private final Set<BlockPos> harvestedMembers = new HashSet<>();
    private final int limit;
    private boolean truncated;

    public MaidVeinTracker() {
        this(DEFAULT_LIMIT);
    }

    /**
     * Creates a tracker without an artificial vein-size cutoff.  Autonomous
     * mining uses this form because its target count is a minimum, not
     * permission to abandon the connected component currently being mined.
     */
    public static MaidVeinTracker unbounded() {
        return new MaidVeinTracker(Integer.MAX_VALUE);
    }

    /** Restores a durable connected-vein commitment after a server reload. */
    public static MaidVeinTracker restore(
            Collection<BlockPos> knownMembers,
            Collection<BlockPos> harvestedMembers) {
        Objects.requireNonNull(knownMembers, "knownMembers");
        Objects.requireNonNull(harvestedMembers, "harvestedMembers");
        MaidVeinTracker tracker = unbounded();
        for (BlockPos pos : knownMembers) {
            tracker.members.add(Objects.requireNonNull(pos, "known member").immutable());
        }
        for (BlockPos pos : harvestedMembers) {
            BlockPos immutable = Objects.requireNonNull(pos, "harvested member").immutable();
            tracker.harvestedMembers.add(immutable);
            tracker.members.add(immutable);
        }
        return tracker;
    }

    MaidVeinTracker(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    /**
     * Expands the component previously locked by {@link #rememberHarvested}.
     * Returned positions retain the caller's priority order and never include
     * a disconnected vein.
     */
    public List<BlockPos> retainConnected(Collection<BlockPos> discovered,
                                          Comparator<BlockPos> priority) {
        Objects.requireNonNull(discovered, "discovered");
        Objects.requireNonNull(priority, "priority");
        List<BlockPos> ordered = discovered.stream()
                .map(pos -> Objects.requireNonNull(pos, "discovered position").immutable())
                .distinct()
                .sorted(priority)
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }

        Set<BlockPos> available = new HashSet<>(ordered);
        Set<BlockPos> connectivityGraph = new HashSet<>(members);
        connectivityGraph.addAll(harvestedMembers);
        connectivityGraph.addAll(available);
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> reached = new HashSet<>();
        if (harvestedMembers.isEmpty()) {
            return List.of();
        }
        for (BlockPos pos : harvestedMembers) {
            if (connectivityGraph.contains(pos)) {
                open.add(pos);
            }
        }

        while (!open.isEmpty()) {
            BlockPos current = open.removeFirst();
            if (!connectivityGraph.contains(current) || !reached.add(current)) {
                continue;
            }
            for (int[] offset : NEIGHBOUR_OFFSETS) {
                BlockPos neighbour = current.offset(offset[0], offset[1], offset[2]);
                if (connectivityGraph.contains(neighbour)
                        && !reached.contains(neighbour)) {
                    open.addLast(neighbour);
                }
            }
        }

        // Every loaded live member is included in discovered by the action.
        // If it is no longer reachable from an actually harvested bridge, it
        // is no longer part of this action's current connected component.
        members.removeIf(pos -> !harvestedMembers.contains(pos)
                && available.contains(pos) && !reached.contains(pos));

        for (BlockPos pos : ordered) {
            if (!reached.contains(pos)) {
                continue;
            }
            if (members.contains(pos)) {
                continue;
            }
            if (members.size() >= limit) {
                truncated = true;
                break;
            }
            members.add(pos);
        }
        List<BlockPos> retained = new ArrayList<>(Math.min(reached.size(), members.size()));
        for (BlockPos pos : ordered) {
            if (reached.contains(pos) && members.contains(pos)) {
                retained.add(pos);
            }
        }
        return List.copyOf(retained);
    }

    /** Keeps a mined member as a bridge for expansion after it becomes air. */
    public boolean rememberHarvested(BlockPos pos) {
        BlockPos immutable = Objects.requireNonNull(pos, "pos").immutable();
        if (!harvestedMembers.add(immutable)) {
            return false;
        }
        if (!members.contains(immutable) && members.size() >= limit) {
            harvestedMembers.remove(immutable);
            truncated = true;
            return false;
        }
        members.add(immutable);
        return true;
    }

    public boolean contains(BlockPos pos) {
        return members.contains(pos);
    }

    public boolean locked() {
        return !harvestedMembers.isEmpty();
    }

    public int knownMembers() {
        return members.size();
    }

    public Set<BlockPos> members() {
        return Set.copyOf(members);
    }

    public Set<BlockPos> harvestedMembers() {
        return Set.copyOf(harvestedMembers);
    }

    /** Known members that still need to be verified and harvested. */
    public Set<BlockPos> pendingMembers() {
        Set<BlockPos> pending = new LinkedHashSet<>(members);
        pending.removeAll(harvestedMembers);
        return Set.copyOf(pending);
    }

    /** Drops externally removed, never-harvested members from future connectivity. */
    public void pruneUnharvested(Predicate<BlockPos> keep) {
        Objects.requireNonNull(keep, "keep");
        members.removeIf(pos -> !harvestedMembers.contains(pos) && !keep.test(pos));
    }

    public boolean truncated() {
        return truncated;
    }

    public int limit() {
        return limit;
    }

    private static int[][] createNeighbourOffsets() {
        List<int[]> offsets = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        offsets.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }
        return offsets.toArray(int[][]::new);
    }
}
