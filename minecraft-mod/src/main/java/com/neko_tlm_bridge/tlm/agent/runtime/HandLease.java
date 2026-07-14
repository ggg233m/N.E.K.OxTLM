package com.neko_tlm_bridge.tlm.agent.runtime;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.Objects;

/**
 * Owns a temporary main-hand/backpack-slot swap without ever copying an
 * {@link ItemStack}.  A lease is deliberately conservative: when either slot
 * no longer contains the stack the agent expects, release leaves both slots
 * untouched and reports a conflict.
 */
public final class HandLease {
    public static final int HELD_TOOL_SLOT = -1;

    private static final String TAG_SOURCE_SLOT = "source_slot";
    private static final String TAG_EXPECTED_HAND = "expected_hand";
    private static final String TAG_EXPECTED_SOURCE = "expected_source";

    private final int sourceSlot;
    private StackFingerprint expectedHand;
    private StackFingerprint expectedSource;
    private boolean released;

    private HandLease(int sourceSlot, StackFingerprint expectedHand,
                      StackFingerprint expectedSource) {
        this.sourceSlot = sourceSlot;
        this.expectedHand = Objects.requireNonNull(expectedHand);
        this.expectedSource = Objects.requireNonNull(expectedSource);
    }

    /**
     * Observes the maid's existing held item.  No inventory slot is changed.
     */
    public static HandLease heldTool(EntityMaid maid) {
        Objects.requireNonNull(maid, "maid");
        return new HandLease(HELD_TOOL_SLOT, StackFingerprint.of(maid.getMainHandItem()),
                StackFingerprint.EMPTY);
    }

    /**
     * Moves the real stack from {@code sourceSlot} into the main hand and the
     * real previous held stack into that backpack slot.
     */
    public static HandLease equipFromBackpack(EntityMaid maid, int sourceSlot) {
        Objects.requireNonNull(maid, "maid");
        IItemHandlerModifiable inventory = maid.getAvailableBackpackInv();
        if (sourceSlot < 0 || sourceSlot >= inventory.getSlots()) {
            throw new IllegalArgumentException("Invalid maid backpack slot: " + sourceSlot);
        }

        ItemStack tool = inventory.getStackInSlot(sourceSlot);
        if (tool.isEmpty()) {
            throw new IllegalArgumentException("Maid backpack slot is empty: " + sourceSlot);
        }

        ItemStack originalHand = maid.getMainHandItem();
        HandLease lease = new HandLease(sourceSlot, StackFingerprint.of(tool),
                StackFingerprint.of(originalHand));
        // Persist expected post-swap slots before mutating either real slot.
        lease.persistIntoBodyLease(maid);
        inventory.setStackInSlot(sourceSlot, originalHand);
        maid.setItemInHand(InteractionHand.MAIN_HAND, tool);
        return lease;
    }

    /** Recreates a lease after entity NBT loading; it does not move items. */
    public static HandLease fromTag(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        return new HandLease(tag.getInt(TAG_SOURCE_SLOT),
                StackFingerprint.fromTag(tag.getCompound(TAG_EXPECTED_HAND)),
                StackFingerprint.fromTag(tag.getCompound(TAG_EXPECTED_SOURCE)));
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_SOURCE_SLOT, sourceSlot);
        tag.put(TAG_EXPECTED_HAND, expectedHand.toTag());
        tag.put(TAG_EXPECTED_SOURCE, expectedSource.toTag());
        return tag;
    }

    public int sourceSlot() {
        return sourceSlot;
    }

    public boolean movedToolFromBackpack() {
        return sourceSlot != HELD_TOOL_SLOT;
    }

    /**
     * Updates the expected held fingerprint after an intentional mutation such
     * as durability loss or tool breakage.
     */
    public void acknowledgeHeldToolMutation(EntityMaid maid) {
        requireOpen();
        expectedHand = StackFingerprint.of(maid.getMainHandItem());
        persistIntoBodyLease(maid);
    }

    public LeaseHealth validate(EntityMaid maid) {
        requireOpen();
        if (!expectedHand.matches(maid.getMainHandItem())) {
            return LeaseHealth.HAND_CONFLICT;
        }
        if (sourceSlot == HELD_TOOL_SLOT) {
            return LeaseHealth.HEALTHY;
        }

        IItemHandlerModifiable inventory = maid.getAvailableBackpackInv();
        if (sourceSlot >= inventory.getSlots()
                || !expectedSource.matches(inventory.getStackInSlot(sourceSlot))) {
            return LeaseHealth.SOURCE_CONFLICT;
        }
        return LeaseHealth.HEALTHY;
    }

    /**
     * Restores the original real stacks if both locations still match.  No
     * copies are created, and a conflict never performs a partial restore.
     */
    public ReleaseResult release(EntityMaid maid) {
        if (released) {
            return ReleaseResult.ALREADY_RELEASED;
        }
        LeaseHealth health = validate(maid);
        if (health != LeaseHealth.HEALTHY) {
            released = true;
            return ReleaseResult.HAND_CONFLICT;
        }
        if (sourceSlot == HELD_TOOL_SLOT) {
            released = true;
            return ReleaseResult.RESTORED;
        }

        IItemHandlerModifiable inventory = maid.getAvailableBackpackInv();
        ItemStack currentTool = maid.getMainHandItem();
        ItemStack originalHand = inventory.getStackInSlot(sourceSlot);
        inventory.setStackInSlot(sourceSlot, currentTool);
        maid.setItemInHand(InteractionHand.MAIN_HAND, originalHand);
        released = true;
        return ReleaseResult.RESTORED;
    }

    private void requireOpen() {
        if (released) {
            throw new IllegalStateException("Hand lease is already released");
        }
    }

    private void persistIntoBodyLease(EntityMaid maid) {
        CompoundTag persistent = maid.getPersistentData();
        if (!persistent.contains(MaidBodyLease.PERSISTENT_TAG)) {
            return;
        }
        CompoundTag bodyTag = persistent.getCompound(MaidBodyLease.PERSISTENT_TAG);
        bodyTag.put("hand", toTag());
        persistent.put(MaidBodyLease.PERSISTENT_TAG, bodyTag);
    }

    public enum LeaseHealth {
        HEALTHY,
        HAND_CONFLICT,
        SOURCE_CONFLICT
    }

    public enum ReleaseResult {
        RESTORED,
        HAND_CONFLICT,
        ALREADY_RELEASED
    }

    /** A value fingerprint only; never use it to recreate an ItemStack. */
    public record StackFingerprint(String itemId, int count, int damage, int componentsHash) {
        public static final StackFingerprint EMPTY = new StackFingerprint("", 0, 0, 0);

        public static StackFingerprint of(ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return EMPTY;
            }
            return new StackFingerprint(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(), stack.getDamageValue(), stack.getComponents().hashCode());
        }

        public boolean matches(ItemStack stack) {
            return equals(of(stack));
        }

        private CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("item", itemId);
            tag.putInt("count", count);
            tag.putInt("damage", damage);
            tag.putInt("components_hash", componentsHash);
            return tag;
        }

        private static StackFingerprint fromTag(CompoundTag tag) {
            return new StackFingerprint(tag.getString("item"), tag.getInt("count"),
                    tag.getInt("damage"), tag.getInt("components_hash"));
        }
    }
}
