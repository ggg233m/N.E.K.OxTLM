package com.neko_tlm_bridge.tlm.agent.action;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 AutonomousMiningAction.isBackpackFull 的背包容量感知逻辑。
 *
 * 覆盖场景:
 * - 空 inventory(0 slots):视为满,因为没有 slot 可装
 * - 有空 slot:未满
 * - 所有 slot 满且可堆叠:满
 * - 未满 stack 算物理余量,但目标掉落仍做兼容性模拟
 * - 目标掉落模拟遵守 slotLimit 和 isItemValid
 * - 不可堆叠物品占满所有 slot:满
 * - null inventory:视为未满(无法判断,不阻塞)
 */
class BackpackCapacityTest {
    @Test
    void nullInventoryIsNotFull() {
        assertFalse(AutonomousMiningAction.isBackpackFull((ItemStackHandler) null));
    }

    @Test
    void emptyInventoryIsFull() {
        ItemStackHandler inventory = new ItemStackHandler(0);
        assertTrue(AutonomousMiningAction.isBackpackFull(inventory));
    }

    @Test
    void inventoryWithEmptySlotIsNotFull() {
        ItemStackHandler inventory = new ItemStackHandler(2);
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 64));
        // slot 1 is empty
        assertFalse(AutonomousMiningAction.isBackpackFull(inventory));
    }

    @Test
    void inventoryWithAllSlotsFullOfStackableItemsIsFull() {
        ItemStackHandler inventory = new ItemStackHandler(2);
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 64));
        inventory.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 64));
        assertTrue(AutonomousMiningAction.isBackpackFull(inventory));
    }

    @Test
    void partialStackIsPhysicalCapacityButExactDropsStillCheckCompatibility() {
        ItemStackHandler inventory = new ItemStackHandler(2);
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 64));
        inventory.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 32));

        assertFalse(AutonomousMiningAction.isBackpackFull(inventory));
        assertFalse(AutonomousMiningAction.canStoreDrops(
                inventory, List.of(new ItemStack(Items.DIAMOND))));
        assertTrue(AutonomousMiningAction.canStoreDrops(
                inventory, List.of(new ItemStack(Items.IRON_INGOT, 32))));
    }

    @Test
    void partialDiamondStackHasCapacityForMoreDiamonds() {
        ItemStackHandler inventory = new ItemStackHandler(1);
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 38));

        AutonomousMiningAction.BackpackCapacitySummary summary =
                AutonomousMiningAction.summarizeBackpackCapacity(inventory);

        assertFalse(summary.full());
        assertEquals(0, summary.emptySlots());
        assertEquals(1, summary.partialStackSlots());
        assertTrue(AutonomousMiningAction.canStoreDrops(
                inventory, List.of(new ItemStack(Items.DIAMOND, 26))));
        assertFalse(AutonomousMiningAction.canStoreDrops(
                inventory, List.of(new ItemStack(Items.DIAMOND, 27))));
    }

    @Test
    void partialStackAtCustomSlotLimitIsFull() {
        ItemStackHandler inventory = new ItemStackHandler(1) {
            @Override
            public int getSlotLimit(int slot) {
                return 16;
            }
        };
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 16));

        assertTrue(AutonomousMiningAction.isBackpackFull(inventory));
    }

    @Test
    void partialStackInNonInsertableSlotIsNotCapacity() {
        ItemStackHandler inventory = new ItemStackHandler(1) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return false;
            }
        };
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 38));

        assertTrue(AutonomousMiningAction.isBackpackFull(inventory));
    }

    @Test
    void dropSimulationRespectsSlotLimit() {
        ItemStackHandler inventory = new ItemStackHandler(1) {
            @Override
            public int getSlotLimit(int slot) {
                return 16;
            }
        };
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 16));

        assertFalse(AutonomousMiningAction.canStoreDrops(
                inventory, List.of(new ItemStack(Items.DIAMOND))));
    }

    @Test
    void dropSimulationRespectsItemValidity() {
        ItemStackHandler inventory = new ItemStackHandler(1) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return stack.is(Items.IRON_INGOT);
            }
        };

        assertFalse(AutonomousMiningAction.canStoreDrops(
                inventory, List.of(new ItemStack(Items.DIAMOND))));
        assertTrue(AutonomousMiningAction.canStoreDrops(
                inventory, List.of(new ItemStack(Items.IRON_INGOT))));
    }

    @Test
    void dropSimulationReservesCapacityAcrossAllDrops() {
        ItemStackHandler inventory = new ItemStackHandler(1);

        assertFalse(AutonomousMiningAction.canStoreDrops(inventory, List.of(
                new ItemStack(Items.DIAMOND, 40),
                new ItemStack(Items.DIAMOND, 40))));
    }

    @Test
    void candidateFilterSkipsEarlierTargetWhoseDropsDoNotFit() {
        ItemStackHandler inventory = new ItemStackHandler(2);
        inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 64));
        inventory.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 63));

        List<String> filtered = AutonomousMiningAction.filterStorableCandidates(
                inventory,
                List.of("diamond", "iron"),
                candidate -> List.of(new ItemStack(
                        candidate.equals("diamond")
                                ? Items.DIAMOND
                                : Items.IRON_INGOT)));

        assertEquals(List.of("iron"), filtered);
    }

    @Test
    void inventoryFullOfNonStackableItemsIsFull() {
        ItemStackHandler inventory = new ItemStackHandler(2);
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        inventory.setStackInSlot(0, pickaxe);
        inventory.setStackInSlot(1, new ItemStack(Items.DIAMOND_SWORD));
        assertTrue(AutonomousMiningAction.isBackpackFull(inventory));
    }

    @Test
    void singleEmptySlotInLargeInventoryMeansNotFull() {
        ItemStackHandler inventory = new ItemStackHandler(15);
        for (int i = 0; i < 14; i++) {
            inventory.setStackInSlot(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        // slot 14 is empty
        assertFalse(AutonomousMiningAction.isBackpackFull(inventory));
    }

    @Test
    void allFifteenSlotsFullMeansFull() {
        ItemStackHandler inventory = new ItemStackHandler(15);
        for (int i = 0; i < 15; i++) {
            inventory.setStackInSlot(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        assertTrue(AutonomousMiningAction.isBackpackFull(inventory));
    }
}
