package com.neko_tlm_bridge.tlm.agent.action;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidTerrainBuilderTest {
    @Test
    void acceptsOrdinaryStableFullCubes() {
        assertTrue(MaidTerrainBuilder.isSafeStructuralBlock(
                Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(MaidTerrainBuilder.isSafeStructuralBlock(
                Blocks.STONE.defaultBlockState()));
        assertTrue(MaidTerrainBuilder.isSafeStructuralBlock(
                Blocks.DIRT.defaultBlockState()));
        assertTrue(MaidTerrainBuilder.isSafeStructuralBlock(
                Blocks.GRANITE.defaultBlockState()));
    }

    @Test
    void rejectsGravityFunctionalHazardousValuableAndNonFullBlocks() {
        List.of(
                Blocks.SAND,
                Blocks.GRAVEL,
                Blocks.CHEST,
                Blocks.FURNACE,
                Blocks.MAGMA_BLOCK,
                Blocks.TNT,
                Blocks.CACTUS,
                Blocks.POWDER_SNOW,
                Blocks.ICE,
                Blocks.COAL_ORE,
                Blocks.IRON_BLOCK,
                Blocks.OAK_SLAB
        ).forEach(block -> assertFalse(
                MaidTerrainBuilder.isSafeStructuralBlock(block.defaultBlockState()),
                () -> "unsafe construction material accepted: "
                        + BuiltInRegistries.BLOCK.getKey(block)));
    }

    @Test
    void selectionUsesRealSlotAndPrefersCommonStructuralMaterial() {
        ItemStackHandler inventory = new ItemStackHandler(6);
        inventory.setStackInSlot(0, new ItemStack(Blocks.SAND, 64));
        inventory.setStackInSlot(1, new ItemStack(Blocks.CHEST, 4));
        inventory.setStackInSlot(2, new ItemStack(Blocks.DIRT, 64));
        inventory.setStackInSlot(3, new ItemStack(Blocks.STONE, 16));
        inventory.setStackInSlot(4, new ItemStack(Blocks.COBBLESTONE, 4));
        inventory.setStackInSlot(5, new ItemStack(Blocks.COAL_ORE, 64));

        MaidTerrainBuilder.MaterialChoice choice =
                MaidTerrainBuilder.chooseMaterial(inventory).orElseThrow();

        assertEquals(4, choice.slot());
        assertEquals(BuiltInRegistries.BLOCK.getKey(Blocks.COBBLESTONE),
                choice.blockId());
        assertEquals(4, choice.availableCount());
        assertEquals(4, inventory.getStackInSlot(4).getCount(),
                "selection must not mutate or extract the real stack");
    }

    @Test
    void selectionFallsBackToOtherSafeFullCubeAndRejectsUnsafeOnlyInventory() {
        ItemStackHandler inventory = new ItemStackHandler(3);
        inventory.setStackInSlot(0, new ItemStack(Blocks.GRANITE, 8));
        inventory.setStackInSlot(1, new ItemStack(Blocks.SAND, 64));

        MaidTerrainBuilder.MaterialChoice choice =
                MaidTerrainBuilder.chooseMaterial(inventory).orElseThrow();
        assertEquals(0, choice.slot());
        assertEquals(BuiltInRegistries.BLOCK.getKey(Blocks.GRANITE),
                choice.blockId());

        inventory.setStackInSlot(0, ItemStack.EMPTY);
        inventory.setStackInSlot(2, new ItemStack(Blocks.TNT, 3));
        assertTrue(MaidTerrainBuilder.chooseMaterial(inventory).isEmpty());
    }
}
