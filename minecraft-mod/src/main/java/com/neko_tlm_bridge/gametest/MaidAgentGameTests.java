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

    @GameTest(template = "maid_agent_test", timeoutTicks = 1000)
    public static void forwardProspectingFindsOreBeyondInitialScan(GameTestHelper helper) {
        for (int x = 0; x <= 9; x++) {
            helper.setBlock(new BlockPos(x, 0, 2), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 3, 2), Blocks.BEDROCK);
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 1), Blocks.BEDROCK);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.BEDROCK);
            }
        }
        for (int x = 2; x <= 5; x++) {
            helper.setBlock(new BlockPos(x, 1, 2), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 2, 2), Blocks.STONE);
        }
        BlockPos ore = new BlockPos(6, 1, 2);
        helper.setBlock(ore, Blocks.COAL_ORE);

        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(1, 1, 2));
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        UUID actionId = UUID.randomUUID();
        JsonObject args = selectorHarvestArgs("block", "minecraft:coal_ore", 2);
        args.add("mining_plan", miningPlan(
                "forward_tunnel", "east", 4, 0, 8));

        helper.runAfterDelay(5, () -> {
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.HARVEST_BLOCKS, args, 45_000L, true);
            helper.assertTrue(start.accepted(), "forward prospecting action should be accepted");
        });
        helper.succeedWhen(() -> {
            JsonObject status = MaidActionStore.getInstance().getStatus(actionId).orElseThrow();
            helper.assertTrue("SUCCEEDED".equals(status.get("status").getAsString()),
                    "forward prospecting should discover and harvest coal, current=" + status);
            JsonObject result = status.getAsJsonObject("result");
            helper.assertTrue(result.get("prospect_steps").getAsInt() >= 2,
                    "ore must only be found after advancing the prospect tunnel");
            helper.assertTrue(helper.getBlockState(ore).isAir(),
                    "coal beyond the initial scan radius should be harvested");
        });
    }

    @GameTest(template = "maid_agent_test", timeoutTicks = 1600)
    public static void forwardProspectingContinuesFromLivePositionInSecondSegment(
            GameTestHelper helper) {
        for (int x = 0; x <= 13; x++) {
            helper.setBlock(new BlockPos(x, 0, 2), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 3, 2), Blocks.BEDROCK);
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 1), Blocks.BEDROCK);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.BEDROCK);
            }
        }
        for (int x = 2; x <= 11; x++) {
            helper.setBlock(new BlockPos(x, 1, 2), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 2, 2), Blocks.STONE);
        }
        BlockPos ore = new BlockPos(12, 1, 2);
        helper.setBlock(ore, Blocks.COAL_ORE);

        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(1, 1, 2));
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        UUID actionId = UUID.randomUUID();
        JsonObject args = selectorHarvestArgs("block", "minecraft:coal_ore", 1);
        args.add("mining_plan", miningPlan(
                "forward_tunnel", "east", 8, 0, 24, 1));

        helper.runAfterDelay(5, () -> {
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.HARVEST_BLOCKS, args, 60_000L, true);
            helper.assertTrue(start.accepted(),
                    "multi-segment forward prospecting action should be accepted");
        });
        helper.succeedWhen(() -> {
            JsonObject status = MaidActionStore.getInstance().getStatus(actionId).orElseThrow();
            helper.assertTrue("SUCCEEDED".equals(status.get("status").getAsString()),
                    "second prospecting segment should discover and harvest coal, current=" + status);
            JsonObject result = status.getAsJsonObject("result");
            helper.assertTrue(result.get("prospect_steps").getAsInt() >= 10,
                    "ore must only become visible after ten completed prospecting steps");
            helper.assertTrue(result.get("prospect_segment").getAsInt() == 2,
                    "terminal diagnostics should report the second prospecting segment");
            helper.assertTrue(result.get("prospect_unbounded").getAsBoolean(),
                    "legacy one-segment limit must not terminate continuous prospecting");
            helper.assertTrue(helper.getBlockState(ore).isAir(),
                    "coal beyond the first eight-step segment should be harvested");
        });
    }

    @GameTest(template = "maid_agent_test", timeoutTicks = 1000)
    public static void staircaseProspectingDescendsWithoutDiggingCurrentSupport(GameTestHelper helper) {
        BlockPos originalSupport = new BlockPos(1, 4, 2);
        helper.setBlock(originalSupport, Blocks.STONE);
        for (int step = 1; step <= 4; step++) {
            int x = 1 + step;
            int destinationY = 5 - step;
            helper.setBlock(new BlockPos(x, destinationY - 1, 2), Blocks.STONE);
            for (int y = destinationY; y <= destinationY + 2; y++) {
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
            }
        }
        BlockPos ore = new BlockPos(6, 1, 2);
        helper.setBlock(new BlockPos(6, 0, 2), Blocks.STONE);
        helper.setBlock(ore, Blocks.COAL_ORE);

        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(1, 5, 2));
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        UUID actionId = UUID.randomUUID();
        JsonObject args = selectorHarvestArgs("block", "minecraft:coal_ore", 1);
        args.add("mining_plan", miningPlan(
                "staircase_down", "east", 4, 4, 12));

        helper.runAfterDelay(5, () -> {
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.HARVEST_BLOCKS, args, 45_000L, true);
            helper.assertTrue(start.accepted(), "staircase prospecting action should be accepted");
        });
        helper.succeedWhen(() -> {
            JsonObject status = MaidActionStore.getInstance().getStatus(actionId).orElseThrow();
            helper.assertTrue("SUCCEEDED".equals(status.get("status").getAsString()),
                    "staircase prospecting should discover and harvest coal, current=" + status);
            JsonObject result = status.getAsJsonObject("result");
            helper.assertTrue(result.get("prospect_descent_steps").getAsInt() == 4,
                    "staircase should perform four stable diagonal descents");
            helper.assertTrue(helper.getBlockState(originalSupport).is(Blocks.STONE),
                    "staircase prospecting must never dig the maid's current support block");
            helper.assertTrue(helper.getBlockState(ore).isAir(),
                    "coal at the bottom of the staircase should be harvested");
        });
    }

    @GameTest(template = "maid_agent_test", timeoutTicks = 500)
    public static void prospectingRechecksFluidBeforeBreakCommit(GameTestHelper helper) {
        prepareFloorArea(helper, 0, 4, 0, 4, Blocks.STONE);
        BlockPos obstacle = new BlockPos(2, 1, 2);
        BlockPos fluid = new BlockPos(2, 1, 1);
        helper.setBlock(obstacle, Blocks.OBSIDIAN);
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(1, 1, 2));
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        UUID actionId = UUID.randomUUID();
        JsonObject args = selectorHarvestArgs("block", "minecraft:coal_ore", 1);
        args.add("mining_plan", miningPlan(
                "forward_tunnel", "east", 1, 0, 2));

        helper.runAfterDelay(5, () -> {
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.HARVEST_BLOCKS, args, 20_000L, true);
            helper.assertTrue(start.accepted(), "fluid race prospecting action should be accepted");
        });
        // Obsidian takes long enough that this changes the world after path
        // planning but before the progressive breaker can commit.
        helper.runAfterDelay(30, () -> helper.setBlock(fluid, Blocks.WATER));
        helper.succeedWhen(() -> {
            JsonObject status = MaidActionStore.getInstance().getStatus(actionId).orElseThrow();
            helper.assertTrue("FAILED".equals(status.get("status").getAsString()),
                    "new adjacent fluid should abort the prospect step, current=" + status);
            helper.assertTrue(helper.getBlockState(obstacle).is(Blocks.OBSIDIAN),
                    "unsafe obstacle must remain intact after fluid appears");
        });
    }

    @GameTest(template = "maid_agent_test", timeoutTicks = 300)
    public static void legacyExcavationBudgetDoesNotStopProspecting(GameTestHelper helper) {
        prepareFloorArea(helper, 0, 4, 0, 4, Blocks.STONE);
        BlockPos feet = new BlockPos(2, 1, 2);
        BlockPos head = feet.above();
        BlockPos ore = new BlockPos(3, 1, 2);
        helper.setBlock(feet, Blocks.STONE);
        helper.setBlock(head, Blocks.STONE);
        helper.setBlock(ore, Blocks.COAL_ORE);
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(1, 1, 2));
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        UUID actionId = UUID.randomUUID();
        JsonObject args = selectorHarvestArgs("block", "minecraft:coal_ore", 1);
        args.add("mining_plan", miningPlan(
                "forward_tunnel", "east", 1, 0, 1));

        helper.runAfterDelay(5, () -> {
            MaidActionStore.StartResult start = MaidActionStore.getInstance().start(
                    actionId, maid, MaidActionKind.HARVEST_BLOCKS, args, 10_000L, true);
            helper.assertTrue(start.accepted(), "legacy budget action should be accepted");
        });
        helper.succeedWhen(() -> {
            JsonObject status = MaidActionStore.getInstance().getStatus(actionId).orElseThrow();
            helper.assertTrue("SUCCEEDED".equals(status.get("status").getAsString()),
                    "legacy one-block budget must not stop a two-block tunnel step, current=" + status);
            helper.assertTrue(helper.getBlockState(feet).isAir(),
                    "feet clearance should be excavated without a total block cap");
            helper.assertTrue(helper.getBlockState(head).isAir(),
                    "head clearance should be excavated without a total block cap");
            helper.assertTrue(helper.getBlockState(ore).isAir(),
                    "target beyond the former excavation budget should be harvested");
            JsonObject result = status.getAsJsonObject("result");
            helper.assertTrue(result.get("prospect_excavation_budget").getAsInt() == -1,
                    "terminal diagnostics should report unbounded excavation");
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
                    actionId, maid, MaidActionKind.HARVEST_BLOCKS, args, 0L, true);
            helper.assertTrue(start.accepted(), "no-deadline harvest action should be accepted");
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

    private static JsonObject selectorHarvestArgs(String type, String id, int searchRadius) {
        JsonObject selector = new JsonObject();
        selector.addProperty("type", type);
        selector.addProperty("id", id);
        JsonObject args = new JsonObject();
        args.add("selector", selector);
        args.addProperty("search_radius", searchRadius);
        args.addProperty("max_blocks", 1);
        args.addProperty("tool_policy", "require_correct");
        args.addProperty("speed", 0.8D);
        return args;
    }

    private static JsonObject miningPlan(String mode, String direction,
                                         int distance, int depth, int budget) {
        return miningPlan(mode, direction, distance, depth, budget, 1);
    }

    private static JsonObject miningPlan(String mode, String direction,
                                         int distance, int depth, int budget,
                                         int maxSegments) {
        JsonObject plan = new JsonObject();
        plan.addProperty("mode", mode);
        plan.addProperty("direction", direction);
        plan.addProperty("max_distance", distance);
        plan.addProperty("max_depth", depth);
        plan.addProperty("excavation_budget", budget);
        plan.addProperty("max_segments", maxSegments);
        return plan;
    }
}
