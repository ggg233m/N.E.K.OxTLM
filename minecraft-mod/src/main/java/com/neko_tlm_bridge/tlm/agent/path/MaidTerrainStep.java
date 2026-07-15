package com.neko_tlm_bridge.tlm.agent.path;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/** One executable edge in a maid terrain path. */
public record MaidTerrainStep(
        Kind kind,
        BlockPos from,
        BlockPos to,
        List<BlockPos> clearance,
        List<BlockPos> toBreak,
        double cost
) {
    public MaidTerrainStep {
        Objects.requireNonNull(kind, "kind");
        from = Objects.requireNonNull(from, "from").immutable();
        to = Objects.requireNonNull(to, "to").immutable();
        Objects.requireNonNull(clearance, "clearance");
        clearance = clearance.stream()
                .map(pos -> Objects.requireNonNull(pos, "clearance position").immutable())
                .distinct()
                .toList();
        if (!clearance.contains(to) || !clearance.contains(to.above())) {
            throw new IllegalArgumentException("every terrain step must reserve feet and head clearance");
        }
        Objects.requireNonNull(toBreak, "toBreak");
        toBreak = toBreak.stream()
                .map(pos -> Objects.requireNonNull(pos, "toBreak position").immutable())
                .distinct()
                .toList();
        if (!clearance.containsAll(toBreak)) {
            throw new IllegalArgumentException("toBreak must be a subset of clearance");
        }
        if (!Double.isFinite(cost) || cost <= 0.0D) {
            throw new IllegalArgumentException("cost must be finite and positive");
        }
    }

    public enum Kind {
        TRAVERSE,
        ASCEND,
        DESCEND,
        DIG_DOWN
    }
}
