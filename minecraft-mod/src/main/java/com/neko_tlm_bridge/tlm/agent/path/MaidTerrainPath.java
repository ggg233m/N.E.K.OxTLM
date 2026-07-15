package com.neko_tlm_bridge.tlm.agent.path;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/** Immutable result of a terrain search. */
public record MaidTerrainPath(
        List<MaidTerrainStep> steps,
        BlockPos target,
        double totalCost,
        int expandedNodes
) {
    public MaidTerrainPath {
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        target = Objects.requireNonNull(target, "target").immutable();
        if (!Double.isFinite(totalCost) || totalCost < 0.0D) {
            throw new IllegalArgumentException("totalCost must be finite and non-negative");
        }
        if (expandedNodes < 0) {
            throw new IllegalArgumentException("expandedNodes must be non-negative");
        }
    }
}
