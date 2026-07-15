package com.neko_tlm_bridge.tlm.agent.action;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.MaidAction;
import com.neko_tlm_bridge.tlm.agent.MaidActionContext;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.MaidActionResource;
import com.neko_tlm_bridge.tlm.agent.MaidActionTickResult;
import com.neko_tlm_bridge.tlm.agent.runtime.HandLease;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainPath;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainSearch;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainWorldEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Searches, approaches and harvests a bounded number of blocks. */
public final class HarvestBlocksAction implements MaidAction {
    private static final int MAX_SEARCH_CANDIDATES = 64;
    private static final int MAX_TERRAIN_GOALS = 384;
    private static final int PATH_SEARCH_BUDGET_PER_TICK = 256;
    private static final int MAX_PATH_SEARCH_EXPANSIONS = 12_000;
    private static final int MAX_TERRAIN_REPLANS = 3;
    private static final double MAX_BREAK_DISTANCE_SQUARED = 4.5D * 4.5D;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final int[] STAND_Y_OFFSETS = {0, 1, -1};

    private final BlockPos explicitTarget;
    private final Predicate<BlockState> selector;
    private final String selectorDescription;
    private final int searchRadius;
    private final int maxBlocks;
    private final ToolPolicy toolPolicy;
    private final double speed;

    private Stage stage = Stage.VALIDATING;
    private final List<BlockPos> candidates = new ArrayList<>();
    private final Set<BlockPos> rejectedCandidates = new HashSet<>();
    private BlockPos searchOrigin;
    private BlockPos currentTarget;
    private BlockPos currentStandPos;
    private BlockState expectedState;
    private MaidTerrainSearch terrainSearch;
    private MaidTerrainPath terrainPath;
    private MaidTerrainNavigator navigation;
    private Map<BlockPos, BlockPos> terrainGoalTargets = Map.of();
    private HandLease handLease;
    private double breakingProgress;
    private int harvested;
    private boolean started;
    private boolean searchPrepared;
    private int matchedBlocks;
    private int safeStandCandidates;
    private int pathAttempts;
    private int nullPaths;
    private int unreachablePaths;
    private int plannerExpandedNodes;
    private int terrainReplans;
    private int routeBlocksCleared;

    public HarvestBlocksAction(BlockPos explicitTarget, Predicate<BlockState> selector,
                               String selectorDescription, int searchRadius, int maxBlocks,
                               ToolPolicy toolPolicy, double speed) {
        if ((explicitTarget == null) == (selector == null)) {
            throw new IllegalArgumentException("Exactly one of explicitTarget and selector is required");
        }
        this.explicitTarget = explicitTarget == null ? null : explicitTarget.immutable();
        this.selector = selector;
        this.selectorDescription = Objects.requireNonNull(selectorDescription, "selectorDescription");
        this.searchRadius = Math.max(1, Math.min(12, searchRadius));
        this.maxBlocks = Math.max(1, Math.min(8, maxBlocks));
        this.toolPolicy = Objects.requireNonNull(toolPolicy, "toolPolicy");
        this.speed = Math.max(0.4D, Math.min(1.0D, speed));
    }

    public static HarvestBlocksAction fromArgs(JsonObject args) {
        Objects.requireNonNull(args, "args");
        boolean hasTarget = args.has("target_pos");
        boolean hasSelector = args.has("selector");
        if (hasTarget == hasSelector) {
            throw new IllegalArgumentException("Exactly one of target_pos and selector is required");
        }

        BlockPos targetPos = null;
        Predicate<BlockState> selectorPredicate = null;
        String description;
        if (hasTarget) {
            JsonObject target = requireObject(args, "target_pos");
            targetPos = new BlockPos(requireCoordinate(target, "x"), requireCoordinate(target, "y"),
                    requireCoordinate(target, "z"));
            description = "position:" + targetPos.toShortString();
        } else {
            JsonObject selectorJson = requireObject(args, "selector");
            String type = requireString(selectorJson, "type");
            ResourceLocation id = parseResourceLocation(requireString(selectorJson, "id"));
            if ("block".equals(type)) {
                selectorPredicate = state -> state.getBlock().builtInRegistryHolder().is(id);
                description = "block:" + id;
            } else if ("tag".equals(type)) {
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
                selectorPredicate = state -> state.is(tag);
                description = "tag:#" + id;
            } else {
                throw new IllegalArgumentException("selector.type must be block or tag");
            }
        }

        int radius = optionalInt(args, "search_radius", 12);
        int maxBlocks = optionalInt(args, "max_blocks", 1);
        double speed = optionalDouble(args, "speed", 0.7D);
        requireRange(radius, "search_radius", 1, 12);
        requireRange(maxBlocks, "max_blocks", 1, 8);
        requireRange(speed, "speed", 0.4D, 1.0D);
        ToolPolicy policy = ToolPolicy.fromWireName(optionalString(args, "tool_policy", "require_correct"));
        return new HarvestBlocksAction(targetPos, selectorPredicate, description, radius, maxBlocks, policy, speed);
    }

    @Override
    public MaidActionKind kind() {
        return MaidActionKind.HARVEST_BLOCKS;
    }

    @Override
    public Set<MaidActionResource> resources() {
        return Set.of(MaidActionResource.MOVE, MaidActionResource.HAND, MaidActionResource.BREAK);
    }

    @Override
    public void start(MaidActionContext context) {
        started = true;
        searchOrigin = context.maid().blockPosition().immutable();
        report(context, Stage.VALIDATING, 0.0D, null);
    }

    @Override
    public MaidActionTickResult tick(MaidActionContext context) {
        if (!started) {
            start(context);
        }

        return switch (stage) {
            case VALIDATING, SEARCHING -> search(context);
            case PATHFINDING -> advanceTerrainSearch(context);
            case SELECTING_TOOL -> selectTool(context);
            case APPROACHING -> approach(context);
            case BREAKING -> breakBlock(context);
            case VERIFYING -> verifyAndContinue(context);
        };
    }

    @Override
    public void stop(MaidActionContext context, ActionEndReason reason) {
        clearBreakingAnimation(context);
        if (navigation != null) {
            navigation.stop(context);
            navigation = null;
        }
    }

    public HandLease handLease() {
        return handLease;
    }

    private MaidActionTickResult search(MaidActionContext context) {
        report(context, Stage.SEARCHING, overallProgress(), null);
        if (!searchPrepared) {
            MaidActionTickResult preparationFailure = prepareSearch(context);
            if (preparationFailure != null) {
                return preparationFailure;
            }
        }

        if (candidates.isEmpty()) {
            if (harvested > 0) {
                return success(context);
            }
            return failure(matchedBlocks == 0 ? ActionEndReason.TARGET_CHANGED : ActionEndReason.PATH_NOT_FOUND,
                    matchedBlocks == 0 ? "no_matching_block_found" : "no_terrain_path_goal_found");
        }
        return beginTerrainSearch(context);
    }

    private MaidActionTickResult prepareSearch(MaidActionContext context) {
        searchPrepared = true;
        if (explicitTarget != null) {
            if (!context.level().hasChunkAt(explicitTarget)) {
                JsonObject result = positionDetail(explicitTarget);
                result.addProperty("message", "target_chunk_not_loaded");
                addSearchDiagnostics(result,
                        "For a nearby resource request, retry with a block/tag selector instead of this target_pos");
                return MaidActionTickResult.failed(ActionEndReason.VALIDATION_FAILED, result);
            }
            if (context.level().getBlockState(explicitTarget).isAir()) {
                return failure(ActionEndReason.TARGET_CHANGED, "target_is_air");
            }
            matchedBlocks = 1;
            if (hasSafeAdjacentStandPosition(context, explicitTarget)) {
                safeStandCandidates = 1;
            }
            candidates.add(explicitTarget);
        } else {
            BlockPos origin = searchOrigin;
            List<BlockPos> discovered = new ArrayList<>();
            Map<BlockPos, Integer> approachRanks = new HashMap<>();
            for (BlockPos mutablePos : BlockPos.betweenClosed(
                    origin.offset(-searchRadius, -searchRadius, -searchRadius),
                    origin.offset(searchRadius, searchRadius, searchRadius))) {
                // The vanilla iterator reuses a MutableBlockPos, so freeze it
                // before retaining it beyond this loop iteration.
                BlockPos pos = mutablePos.immutable();
                if (pos.distSqr(origin) > (double) searchRadius * searchRadius
                        || !context.level().hasChunkAt(pos)) {
                    continue;
                }
                BlockState state = context.level().getBlockState(pos);
                if (!selector.test(state)) {
                    continue;
                }
                matchedBlocks++;
                if (rejectedCandidates.contains(pos)
                        || pos.equals(context.maid().getOnPos())
                        || pos.equals(context.maid().blockPosition().below())) {
                    continue;
                }
                boolean exposed = hasSafeAdjacentStandPosition(context, pos);
                if (exposed) {
                    safeStandCandidates++;
                }
                discovered.add(pos);
                approachRanks.put(pos, sideApproachAvailable(context, pos) ? 0 : exposed ? 1 : 2);
            }
            discovered.sort(Comparator
                    .comparingInt((BlockPos pos) -> approachRanks.getOrDefault(pos, 2))
                    .thenComparingDouble(pos -> miningSelectionScore(origin, pos)));
            candidates.addAll(discovered.subList(0, Math.min(MAX_SEARCH_CANDIDATES, discovered.size())));
        }
        return null;
    }

    private MaidActionTickResult beginTerrainSearch(MaidActionContext context) {
        BlockPos start = context.maid().blockPosition().immutable();
        BlockState planningState = candidates.stream()
                .map(context.level()::getBlockState)
                .filter(state -> !state.isAir() && matchesTarget(state))
                .findFirst()
                .orElse(null);
        if (planningState == null) {
            return failure(ActionEndReason.TARGET_CHANGED, "all_targets_changed_before_planning");
        }
        MaidActionTickResult toolFailure = ensureHeldTool(context, planningState);
        if (toolFailure != null) {
            return toolFailure;
        }
        MaidTerrainWorldEvaluator evaluator = new MaidTerrainWorldEvaluator(
                context.level(), context.maid(), searchOrigin,
                searchRadius + 2, searchRadius + 2,
                toolPolicy == ToolPolicy.REQUIRE_CORRECT);
        LinkedHashMap<BlockPos, BlockPos> goals = new LinkedHashMap<>();

        for (BlockPos candidate : candidates) {
            BlockState candidateState = context.level().getBlockState(candidate);
            if (candidateState.isAir() || !matchesTarget(candidateState)
                    || rejectedCandidates.contains(candidate)) {
                continue;
            }
            ItemStack held = context.maid().getMainHandItem();
            if (toolPolicy == ToolPolicy.REQUIRE_CORRECT
                    && candidateState.requiresCorrectToolForDrops()
                    && !held.isCorrectToolForDrops(candidateState)) {
                continue;
            }
            if (canReachVisibleFace(context, candidate)
                    && !candidate.equals(context.maid().getOnPos())
                    && !candidate.equals(context.maid().blockPosition().below())) {
                currentTarget = candidate;
                currentStandPos = start;
                expectedState = candidateState;
                terrainPath = new MaidTerrainPath(List.of(), start, 0.0D, 0);
                report(context, Stage.SELECTING_TOOL, overallProgress(), currentTarget);
                return MaidActionTickResult.running();
            }

            for (BlockPos standPos : standPositionCandidates(candidate)) {
                if (goals.size() >= MAX_TERRAIN_GOALS) {
                    break;
                }
                if (!potentialMiningStance(evaluator, standPos, candidate)) {
                    continue;
                }
                goals.putIfAbsent(standPos.immutable(), candidate.immutable());
            }
        }

        if (goals.isEmpty()) {
            return failure(ActionEndReason.PATH_NOT_FOUND, "no_clearable_mining_stance_found");
        }
        terrainGoalTargets = Map.copyOf(goals);
        pathAttempts++;
        terrainSearch = new MaidTerrainSearch(start, goals.keySet(), evaluator, MAX_PATH_SEARCH_EXPANSIONS);
        report(context, Stage.PATHFINDING, overallProgress(), null);
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult advanceTerrainSearch(MaidActionContext context) {
        if (terrainSearch == null) {
            return failure(ActionEndReason.INTERNAL_ERROR, "terrain_search_missing");
        }
        MaidTerrainSearch.Status status = terrainSearch.advance(PATH_SEARCH_BUDGET_PER_TICK);
        plannerExpandedNodes = Math.max(plannerExpandedNodes, terrainSearch.expandedNodes());
        JsonObject detail = new JsonObject();
        detail.addProperty("planner", "maid_weighted_astar");
        detail.addProperty("nodes_expanded", terrainSearch.expandedNodes());
        detail.addProperty("goal_count", terrainGoalTargets.size());
        detail.addProperty("replans", terrainReplans);
        context.execution().reportProgress(Stage.PATHFINDING.wireName, overallProgress(), detail);
        if (status == MaidTerrainSearch.Status.SEARCHING) {
            return MaidActionTickResult.running();
        }
        if (status == MaidTerrainSearch.Status.FAILED) {
            unreachablePaths++;
            return failure(ActionEndReason.PATH_NOT_FOUND, "terrain_search_exhausted");
        }

        terrainPath = terrainSearch.result().orElse(null);
        if (terrainPath == null) {
            return failure(ActionEndReason.INTERNAL_ERROR, "terrain_search_found_without_path");
        }
        currentStandPos = terrainPath.target();
        currentTarget = terrainGoalTargets.get(currentStandPos);
        if (currentTarget == null) {
            return failure(ActionEndReason.INTERNAL_ERROR, "terrain_goal_lost_target_mapping");
        }
        expectedState = context.level().getBlockState(currentTarget);
        if (expectedState.isAir() || !matchesTarget(expectedState)) {
            return restartTerrainSearch(context, ActionEndReason.TARGET_CHANGED,
                    "target_changed_after_path_search");
        }
        terrainSearch = null;
        report(context, Stage.SELECTING_TOOL, overallProgress(), currentTarget);
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult selectTool(MaidActionContext context) {
        if (!context.level().getBlockState(currentTarget).equals(expectedState)) {
            return onCandidateFailure(context, ActionEndReason.TARGET_CHANGED, "target_changed_before_tool_selection");
        }

        MaidActionTickResult toolFailure = ensureHeldTool(context, expectedState);
        if (toolFailure != null) {
            return toolFailure;
        }

        if (terrainPath == null) {
            return failure(ActionEndReason.INTERNAL_ERROR, "terrain_path_missing_before_execution");
        }
        navigation = new MaidTerrainNavigator(
                terrainPath, handLease, speed,
                toolPolicy == ToolPolicy.REQUIRE_CORRECT);
        navigation.start(context);
        report(context, Stage.APPROACHING, overallProgress(), currentTarget);
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult ensureHeldTool(MaidActionContext context, BlockState state) {
        if (handLease != null) {
            if (handLease.validate(context.maid()) != HandLease.LeaseHealth.HEALTHY) {
                return failure(ActionEndReason.HAND_CONFLICT, "held_tool_changed_during_action");
            }
            ItemStack held = context.maid().getMainHandItem();
            boolean correct = !state.requiresCorrectToolForDrops() || held.isCorrectToolForDrops(state);
            return toolPolicy == ToolPolicy.REQUIRE_CORRECT && !correct
                    ? failure(ActionEndReason.TOOL_NOT_FOUND, "equipped_tool_is_wrong_for_target")
                    : null;
        }

        ToolCandidate selected = findBestTool(context.maid(), state);
        if (selected == null) {
            return failure(ActionEndReason.TOOL_NOT_FOUND, "correct_tool_not_found");
        }
        try {
            handLease = selected.slot == HandLease.HELD_TOOL_SLOT
                    ? HandLease.heldTool(context.maid())
                    : HandLease.equipFromBackpack(context.maid(), selected.slot);
        } catch (RuntimeException exception) {
            return failure(ActionEndReason.HAND_CONFLICT, "tool_slot_changed");
        }
        boolean attached = MaidActionStore.getInstance().attachHandLease(
                context.execution().actionId(), context.execution().generation(), handLease);
        if (!attached) {
            handLease.release(context.maid());
            handLease = null;
            return failure(ActionEndReason.SUPERSEDED, "action_ended_before_tool_lease_attached");
        }
        return null;
    }

    private MaidActionTickResult approach(MaidActionContext context) {
        if (!context.level().getBlockState(currentTarget).equals(expectedState)) {
            return onCandidateFailure(context, ActionEndReason.TARGET_CHANGED, "target_changed_while_approaching");
        }
        if (handLease.validate(context.maid()) != HandLease.LeaseHealth.HEALTHY) {
            return failure(ActionEndReason.HAND_CONFLICT, "held_tool_changed_while_approaching");
        }

        MaidTerrainNavigator.TickResult result = navigation.tick(context);
        for (MaidTerrainNavigator.ClearedBlock cleared : navigation.drainClearedBlocks()) {
            routeBlocksCleared++;
            rejectedCandidates.add(cleared.pos());
            if (matchesClearedTarget(cleared)) {
                harvested++;
                if (harvested >= maxBlocks) {
                    navigation.stop(context);
                    navigation = null;
                    return success(context);
                }
            }
        }
        if (result.outcome() == MaidTerrainNavigator.Outcome.FAILED) {
            boolean retry = result.replanRecommended() && terrainReplans < MAX_TERRAIN_REPLANS;
            navigation.stop(context);
            navigation = null;
            if (retry) {
                terrainReplans++;
                return restartTerrainSearch(context, result.reason(), "terrain_execution_requires_replan");
            }
            return onCandidateFailure(context, result.reason(),
                    result.detail().has("message")
                            ? result.detail().get("message").getAsString()
                            : "terrain_path_execution_failed");
        }
        if (result.outcome() == MaidTerrainNavigator.Outcome.ARRIVED) {
            navigation = null;
            if (!canReachVisibleFace(context, currentTarget)) {
                return onCandidateFailure(context, ActionEndReason.PATH_NOT_FOUND,
                        "planned_mining_stance_has_no_visible_target_face");
            }
            breakingProgress = 0.0D;
            report(context, Stage.BREAKING, overallProgress(), currentTarget);
        } else {
            stage = Stage.APPROACHING;
            JsonObject detail = result.detail();
            detail.addProperty("harvested", harvested);
            detail.addProperty("max_blocks", maxBlocks);
            context.execution().reportProgress(Stage.APPROACHING.wireName, overallProgress(), detail);
        }
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult breakBlock(MaidActionContext context) {
        BlockState state = context.level().getBlockState(currentTarget);
        if (!state.equals(expectedState)) {
            return onCandidateFailure(context, ActionEndReason.TARGET_CHANGED, "target_changed_while_breaking");
        }
        if (handLease.validate(context.maid()) != HandLease.LeaseHealth.HEALTHY) {
            return failure(ActionEndReason.HAND_CONFLICT, "held_tool_changed_while_breaking");
        }
        if (!canReachVisibleFace(context, currentTarget)) {
            return onCandidateFailure(context, ActionEndReason.PATH_NOT_FOUND,
                    "target_face_is_not_visible_or_in_reach");
        }

        float hardness = state.getDestroySpeed(context.level(), currentTarget);
        if (hardness < 0.0F) {
            return failure(ActionEndReason.BLOCK_PROTECTED, "block_is_unbreakable");
        }
        ItemStack tool = context.maid().getMainHandItem();
        boolean correctForDrops = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        if (toolPolicy == ToolPolicy.REQUIRE_CORRECT && !correctForDrops) {
            return failure(ActionEndReason.TOOL_NOT_FOUND, "tool_broke_or_became_invalid_before_target");
        }
        float toolSpeed = Math.max(1.0F, tool.getDestroySpeed(state));
        double increment = hardness == 0.0F ? 1.0D
                : toolSpeed / hardness / (correctForDrops ? 30.0D : 100.0D);
        breakingProgress = Math.min(1.0D, breakingProgress + increment);

        context.maid().getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(currentTarget));
        context.level().destroyBlockProgress(context.maid().getId(), currentTarget,
                Math.min(9, (int) Math.floor(breakingProgress * 10.0D)));
        JsonObject detail = positionDetail(currentTarget);
        detail.addProperty("block_progress", breakingProgress);
        context.execution().reportProgress(Stage.BREAKING.wireName,
                Math.min(0.99D, (harvested + breakingProgress) / maxBlocks), detail);

        if (breakingProgress < 1.0D) {
            return MaidActionTickResult.running();
        }

        MaidBlockBreaker.BreakResult breakResult = MaidBlockBreaker.breakWithHeldTool(
                context.maid(), currentTarget, expectedState, handLease);
        clearBreakingAnimation(context);
        return switch (breakResult) {
            case SUCCESS -> {
                stage = Stage.VERIFYING;
                yield MaidActionTickResult.running();
            }
            case TARGET_CHANGED -> onCandidateFailure(context, ActionEndReason.TARGET_CHANGED,
                    "target_changed_before_commit");
            case BLOCK_PROTECTED -> failure(ActionEndReason.BLOCK_PROTECTED, "block_break_was_rejected");
            case HAND_CONFLICT -> failure(ActionEndReason.HAND_CONFLICT, "held_tool_changed_before_commit");
        };
    }

    private MaidActionTickResult verifyAndContinue(MaidActionContext context) {
        if (context.level().getBlockState(currentTarget).equals(expectedState)) {
            return failure(ActionEndReason.INTERNAL_ERROR, "block_remained_after_successful_break");
        }
        harvested++;
        rejectedCandidates.add(currentTarget.immutable());
        currentTarget = null;
        currentStandPos = null;
        expectedState = null;
        resetTerrainPlan();
        if (harvested >= maxBlocks || explicitTarget != null) {
            return success(context);
        }
        searchOrigin = context.maid().blockPosition().immutable();
        candidates.clear();
        searchPrepared = false;
        stage = Stage.SEARCHING;
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult onCandidateFailure(MaidActionContext context, ActionEndReason reason, String message) {
        clearBreakingAnimation(context);
        if (navigation != null) {
            navigation.stop(context);
            navigation = null;
        }
        if (currentTarget != null) {
            rejectedCandidates.add(currentTarget.immutable());
        }
        resetTerrainPlan();
        currentTarget = null;
        currentStandPos = null;
        expectedState = null;
        if (explicitTarget == null && harvested > 0) {
            return success(context);
        }
        if (explicitTarget != null || rejectedCandidates.size() >= MAX_SEARCH_CANDIDATES) {
            return failure(reason, message);
        }
        searchOrigin = context.maid().blockPosition().immutable();
        candidates.clear();
        searchPrepared = false;
        stage = Stage.SEARCHING;
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult restartTerrainSearch(MaidActionContext context,
                                                       ActionEndReason reason, String message) {
        clearBreakingAnimation(context);
        if (navigation != null) {
            navigation.stop(context);
            navigation = null;
        }
        if (reason == ActionEndReason.TARGET_CHANGED && currentTarget != null) {
            rejectedCandidates.add(currentTarget.immutable());
        }
        resetTerrainPlan();
        currentTarget = null;
        currentStandPos = null;
        expectedState = null;
        if (terrainReplans > MAX_TERRAIN_REPLANS) {
            return failure(reason, message);
        }
        searchOrigin = context.maid().blockPosition().immutable();
        candidates.clear();
        searchPrepared = false;
        stage = Stage.SEARCHING;
        return MaidActionTickResult.running();
    }

    private void resetTerrainPlan() {
        terrainSearch = null;
        terrainPath = null;
        terrainGoalTargets = Map.of();
    }

    private ToolCandidate findBestTool(EntityMaid maid, BlockState state) {
        ToolCandidate best = candidateFor(HandLease.HELD_TOOL_SLOT, maid.getMainHandItem(), state);
        IItemHandler inventory = maid.getAvailableBackpackInv();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ToolCandidate candidate = candidateFor(slot, inventory.getStackInSlot(slot), state);
            if (candidate != null && (best == null || candidate.score > best.score)) {
                best = candidate;
            }
        }
        return best;
    }

    private ToolCandidate candidateFor(int slot, ItemStack stack, BlockState state) {
        boolean correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        if (toolPolicy == ToolPolicy.REQUIRE_CORRECT && !correct) {
            return null;
        }
        double score = stack.getDestroySpeed(state) + (correct ? 10_000.0D : 0.0D);
        return new ToolCandidate(slot, score);
    }

    private boolean matchesTarget(BlockState state) {
        if (explicitTarget != null) {
            return !state.isAir();
        }
        return selector.test(state);
    }

    private boolean matchesClearedTarget(MaidTerrainNavigator.ClearedBlock cleared) {
        return explicitTarget != null
                ? cleared.pos().equals(explicitTarget)
                : selector.test(cleared.state());
    }

    private MaidActionTickResult success(MaidActionContext context) {
        report(context, Stage.VERIFYING, 1.0D, null);
        JsonObject result = new JsonObject();
        result.addProperty("harvested", harvested);
        addSearchDiagnostics(result, "none");
        return MaidActionTickResult.succeeded(result);
    }

    private void clearBreakingAnimation(MaidActionContext context) {
        if (currentTarget != null) {
            context.level().destroyBlockProgress(context.maid().getId(), currentTarget, -1);
        }
    }

    private void report(MaidActionContext context, Stage nextStage, double progress, BlockPos pos) {
        stage = nextStage;
        JsonObject detail = pos == null ? new JsonObject() : positionDetail(pos);
        detail.addProperty("harvested", harvested);
        detail.addProperty("max_blocks", maxBlocks);
        detail.addProperty("selector", selectorDescription);
        context.execution().reportProgress(nextStage.wireName, progress, detail);
    }

    private double overallProgress() {
        return Math.min(0.99D, (double) harvested / maxBlocks);
    }

    private MaidActionTickResult failure(ActionEndReason reason, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("message", message);
        addSearchDiagnostics(result, retryHint(reason));
        return MaidActionTickResult.failed(reason, result);
    }

    private void addSearchDiagnostics(JsonObject result, String retryHint) {
        result.addProperty("matched_blocks", matchedBlocks);
        result.addProperty("safe_stand_candidates", safeStandCandidates);
        result.addProperty("path_attempts", pathAttempts);
        result.addProperty("null_paths", nullPaths);
        result.addProperty("unreachable_paths", unreachablePaths);
        result.addProperty("planner", "maid_weighted_astar");
        result.addProperty("planner_expanded_nodes", plannerExpandedNodes);
        result.addProperty("terrain_replans", terrainReplans);
        result.addProperty("route_blocks_cleared", routeBlocksCleared);
        result.addProperty("search_radius", searchRadius);
        result.addProperty("selector", selectorDescription);
        result.addProperty("retry_hint", retryHint);
    }

    private static String retryHint(ActionEndReason reason) {
        return switch (reason) {
            case PATH_NOT_FOUND, STUCK ->
                    "Move the maid closer, provide the required tools, or increase search_radius within loaded chunks";
            case TOOL_NOT_FOUND -> "Provide a correct harvesting tool or use tool_policy=allow_wrong";
            case TARGET_CHANGED -> "Refresh the target or retry with a broader block/tag selector";
            case VALIDATION_FAILED -> "Check the target coordinates and loaded area before retrying";
            default -> "Refresh world state and retry the action";
        };
    }

    private static JsonObject positionDetail(BlockPos pos) {
        JsonObject detail = new JsonObject();
        detail.addProperty("x", pos.getX());
        detail.addProperty("y", pos.getY());
        detail.addProperty("z", pos.getZ());
        return detail;
    }

    private static JsonObject requireObject(JsonObject parent, String name) {
        if (!parent.has(name) || !parent.get(name).isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return parent.getAsJsonObject(name);
    }

    private static String requireString(JsonObject parent, String name) {
        if (!parent.has(name) || !parent.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return parent.get(name).getAsString();
    }

    private static String optionalString(JsonObject parent, String name, String fallback) {
        return parent.has(name) ? requireString(parent, name) : fallback;
    }

    private static int requireInt(JsonObject parent, String name) {
        if (!parent.has(name) || !parent.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return parent.get(name).getAsInt();
    }

    private static int optionalInt(JsonObject parent, String name, int fallback) {
        return parent.has(name) ? requireInt(parent, name) : fallback;
    }

    private static double miningSelectionScore(BlockPos origin, BlockPos target) {
        double distance = Math.sqrt(origin.distSqr(target));
        double depthPenalty = Math.max(0, origin.getY() - target.getY()) * 3.0D;
        return distance + depthPenalty;
    }

    private static boolean potentialMiningStance(MaidTerrainWorldEvaluator evaluator,
                                                  BlockPos standPos, BlockPos target) {
        if (!evaluator.withinBounds(standPos)
                || !evaluator.isLoaded(standPos)
                || !evaluator.isLoaded(standPos.above())
                || !evaluator.canStandOn(standPos.below())) {
            return false;
        }
        double feetCost = evaluator.clearCost(standPos);
        double headCost = evaluator.clearCost(standPos.above());
        if (!Double.isFinite(feetCost) || !Double.isFinite(headCost)) {
            return false;
        }
        int verticalOffset = standPos.getY() - target.getY();
        if (verticalOffset < -1 || verticalOffset > 1) {
            return false;
        }
        // A stance one block above can only see the target's top face when the
        // cover is already open. Same-level/below stances expose a side face by
        // clearing their own two body cells as part of the route.
        return verticalOffset != 1 || evaluator.clearCost(target.above()) == 0.0D;
    }

    private static boolean hasSafeAdjacentStandPosition(MaidActionContext context, BlockPos target) {
        if (target.equals(context.maid().getOnPos())
                || target.equals(context.maid().blockPosition().below())) {
            return false;
        }
        for (BlockPos standPos : standPositionCandidates(target)) {
            if (isSafeStandPosition(context, standPos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sideApproachAvailable(MaidActionContext context, BlockPos target) {
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            BlockPos adjacent = target.relative(direction);
            if (isSafeStandPosition(context, adjacent)
                    || isSafeStandPosition(context, adjacent.below())) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> standPositionCandidates(BlockPos target) {
        List<BlockPos> positions = new ArrayList<>(HORIZONTAL_DIRECTIONS.length * STAND_Y_OFFSETS.length);
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            BlockPos adjacent = target.relative(direction);
            for (int yOffset : STAND_Y_OFFSETS) {
                positions.add(adjacent.offset(0, yOffset, 0).immutable());
            }
        }
        return positions;
    }

    private static boolean isSafeStandPosition(MaidActionContext context, BlockPos pos) {
        BlockState feet = context.level().getBlockState(pos);
        BlockState head = context.level().getBlockState(pos.above());
        BlockPos belowPos = pos.below();
        BlockState below = context.level().getBlockState(belowPos);
        return feet.getCollisionShape(context.level(), pos).isEmpty()
                && head.getCollisionShape(context.level(), pos.above()).isEmpty()
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && below.isFaceSturdy(context.level(), belowPos, Direction.UP);
    }

    private static boolean canReachVisibleFace(MaidActionContext context, BlockPos target) {
        Vec3 eye = context.maid().getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);
        if (eye.distanceToSqr(center) > MAX_BREAK_DISTANCE_SQUARED) {
            return false;
        }
        BlockHitResult hit = context.level().clip(new ClipContext(
                eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context.maid()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    private static int requireCoordinate(JsonObject parent, String name) {
        int value = requireInt(parent, name);
        requireRange(value, name, -30_000_000, 30_000_000);
        return value;
    }

    private static void requireRange(double value, String name, double minimum, double maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static double optionalDouble(JsonObject parent, String name, double fallback) {
        if (!parent.has(name)) {
            return fallback;
        }
        double value = parent.get(name).getAsDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    private static ResourceLocation parseResourceLocation(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw new IllegalArgumentException("Invalid resource location: " + value);
        }
        return id;
    }

    private record ToolCandidate(int slot, double score) {
    }

    public enum ToolPolicy {
        REQUIRE_CORRECT,
        ALLOW_WRONG;

        static ToolPolicy fromWireName(String value) {
            return switch (value) {
                case "require_correct" -> REQUIRE_CORRECT;
                case "allow_wrong" -> ALLOW_WRONG;
                default -> throw new IllegalArgumentException(
                        "tool_policy must be require_correct or allow_wrong");
            };
        }
    }

    private enum Stage {
        VALIDATING("validating"),
        SEARCHING("searching"),
        SELECTING_TOOL("selecting_tool"),
        PATHFINDING("pathfinding"),
        APPROACHING("approaching"),
        BREAKING("breaking"),
        VERIFYING("verifying");

        private final String wireName;

        Stage(String wireName) {
            this.wireName = wireName;
        }
    }
}
