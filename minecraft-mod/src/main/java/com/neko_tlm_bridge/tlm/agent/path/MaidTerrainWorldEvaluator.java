package com.neko_tlm_bridge.tlm.agent.path;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * Main-thread evaluator over the maid's live server world and leased held tool.
 * It never loads chunks and treats block entities, fluids, hazards, and blocks
 * without a suitable harvesting tool as impassable.
 */
public final class MaidTerrainWorldEvaluator implements MaidTerrainNodeEvaluator {
    public static final int DEFAULT_HORIZONTAL_RADIUS = 64;
    public static final int DEFAULT_VERTICAL_RADIUS = 24;
    private static final Direction[] FLUID_EXPOSURE_DIRECTIONS = {
            Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private final ServerLevel level;
    private final EntityMaid maid;
    private final BlockPos origin;
    private final int horizontalRadius;
    private final int verticalRadius;
    private final long horizontalRadiusSquared;
    private final boolean requireCorrectTool;

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
        if (isHazard(state) || !state.getFluidState().isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        if (state.getFluidState().isEmpty()
                && state.getCollisionShape(level, pos).isEmpty()) {
            return 0.0D;
        }
        if (isProtectedBlock(pos, state) || wouldExposeFluid(pos)) {
            return Double.POSITIVE_INFINITY;
        }

        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F || !Float.isFinite(hardness)) {
            return Double.POSITIVE_INFINITY;
        }

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
        return Math.max(1.0D, ticks);
    }

    @Override
    public boolean canStandOn(BlockPos pos) {
        if (!isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && !isHazard(state)
                && state.isFaceSturdy(level, pos, Direction.UP);
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

    private boolean isProtectedBlock(BlockPos pos, BlockState state) {
        // Route digging must never use a storage or other stateful block as disposable terrain.
        return state.hasBlockEntity() || level.getBlockEntity(pos) != null;
    }

    private boolean wouldExposeFluid(BlockPos pos) {
        for (Direction direction : FLUID_EXPOSURE_DIRECTIONS) {
            BlockPos adjacent = pos.relative(direction);
            // Unknown terrain is never assumed dry. This also guarantees that
            // evaluating a route cannot synchronously load a neighbouring chunk.
            if (!level.hasChunkAt(adjacent)
                    || !level.getBlockState(adjacent).getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
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
}
