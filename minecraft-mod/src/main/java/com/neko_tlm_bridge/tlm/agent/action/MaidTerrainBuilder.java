package com.neko_tlm_bridge.tlm.agent.action;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-thread construction primitive for terrain support and fluid seals.
 *
 * <p>Materials are selected only from the maid's real available backpack.
 * Placement extracts exactly one item before invoking
 * {@link CommonHooks#onPlaceItemIntoWorld}, so NeoForge's placement snapshot,
 * cancellation and {@code EntityPlaceEvent} hooks participate. No
 * compensating ItemStack copy is created by this class.</p>
 *
 * <p>This class deliberately does not decide where construction is allowed or
 * useful. A planner must still verify that a bridge/support or seal is part of
 * its current safe route.</p>
 */
public final class MaidTerrainBuilder {
    private static final double MAX_PLACE_DISTANCE_SQUARED = 36.0D;
    private static final String OFFLINE_ACTOR_NAME = "[NekoMaid]";

    private MaidTerrainBuilder() {
    }

    public static Optional<MaterialChoice> chooseMaterial(EntityMaid maid) {
        Objects.requireNonNull(maid, "maid");
        return chooseMaterial(maid.getAvailableBackpackInv());
    }

    /** Visible for deterministic inventory-policy tests. */
    static Optional<MaterialChoice> chooseMaterial(IItemHandler inventory) {
        Objects.requireNonNull(inventory, "inventory");
        MaterialChoice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!isSafeStructuralMaterial(stack)) {
                continue;
            }
            BlockItem blockItem = (BlockItem) stack.getItem();
            Block block = blockItem.getBlock();
            int score = materialScore(block.defaultBlockState(), stack.getCount());
            if (best == null || score > bestScore
                    || score == bestScore && slot < best.slot()) {
                best = new MaterialChoice(
                        slot,
                        BuiltInRegistries.ITEM.getKey(stack.getItem()),
                        BuiltInRegistries.BLOCK.getKey(block),
                        stack.getCount());
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    public static boolean isSafeStructuralMaterial(ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || stack.getItem().getClass() != BlockItem.class
                || stack.getMaxStackSize() <= 1
                || stack.has(DataComponents.BLOCK_ENTITY_DATA)
                || stack.has(DataComponents.CONTAINER)) {
            return false;
        }
        return isSafeStructuralBlock(blockItem.getBlock().defaultBlockState());
    }

    /**
     * Conservative material filter: ordinary, stable, breakable full cubes
     * only. Valuable storage blocks are rejected along with ores and hazards.
     */
    public static boolean isSafeStructuralBlock(BlockState state) {
        if (state == null || state.isAir() || state.hasBlockEntity()
                || state.getBlock() instanceof FallingBlock
                || state.getBlock() instanceof LiquidBlock
                || state.getBlock() instanceof BaseFireBlock
                || !state.getFluidState().isEmpty()
                || state.is(Tags.Blocks.ORES)
                || isOreBlockByIdentity(state)
                || state.is(Tags.Blocks.STORAGE_BLOCKS)
                || isStorageBlockByIdentity(state)
                || state.is(BlockTags.ICE)
                || state.is(BlockTags.CAMPFIRES)
                || state.is(BlockTags.FIRE)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.TNT)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SLIME_BLOCK)
                || state.is(Blocks.HONEY_BLOCK)
                || state.is(Blocks.RESPAWN_ANCHOR)
                || state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0.0F) {
            return false;
        }
        return state.canOcclude()
                && state.isCollisionShapeFullBlock(
                EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** Fallback for when NeoForge Tags.Blocks.ORES is not loaded (unitTest). */
    private static boolean isOreBlockByIdentity(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.COAL_ORE
                || block == Blocks.DEEPSLATE_COAL_ORE
                || block == Blocks.IRON_ORE
                || block == Blocks.DEEPSLATE_IRON_ORE
                || block == Blocks.COPPER_ORE
                || block == Blocks.DEEPSLATE_COPPER_ORE
                || block == Blocks.GOLD_ORE
                || block == Blocks.DEEPSLATE_GOLD_ORE
                || block == Blocks.REDSTONE_ORE
                || block == Blocks.DEEPSLATE_REDSTONE_ORE
                || block == Blocks.EMERALD_ORE
                || block == Blocks.DEEPSLATE_EMERALD_ORE
                || block == Blocks.LAPIS_ORE
                || block == Blocks.DEEPSLATE_LAPIS_ORE
                || block == Blocks.DIAMOND_ORE
                || block == Blocks.DEEPSLATE_DIAMOND_ORE
                || block == Blocks.NETHER_GOLD_ORE
                || block == Blocks.NETHER_QUARTZ_ORE
                || block == Blocks.ANCIENT_DEBRIS;
    }

    /** Fallback for when NeoForge Tags.Blocks.STORAGE_BLOCKS is not loaded (unitTest). */
    private static boolean isStorageBlockByIdentity(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.BONE_BLOCK
                || block == Blocks.COAL_BLOCK
                || block == Blocks.COPPER_BLOCK
                || block == Blocks.DIAMOND_BLOCK
                || block == Blocks.DRIED_KELP_BLOCK
                || block == Blocks.EMERALD_BLOCK
                || block == Blocks.GOLD_BLOCK
                || block == Blocks.HAY_BLOCK
                || block == Blocks.IRON_BLOCK
                || block == Blocks.LAPIS_BLOCK
                || block == Blocks.NETHERITE_BLOCK
                || block == Blocks.REDSTONE_BLOCK
                || block == Blocks.RAW_COPPER_BLOCK
                || block == Blocks.RAW_IRON_BLOCK
                || block == Blocks.RAW_GOLD_BLOCK;
    }

    public static PlacementResult place(
            EntityMaid maid, BlockPos target, Purpose purpose) {
        return place(maid, target, preferredFace(maid, target), purpose);
    }

    /**
     * Extracts and places one safe backpack block. The clicked face is retained
     * in NeoForge's placement event and lets callers describe the supporting
     * surface used by their route plan.
     */
    public static PlacementResult place(
            EntityMaid maid, BlockPos target, Direction clickedFace,
            Purpose purpose) {
        Objects.requireNonNull(maid, "maid");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(clickedFace, "clickedFace");
        Objects.requireNonNull(purpose, "purpose");
        if (!(maid.level() instanceof ServerLevel level)) {
            return PlacementResult.failed(Status.NOT_SERVER_LEVEL, target,
                    "terrain construction is server-only");
        }
        if (!level.getServer().isSameThread()) {
            return PlacementResult.failed(Status.NOT_SERVER_THREAD, target,
                    "terrain construction must run on the server thread");
        }
        if (!maid.isAlive()) {
            return PlacementResult.failed(Status.MAID_NOT_ALIVE, target,
                    "maid is not alive");
        }
        if (!withinBuildHeight(level, target) || !level.hasChunkAt(target)) {
            return PlacementResult.failed(Status.TARGET_UNLOADED, target,
                    "target is outside loaded build space");
        }
        if (maid.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))
                > MAX_PLACE_DISTANCE_SQUARED) {
            return PlacementResult.failed(Status.TARGET_OUT_OF_RANGE, target,
                    "target is outside maid placement reach");
        }

        BlockState replaced = level.getBlockState(target);
        if (!replaced.canBeReplaced()) {
            return PlacementResult.failed(Status.TARGET_NOT_REPLACEABLE, target,
                    "target block is not replaceable");
        }
        boolean containsFluid = !replaced.getFluidState().isEmpty();
        if (purpose == Purpose.SEAL_FLUID && !containsFluid) {
            return PlacementResult.failed(Status.FLUID_REQUIRED, target,
                    "seal placement requires a fluid target");
        }
        if (purpose == Purpose.BRIDGE_SUPPORT && containsFluid) {
            return PlacementResult.failed(Status.FLUID_REQUIRES_SEAL_PURPOSE, target,
                    "fluid replacement requires SEAL_FLUID purpose");
        }

        // This must remain before material selection/extraction. A temporary
        // player conflict is not a placement transaction and must never debit
        // the maid's real backpack.
        MaidTerrainInteractionSafety.Assessment playerSafety =
                MaidTerrainInteractionSafety.assessModification(level, target);
        if (!playerSafety.safe()) {
            Status status = playerSafety.conflict()
                    == MaidTerrainInteractionSafety.Conflict.PLAYER_BODY
                    ? Status.PLAYER_BODY_CONFLICT
                    : Status.PLAYER_SUPPORT_CONFLICT;
            return PlacementResult.failed(status, target,
                    "terrain placement conflicts with "
                            + playerSafety.conflict().wireName());
        }

        UUID ownerId = maid.getOwnerUUID();
        if (ownerId == null) {
            return PlacementResult.failed(Status.OWNER_REQUIRED, target,
                    "terrain construction requires an owned maid");
        }
        Optional<MaterialChoice> selected = chooseMaterial(maid);
        if (selected.isEmpty()) {
            return PlacementResult.failed(Status.NO_SAFE_MATERIAL, target,
                    "maid backpack contains no safe structural block");
        }

        MaterialChoice choice = selected.get();
        IItemHandler inventory = maid.getAvailableBackpackInv();
        ItemStack extracted = inventory.extractItem(choice.slot(), 1, false);
        if (!matchesChoice(extracted, choice)) {
            Rollback rollback = restoreExtracted(inventory, choice.slot(), extracted, maid);
            return PlacementResult.failed(
                    rollback == Rollback.COMPLETE
                            ? Status.INVENTORY_CHANGED : Status.INVENTORY_ROLLBACK_FAILED,
                    target, "selected backpack material changed before extraction");
        }

        BlockItem blockItem = (BlockItem) extracted.getItem();
        ServerPlayer actor = placementActor(level, maid, ownerId);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(target), clickedFace, target, false);
        BlockPlaceContext context = new BlockPlaceContext(
                level, actor, InteractionHand.MAIN_HAND, extracted, hit);
        InteractionResult interaction;
        try {
            interaction = CommonHooks.onPlaceItemIntoWorld(context);
        } catch (RuntimeException failure) {
            if (level.getBlockState(target).is(blockItem.getBlock())) {
                extracted.shrink(extracted.getCount());
                return PlacementResult.placed(choice, target,
                        "placed despite a post-placement exception: "
                                + failure.getClass().getSimpleName());
            }
            Rollback rollback = restoreExtracted(
                    inventory, choice.slot(), extracted, maid);
            return PlacementResult.failed(
                    rollback == Rollback.COMPLETE
                            ? Status.INTERNAL_ERROR : Status.INVENTORY_ROLLBACK_FAILED,
                    target, "placement threw " + failure.getClass().getSimpleName());
        }

        if (interaction.consumesAction()
                && level.getBlockState(target).is(blockItem.getBlock())) {
            // The material was already debited from the maid. A creative or
            // custom placement actor may leave the extracted one-count stack
            // intact; consume that transaction token instead of reinserting it.
            extracted.shrink(extracted.getCount());
            return PlacementResult.placed(choice, target, "placed");
        }

        Rollback rollback = restoreExtracted(inventory, choice.slot(), extracted, maid);
        Status failureStatus = rollback == Rollback.COMPLETE
                ? Status.PLACE_REJECTED : Status.INVENTORY_ROLLBACK_FAILED;
        return PlacementResult.failed(failureStatus, target,
                interaction.consumesAction()
                        ? "placement did not produce the selected block at target"
                        : "placement was rejected or cancelled");
    }

    private static ServerPlayer placementActor(
            ServerLevel level, EntityMaid maid, UUID ownerId) {
        ServerPlayer onlineOwner = level.getServer().getPlayerList().getPlayer(ownerId);
        if (onlineOwner != null && onlineOwner.serverLevel() == level) {
            return onlineOwner;
        }
        GameProfile profile = new GameProfile(ownerId, OFFLINE_ACTOR_NAME);
        FakePlayer actor = FakePlayerFactory.get(level, profile);
        actor.setPos(maid.getX(), maid.getY(), maid.getZ());
        actor.setYRot(maid.getYRot());
        actor.setXRot(maid.getXRot());
        return actor;
    }

    private static boolean matchesChoice(ItemStack extracted, MaterialChoice choice) {
        if (!isSafeStructuralMaterial(extracted)) {
            return false;
        }
        BlockItem blockItem = (BlockItem) extracted.getItem();
        return BuiltInRegistries.ITEM.getKey(extracted.getItem()).equals(choice.itemId())
                && BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).equals(choice.blockId());
    }

    private static Rollback restoreExtracted(
            IItemHandler inventory, int preferredSlot,
            ItemStack extracted, EntityMaid maid) {
        if (extracted == null || extracted.isEmpty()) {
            return Rollback.COMPLETE;
        }
        ItemStack remainder = inventory.insertItem(preferredSlot, extracted, false);
        if (!remainder.isEmpty()) {
            remainder = ItemHandlerHelper.insertItemStacked(inventory, remainder, false);
        }
        if (remainder.isEmpty()) {
            return Rollback.COMPLETE;
        }
        // Preserve the real item even if an event handler filled the inventory
        // during placement. Dropping the remainder is safer than loss or copy.
        maid.spawnAtLocation(remainder);
        return Rollback.DROPPED_REMAINDER;
    }

    private static Direction preferredFace(EntityMaid maid, BlockPos target) {
        if (maid == null || target == null) {
            return Direction.UP;
        }
        BlockPos delta = maid.blockPosition().subtract(target);
        if (Math.abs(delta.getY()) >= Math.max(Math.abs(delta.getX()), Math.abs(delta.getZ()))) {
            return delta.getY() >= 0 ? Direction.UP : Direction.DOWN;
        }
        if (Math.abs(delta.getX()) >= Math.abs(delta.getZ())) {
            return delta.getX() >= 0 ? Direction.EAST : Direction.WEST;
        }
        return delta.getZ() >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static boolean withinBuildHeight(ServerLevel level, BlockPos pos) {
        return pos.getY() >= level.getMinBuildHeight()
                && pos.getY() < level.getMaxBuildHeight();
    }

    private static int materialScore(BlockState state, int count) {
        int category = 100;
        if (state.is(Tags.Blocks.COBBLESTONES) || isCobblestoneByIdentity(state)) {
            category = 500;
        } else if (state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)) {
            category = 400;
        } else if (state.is(BlockTags.DIRT)) {
            category = 300;
        } else if (state.is(BlockTags.PLANKS)) {
            category = 200;
        }
        return category * 1000 + Math.min(999, Math.max(0, count));
    }

    /** Fallback for when NeoForge Tags.Blocks.COBBLESTONES is not loaded (unitTest). */
    private static boolean isCobblestoneByIdentity(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.COBBLESTONE
                || block == Blocks.COBBLED_DEEPSLATE
                || block == Blocks.MOSSY_COBBLESTONE;
    }

    public enum Purpose {
        BRIDGE_SUPPORT,
        SEAL_FLUID
    }

    public enum Status {
        PLACED,
        NOT_SERVER_LEVEL,
        NOT_SERVER_THREAD,
        MAID_NOT_ALIVE,
        TARGET_UNLOADED,
        TARGET_OUT_OF_RANGE,
        TARGET_NOT_REPLACEABLE,
        FLUID_REQUIRED,
        FLUID_REQUIRES_SEAL_PURPOSE,
        PLAYER_BODY_CONFLICT,
        PLAYER_SUPPORT_CONFLICT,
        OWNER_REQUIRED,
        NO_SAFE_MATERIAL,
        INVENTORY_CHANGED,
        PLACE_REJECTED,
        INVENTORY_ROLLBACK_FAILED,
        INTERNAL_ERROR
    }

    public record MaterialChoice(
            int slot,
            ResourceLocation itemId,
            ResourceLocation blockId,
            int availableCount) {
        public MaterialChoice {
            if (slot < 0 || availableCount < 1) {
                throw new IllegalArgumentException("invalid material choice");
            }
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(blockId, "blockId");
        }
    }

    public record PlacementResult(
            Status status,
            BlockPos target,
            ResourceLocation blockId,
            int inventorySlot,
            String detail) {
        public PlacementResult {
            Objects.requireNonNull(status, "status");
            target = Objects.requireNonNull(target, "target").immutable();
            detail = String.valueOf(detail == null ? "" : detail);
        }

        public boolean placed() {
            return status == Status.PLACED;
        }

        private static PlacementResult placed(
                MaterialChoice choice, BlockPos target, String detail) {
            return new PlacementResult(Status.PLACED, target,
                    choice.blockId(), choice.slot(), detail);
        }

        private static PlacementResult failed(
                Status status, BlockPos target, String detail) {
            return new PlacementResult(status, target, null, -1, detail);
        }
    }

    private enum Rollback {
        COMPLETE,
        DROPPED_REMAINDER
    }
}
