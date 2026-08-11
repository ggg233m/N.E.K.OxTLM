package com.neko_tlm_bridge.ws.handler;

import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskAttack;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameContextHandlerTest {
    @Test
    void combatCompatibilityDelegatesToTlmAttackTaskWeaponRules() {
        TaskAttack attack = new TaskAttack();
        JsonObject sword = GameContextHandler.collectCombatTaskCompatibility(
                null, new ItemStack(Items.DIAMOND_SWORD), List.of(attack));
        JsonObject empty = GameContextHandler.collectCombatTaskCompatibility(
                null, ItemStack.EMPTY, List.of(attack));

        assertTrue(sword.get(TaskAttack.UID.toString()).getAsBoolean());
        assertFalse(empty.get(TaskAttack.UID.toString()).getAsBoolean());
    }
}
