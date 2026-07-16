package com.neko_tlm_bridge.tlm.agent.path;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Main-thread evaluator over the maid's live server world and leased held tool.
 * It never loads chunks and treats block entities, fluids, hazards, and blocks
 * without a suitable harvesting tool as impassable.
 */
public final class MaidTerrainWorldEvaluator implements MaidTerrainNodeEvaluator {
    public static final int DEFAULT_HORIZONTAL_RADIUS = 64;
    public static final int DEFAULT_VERTICAL_RADIUS = 24;
    private static final Direction[] FLUID_EXPOSURE_DIRECTIONS = {
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.EAST,
            Direction.SOUTH, Direction.WEST
    };

    private final ServerLevel level;
    private final EntityMaid maid;
    private final BlockPos origin;
    private final int horizontalRadius;
    private final int verticalRadius;
    private final long horizontalRadiusSquared;
    private final boolean requireCorrectTool;
    private final Predicate<BlockPos> clearancePolicy;
    private final Predicate<BlockPos> constructionPolicy;

    public MaidTerrainWorldEvaluator(ServerLevel level, EntityMaid maid, BlockPos origin) {
        this(level, maid, origin, DEFAULT_HORIZONTAL_RADIUS, DEFAULT_VERTICAL_RADIUS, true);
    }

    public MaidTerrainWorldEvaluator(ServerLevel level, EntityMaid maid, BlockPos origin,
                                     int horizontalRadius, int verticalRadius) {
        this(level, maid, origin, horizontalRadius, verticalRadius, true);
    }

    public MaidTerrainWorldEvaluator(ServerLevel level, EntityMaid maid, BlockPos origin,
                                     int horizontalRadius, int verticalRadius,
                                     boolean requireCorrectTool) {
        this(level, maid, origin, horizontalRadius, verticalRadius,
                requireCorrectTool, ignored -> true, ignored -> false);
    }

    public MaidTerrainWorldEvaluator(ServerLevel level, EntityMaid maid, BlockPos origin,
                                     int horizontalRadius, int verticalRadius,
                                     boolean requireCorrectTool,
                                     Predicate<BlockPos> clearancePolicy) {
        this(level, maid, origin, horizontalRadius, verticalRadius,
                requireCorrectTool, clearancePolicy, ignored -> false);
    }

    public MaidTerrainWorldEvaluator(ServerLevel level, EntityMaid maid, BlockPos origin,
                                     int horizontalRadius, int verticalRadius,
                                     boolean requireCorrectTool,
                                     Predicate<BlockPos> clearancePolicy,
                                     Predicate<BlockPos> constructionPolicy) {
        this.level = Objects.requireNonNull(level, "level");
        this.maid = Objects.requireNonNull(maid, "maid");
        this.origin = Objects.requireNonNull(origin, "origin").immutable();
        if (horizontalRadius <= 0) {
            throw new IllegalArgumentException("horizontalRadius must be positive");
        }
        if (verticalRadius <= 0) {
            throw new IllegalArgumentException("verticalRadius must be positive");
        }
        this.horizontalRadius = horizontalRadius;
        this.verticalRadius = verticalRadius;
        this.horizontalRadiusSquared = (long) horizontalRadius * horizontalRadius;
        this.requireCorrectTool = requireCorrectTool;
        this.clearancePolicy = Objects.requireNonNull(clearancePolicy, "clearancePolicy");
        this.constructionPolicy = Objects.requireNonNull(
                constructionPolicy, "constructionPolicy");
    }

    @Override
    public boolean withinBounds(BlockPos pos) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        long dx = (long) pos.getX() - origin.getX();
        long dz = (long) pos.getZ() - origin.getZ();
        return Math.abs(pos.getY() - origin.getY()) <= verticalRadius
                && dx * dx + dz * dz <= horizontalRadiusSquared;
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return withinBounds(pos) && level.hasChunkAt(pos);
    }

    @Override
    public double clearCost(BlockPos pos) {
        if (!isLoaded(pos)) {
            return Double.POSITIVE_INFINITY;
        }
        BlockState state = level.getBlockState(pos);
        ClearanceAssessment assessment = assessClearance(level, pos, state);
        boolean sealableWater = assessment == ClearanceAssessment.WATER_HAZARD
                && state.canBeReplaced() && constructionPolicy.test(pos);
        if (!sealableWater
                && assessment != ClearanceAssessment.CLEAR
                && assessment != ClearanceAssessment.BREAKABLE) {
            return Double.POSITIVE_INFINITY;
        }
        if (!sealableWater && state.getFluidState().isEmpty()
                && state.getCollisionShape(level, pos).isEmpty()) {
            return 0.0D;
        }
        if (!clearancePolicy.test(pos)) {
            return Double.POSITIVE_INFINITY;
        }

        if (sealableWater && !state.getFluidState().isEmpty()
                && state.getCollisionShape(level, pos).isEmpty()) {
            return 24.0D;
        }
        float hardness = state.getDestroySpeed(level, pos);

        // Execution is governed by one HandLease. Price only the real held
        // tool so the planner cannot promise a route that would require an
        // unleased mid-path swap to some other backpack tool.
        ToolChoice tool = toolChoice(maid.getMainHandItem(), state);
        if (requireCorrectTool && state.requiresCorrectToolForDrops() && !tool.correct()) {
            return Double.POSITIVE_INFINITY;
        }
        double divisor = tool.correct() ? 30.0D : 100.0D;
        double ticks = hardness == 0.0F
                ? 1.0D
                : Math.ceil(hardness * divisor / Math.max(1.0D, tool.speed()));
        return Math.max(1.0D, ticks) + (sealableWater ? 24.0D : 0.0D);
    }

    @Override
    public boolean canStandOn(BlockPos pos) {
        return supportCost(pos) == 0.0D;
    }

    @Override
    public double supportCost(BlockPos pos) {
        if (!isLoaded(pos)) {
            return Double.POSITIVE_INFINITY;
        }
        BlockState state = level.getBlockState(pos);
        SupportAssessment assessment = assessStandSupport(level, pos, state);
        if (assessment == SupportAssessment.SAFE) {
            return 0.0D;
        }
        if ((assessment == SupportAssessment.UNSAFE_SUPPORT
                || assessment == SupportAssessment.WATER_HAZARD)
                && state.canBeReplaced() && constructionPolicy.test(pos)) {
            return assessment == SupportAssessment.WATER_HAZARD ? 30.0D : 20.0D;
        }
        return Double.POSITIVE_INFINITY;
    }

    /**
     * Revalidates a planned standing surface immediately before movement.
     * Kept public so the terrain executor uses the same hazard rules as A*.
     */
    public static boolean isSafeStandSupport(ServerLevel level, BlockPos pos, BlockState state) {
        return assessStandSupport(level, pos, state) == SupportAssessment.SAFE;
    }

    /** Classifies a prospective standing surface without loading terrain. */
    public static SupportAssessment assessStandSupport(
            ServerLevel level, BlockPos pos, BlockState expectedState) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(expectedState, "expectedState");
        if (pos.getY() < level.getMinBuildHeight()
                || pos.getY() >= level.getMaxBuildHeight()
                || !level.hasChunkAt(pos)) {
            return SupportAssessment.UNLOADED;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.equals(expectedState)) {
            return SupportAssessment.TARGET_CHANGED;
        }
        FluidHazard fluid = classifyFluid(state);
        if (fluid == FluidHazard.LAVA) {
            return SupportAssessment.LAVA_HAZARD;
        }
        if (fluid == FluidHazard.WATER) {
            return SupportAssessment.WATER_HAZARD;
        }
        return state.getFluidState().isEmpty()
                && !isHazard(state)
                && state.isFaceSturdy(level, pos, Direction.UP)
                ? SupportAssessment.SAFE
                : SupportAssessment.UNSAFE_SUPPORT;
    }

    public ServerLevel level() {
        return level;
    }

    public EntityMaid maid() {
        return maid;
    }

    public BlockPos origin() {
        return origin;
    }

    public int horizontalRadius() {
        return horizontalRadius;
    }

    public int verticalRadius() {
        return verticalRadius;
    }

    /**
     * Revalidates the non-tool safety invariants immediately before a route or
     * target block is broken. This closes the gap between A* evaluation and a
     * multi-tick progressive break when water/lava or a block entity appears.
     */
    public static boolean isSafeToClear(ServerLevel level, BlockPos pos, BlockState expectedState) {
        ClearanceAssessment assessment = assessClearance(level, pos, expectedState);
        return assessment == ClearanceAssessment.CLEAR
                || assessment == ClearanceAssessment.BREAKABLE;
    }

    /**
     * Classifies one clearance cell using the same rules as path evaluation and
     * progressive breaking.  The method is read-only and never loads chunks.
     */
    public static ClearanceAssessment assessClearance(
            ServerLevel level, BlockPos pos, BlockState expectedState) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(expectedState, "expectedState");
        if (pos.getY() < level.getMinBuildHeight()
                || pos.getY() >= level.getMaxBuildHeight()
                || !level.hasChunkAt(pos)) {
            return ClearanceAssessment.UNLOADED;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.equals(expectedState)) {
            return ClearanceAssessment.TARGET_CHANGED;
        }
        FluidHazard directFluid = classifyFluid(state);
        if (directFluid == FluidHazard.LAVA) {
            return ClearanceAssessment.LAVA_HAZARD;
        }
        if (directFluid == FluidHazard.WATER) {
            return ClearanceAssessment.WATER_HAZARD;
        }
        if (isHazard(state) || !state.getFluidState().isEmpty()) {
            return ClearanceAssessment.UNSAFE;
        }
        if (state.getCollisionShape(level, pos).isEmpty()) {
            return ClearanceAssessment.CLEAR;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F || !Float.isFinite(hardness)
                || isProtectedBlock(level, pos, state)) {
            return ClearanceAssessment.PROTECTED_BLOCK;
        }
        FluidHazard exposure = exposedFluidHazard(level, pos);
        if (exposure == FluidHazard.LAVA) {
            return ClearanceAssessment.LAVA_HAZARD;
        }
        if (exposure == FluidHazard.WATER) {
            return ClearanceAssessment.WATER_HAZARD;
        }
        if (exposure == FluidHazard.UNKNOWN) {
            return ClearanceAssessment.UNLOADED;
        }
        if (exposure == FluidHazard.OTHER) {
            return ClearanceAssessment.UNSAFE;
        }
        return ClearanceAssessment.BREAKABLE;
    }

    private static boolean isProtectedBlock(ServerLevel level, BlockPos pos, BlockState state) {
        // Route digging must never use a storage or other stateful block as disposable terrain.
        return state.hasBlockEntity() || level.getBlockEntity(pos) != null;
    }

    private static FluidHazard exposedFluidHazard(ServerLevel level, BlockPos pos) {
        FluidHazard result = FluidHazard.NONE;
        for (Direction direction : FLUID_EXPOSURE_DIRECTIONS) {
            BlockPos adjacent = pos.relative(direction);
            // Unknown terrain is never assumed dry. This also guarantees that
            // evaluating a route cannot synchronously load a neighbouring chunk.
            if (!level.hasChunkAt(adjacent)) {
                if (result == FluidHazard.NONE || result == FluidHazard.OTHER) {
                    result = FluidHazard.UNKNOWN;
                }
                continue;
            }
            FluidHazard adjacentHazard = classifyFluid(level.getBlockState(adjacent));
            if (adjacentHazard == FluidHazard.LAVA) {
                return FluidHazard.LAVA;
            }
            if (adjacentHazard == FluidHazard.WATER) {
                result = FluidHazard.WATER;
            } else if (adjacentHazard == FluidHazard.OTHER && result == FluidHazard.NONE) {
                result = FluidHazard.OTHER;
            }
        }
        return result;
    }

    private static FluidHazard classifyFluid(BlockState state) {
        if (state.getFluidState().isEmpty()) {
            return FluidHazard.NONE;
        }
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return FluidHazard.LAVA;
        }
        if (state.getFluidState().is(FluidTags.WATER)) {
            return FluidHazard.WATER;
        }
        return FluidHazard.OTHER;
    }

    private static ToolChoice toolChoice(ItemStack stack, BlockState state) {
        boolean correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        return new ToolChoice(Math.max(1.0F, stack.getDestroySpeed(state)), correct);
    }

    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    private record ToolChoice(float speed, boolean correct) {
    }

    private enum FluidHazard {
        NONE,
        WATER,
        LAVA,
        OTHER,
        UNKNOWN
    }

    public enum ClearanceAssessment {
        CLEAR,
        BREAKABLE,
        WATER_HAZARD,
        LAVA_HAZARD,
        PROTECTED_BLOCK,
        UNSAFE,
        UNLOADED,
        TARGET_CHANGED
    }

    public enum SupportAssessment {
        SAFE,
        WATER_HAZARD,
        LAVA_HAZARD,
        UNSAFE_SUPPORT,
        UNLOADED,
        TARGET_CHANGED
    }
}
