package com.neko_tlm_bridge.tlm.agent.path;

import net.minecraft.core.BlockPos;

/**
 * Supplies terrain facts to {@link MaidTerrainSearch}.
 *
 * <p>A clear cost of zero means the cell is already passable, a finite positive
 * value is the cost of breaking the cell, and positive infinity means that the
 * cell cannot be entered or broken. Implementations may read a live world and
 * therefore define their own thread-affinity rules.</p>
 */
public interface MaidTerrainNodeEvaluator {
    boolean withinBounds(BlockPos pos);

    boolean isLoaded(BlockPos pos);

    double clearCost(BlockPos pos);

    /** Returns whether the block at {@code pos} safely supports the maid. */
    boolean canStandOn(BlockPos pos);

    /**
     * Returns the extra cost of preparing the support at {@code pos}. Existing
     * safe ground costs zero; a finite positive value means construction is
     * allowed; positive infinity means the destination cannot be supported.
     */
    default double supportCost(BlockPos pos) {
        return canStandOn(pos) ? 0.0D : Double.POSITIVE_INFINITY;
    }
}
