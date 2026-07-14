package com.neko_tlm_bridge.gametest;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Small world-level smoke tests for the two public phase-one actions. */
@GameTestHolder("neko_tlm_bridge")
@PrefixGameTestTemplate(false)
public final class MaidAgentGameTests {
    private MaidAgentGameTests() {
    }

    @GameTest(template = "maid_agent_test", timeoutTicks = 400)
    public static void navigateAcrossLoadedFloor(GameTestHelper helper) {
        prepareFloor(helper, 1, 11, 1);
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(1, 1, 1));
        BlockPos target = helper.absolutePos(new BlockPos(10, 1, 1));
        UUID actionId = UUID.randomUUID();

        JsonObject args = new JsonObject();
        args.add("target", position(target));
        args.addProperty("speed", 0.8D);
        args.addProperty("stop_distance", 1.5D);
        helper.runAfterDelay(5, () -> {
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.NAVIGATE, args, 15_000L, true);
            helper.assertTrue(start.accepted(), "navigate action should be accepted");
        });

        helper.succeedWhen(() -> {
            var current = MaidActionStore.getInstance().getStatus(actionId);
            helper.assertTrue(current.isPresent(), "navigate action has not started yet");
            JsonObject status = current.orElseThrow();
            helper.assertTrue("SUCCEEDED".equals(status.get("status").getAsString()),
                    "navigate action should succeed, current=" + status);
            helper.assertTrue(maid.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.5D,
                    "maid should arrive inside stop distance");
        });
    }

    @GameTest(template = "maid_agent_test", timeoutTicks = 400)
    public static void harvestVisibleStoneWithRealTool(GameTestHelper helper) {
        prepareFloor(helper, 1, 10, 1);
        BlockPos relativeTarget = new BlockPos(8, 1, 1);
        helper.setBlock(relativeTarget, Blocks.STONE);
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(1, 1, 1));
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        BlockPos target = helper.absolutePos(relativeTarget);
        UUID actionId = UUID.randomUUID();

        JsonObject args = new JsonObject();
        args.add("target_pos", position(target));
        args.addProperty("search_radius", 12);
        args.addProperty("max_blocks", 1);
        args.addProperty("tool_policy", "require_correct");
        args.addProperty("speed", 0.8D);
        helper.runAfterDelay(5, () -> {
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.HARVEST_BLOCKS, args, 20_000L, true);
            helper.assertTrue(start.accepted(), "harvest action should be accepted");
        });

        helper.succeedWhen(() -> {
            var current = MaidActionStore.getInstance().getStatus(actionId);
            helper.assertTrue(current.isPresent(), "harvest action has not started yet");
            JsonObject status = current.orElseThrow();
            helper.assertTrue("SUCCEEDED".equals(status.get("status").getAsString()),
                    "harvest action should succeed, current=" + status);
            helper.assertTrue(helper.getBlockState(relativeTarget).isAir(),
                    "target stone should be removed");
        });
    }

    private static void prepareFloor(GameTestHelper helper, int startX, int endX, int z) {
        for (int x = startX; x <= endX; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(new BlockPos(x, 0, z + dz), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 1, z + dz), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 2, z + dz), Blocks.AIR);
            }
        }
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }
}
