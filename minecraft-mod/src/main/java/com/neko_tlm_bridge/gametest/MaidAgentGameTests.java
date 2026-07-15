package com.neko_tlm_bridge.gametest;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidBodyLease;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
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

    @GameTest(template = "maid_agent_test", timeoutTicks = 800)
    public static void harvestBehindTwoBlockHighWallClearsFullPassage(GameTestHelper helper) {
        for (int x = 0; x <= 10; x++) {
            helper.setBlock(new BlockPos(x, 0, 2), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 3, 2), Blocks.BEDROCK);
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 1), Blocks.BEDROCK);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.BEDROCK);
            }
        }
        BlockPos wallFeet = new BlockPos(4, 1, 2);
        BlockPos wallHead = wallFeet.above();
        BlockPos relativeTarget = new BlockPos(8, 1, 2);
        helper.setBlock(wallFeet, Blocks.DIRT);
        helper.setBlock(wallHead, Blocks.DIRT);
        helper.setBlock(relativeTarget, Blocks.GRANITE);

        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(1, 1, 2));
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        UUID actionId = UUID.randomUUID();
        JsonObject args = new JsonObject();
        args.add("target_pos", position(helper.absolutePos(relativeTarget)));
        args.addProperty("search_radius", 10);
        args.addProperty("max_blocks", 1);
        args.addProperty("tool_policy", "require_correct");
        args.addProperty("speed", 0.8D);

        helper.runAfterDelay(5, () -> {
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.HARVEST_BLOCKS, args, 35_000L, true);
            helper.assertTrue(start.accepted(), "two-block passage harvest should be accepted");
        });
        helper.succeedWhen(() -> {
            JsonObject status = MaidActionStore.getInstance().getStatus(actionId).orElseThrow();
            helper.assertTrue("SUCCEEDED".equals(status.get("status").getAsString()),
                    "maid should clear the full-height passage and harvest the target, current=" + status);
            helper.assertTrue(helper.getBlockState(wallFeet).isAir(),
                    "passage feet cell should be cleared");
            helper.assertTrue(helper.getBlockState(wallHead).isAir(),
                    "passage head cell should be cleared");
            helper.assertTrue(helper.getBlockState(relativeTarget).isAir(),
                    "target behind the wall should be harvested");
        });
    }

    @GameTest(template = "maid_agent_test", timeoutTicks = 400)
    public static void harvestSelectorPrefersExposedBaseStone(GameTestHelper helper) {
        prepareFloorArea(helper, 0, 10, 0, 10, Blocks.STONE);
        BlockPos relativeTarget = new BlockPos(8, 1, 1);
        helper.setBlock(relativeTarget, Blocks.GRANITE);
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(1, 1, 1));
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        UUID actionId = UUID.randomUUID();

        JsonObject selector = new JsonObject();
        selector.addProperty("type", "tag");
        selector.addProperty("id", "minecraft:base_stone_overworld");
        JsonObject args = new JsonObject();
        args.add("selector", selector);
        args.addProperty("search_radius", 7);
        args.addProperty("max_blocks", 1);
        args.addProperty("tool_policy", "require_correct");
        args.addProperty("speed", 0.8D);
        helper.runAfterDelay(5, () -> {
            BlockPos standPos = helper.absolutePos(new BlockPos(7, 1, 1));
            var fixturePath = maid.getNavigation().createPath(standPos, 0);
            helper.assertTrue(fixturePath != null && fixturePath.canReach(),
                    "selector test fixture must provide a reachable adjacent stand position");
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.HARVEST_BLOCKS, args, 20_000L, true);
            helper.assertTrue(start.accepted(), "selector harvest action should be accepted");
        });

        helper.succeedWhen(() -> {
            var current = MaidActionStore.getInstance().getStatus(actionId);
            helper.assertTrue(current.isPresent(), "selector harvest action has not started yet");
            JsonObject status = current.orElseThrow();
            helper.assertTrue("SUCCEEDED".equals(status.get("status").getAsString()),
                    "selector harvest action should succeed, current=" + status);
            helper.assertTrue(helper.getBlockState(relativeTarget).isAir(),
                    "exposed granite selector target should be removed");
            helper.assertTrue(helper.getBlockState(new BlockPos(1, 0, 1)).is(Blocks.STONE),
                    "selector must not mine the maid's support block");
            helper.assertTrue(helper.getBlockState(new BlockPos(2, 0, 1)).is(Blocks.STONE),
                    "selector should prefer the exposed target over a nearby stone floor");
        });
    }

    @GameTest(template = "maid_agent_test", timeoutTicks = 800)
    public static void harvestBuriedBaseStoneOpensShortTunnel(GameTestHelper helper) {
        // A dirt cap covers an entire stone stratum. There is deliberately no
        // pre-existing air cell beside any target, reproducing the old
        // safe_stand_candidates=0 failure from a normal overworld surface.
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.DIRT);
                helper.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 4, z), Blocks.AIR);
            }
        }
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(1, 3, 2));
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        UUID actionId = UUID.randomUUID();

        JsonObject selector = new JsonObject();
        selector.addProperty("type", "tag");
        selector.addProperty("id", "minecraft:base_stone_overworld");
        JsonObject args = new JsonObject();
        args.add("selector", selector);
        args.addProperty("search_radius", 7);
        args.addProperty("max_blocks", 1);
        args.addProperty("tool_policy", "require_correct");
        args.addProperty("speed", 0.8D);
        helper.runAfterDelay(5, () -> {
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.HARVEST_BLOCKS, args, 35_000L, true);
            helper.assertTrue(start.accepted(), "buried harvest action should be accepted");
        });

        helper.succeedWhen(() -> {
            var current = MaidActionStore.getInstance().getStatus(actionId);
            helper.assertTrue(current.isPresent(), "buried harvest action has not started yet");
            JsonObject status = current.orElseThrow();
            helper.assertTrue("SUCCEEDED".equals(status.get("status").getAsString()),
                    "terrain-aware harvest should reach buried stone, current=" + status);
            boolean openedStone = false;
            for (int x = 0; x <= 10 && !openedStone; x++) {
                for (int z = 0; z <= 4; z++) {
                    if (helper.getBlockState(new BlockPos(x, 1, z)).isAir()) {
                        openedStone = true;
                        break;
                    }
                }
            }
            helper.assertTrue(openedStone, "terrain-aware route should have mined one buried base-stone block");
        });
    }

    @GameTest(template = "maid_agent_test", timeoutTicks = 400)
    public static void activeLeaseSuppressesScheduleTeleport(GameTestHelper helper) {
        prepareFloor(helper, 1, 11, 1);
        BlockPos relativeTarget = new BlockPos(2, 1, 1);
        helper.setBlock(relativeTarget, Blocks.OBSIDIAN);
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(1, 1, 1));
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        BlockPos staleScheduleHome = helper.absolutePos(new BlockPos(10, 1, 1));
        maid.getSchedulePos().setWorkPos(staleScheduleHome);
        maid.getSchedulePos().setIdlePos(staleScheduleHome);
        maid.getSchedulePos().setSleepPos(staleScheduleHome);
        maid.getSchedulePos().setConfigured(true);
        maid.getPersistentData().put(MaidBodyLease.PERSISTENT_TAG, new CompoundTag());
        helper.assertTrue(!MaidBodyLease.hasRecoverablePersistentLease(maid),
                "malformed lease evidence must not disable the maid schedule forever");
        maid.getPersistentData().remove(MaidBodyLease.PERSISTENT_TAG);
        UUID actionId = UUID.randomUUID();
        Vec3[] previousPosition = {maid.position()};

        JsonObject args = new JsonObject();
        args.add("target_pos", position(helper.absolutePos(relativeTarget)));
        args.addProperty("search_radius", 12);
        args.addProperty("max_blocks", 1);
        args.addProperty("tool_policy", "require_correct");
        args.addProperty("speed", 0.7D);
        helper.runAfterDelay(5, () -> {
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.HARVEST_BLOCKS, args, 20_000L, true);
            helper.assertTrue(start.accepted(), "long harvest action should be accepted");
            // SchedulePos.tick runs every 40 entity ticks. Force the very next
            // tick onto that boundary while the Agent lease is live.
            maid.tickCount = 39;
        });
        helper.runAfterDelay(15, () -> {
            MaidActionStore.CancelResult cancel = MaidActionStore.getInstance().requestCancel(actionId);
            helper.assertTrue(cancel.accepted(), "long harvest action should accept cancellation");
        });
        helper.onEachTick(() -> {
            if (MaidActionStore.getInstance().getActiveStatus(maid.getUUID()).isPresent()) {
                helper.assertTrue(maid.position().distanceToSqr(previousPosition[0]) < 9.0D,
                        "TLM schedule tick teleported a maid while the Agent lease was active");
            }
            previousPosition[0] = maid.position();
        });

        helper.succeedWhen(() -> {
            var current = MaidActionStore.getInstance().getStatus(actionId);
            helper.assertTrue(current.isPresent(), "long harvest action has not started yet");
            JsonObject status = current.orElseThrow();
            helper.assertTrue("CANCELLED".equals(status.get("status").getAsString()),
                    "long harvest action should cancel without teleporting, current=" + status);
            helper.assertTrue(maid.position().distanceToSqr(
                            Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)))) < 9.0D,
                    "maid should remain near the Agent action origin");
        });
    }

    private static void prepareFloor(GameTestHelper helper, int startX, int endX, int z) {
        prepareFloor(helper, startX, endX, z, Blocks.STONE);
    }

    private static void prepareFloor(GameTestHelper helper, int startX, int endX, int z, Block floor) {
        prepareFloorArea(helper, startX, endX, z - 1, z + 1, floor);
    }

    private static void prepareFloorArea(GameTestHelper helper, int startX, int endX,
                                         int startZ, int endZ, Block floor) {
        for (int x = startX; x <= endX; x++) {
            for (int z = startZ; z <= endZ; z++) {
                helper.setBlock(new BlockPos(x, 0, z), floor);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
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
