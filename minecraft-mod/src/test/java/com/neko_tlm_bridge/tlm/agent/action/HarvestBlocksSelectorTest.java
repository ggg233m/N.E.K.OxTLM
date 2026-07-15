package com.neko_tlm_bridge.tlm.agent.action;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarvestBlocksSelectorTest {
    @Test
    void recognizesVanillaAndCommonOreSelectorNames() {
        assertTrue(HarvestBlocksAction.looksLikeOreSelector(
                "block", ResourceLocation.parse("minecraft:diamond_ore")));
        assertTrue(HarvestBlocksAction.looksLikeOreSelector(
                "tag", ResourceLocation.parse("minecraft:diamond_ores")));
        assertTrue(HarvestBlocksAction.looksLikeOreSelector(
                "tag", ResourceLocation.parse("c:ores/diamond")));
        assertFalse(HarvestBlocksAction.looksLikeOreSelector(
                "tag", ResourceLocation.parse("minecraft:logs")));
        assertFalse(HarvestBlocksAction.looksLikeOreSelector(
                "block", ResourceLocation.parse("example:decorative_ore")));
        assertFalse(HarvestBlocksAction.looksLikeOreSelector(
                "tag", ResourceLocation.parse("minecraft:not_real_ores")));
        assertTrue(HarvestBlocksAction.isAnyOre(Blocks.IRON_ORE.defaultBlockState()));
        assertFalse(HarvestBlocksAction.isAnyOre(Blocks.STONE.defaultBlockState()));
    }
}
