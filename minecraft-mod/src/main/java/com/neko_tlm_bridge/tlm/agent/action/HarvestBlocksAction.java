package com.neko_tlm_bridge.tlm.agent.action;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
import java.util.EnumSet;
import java.util.function.Predicate;

/** Searches, approaches and harvests a bounded number of blocks. */
public final class HarvestBlocksAction implements MaidAction {
    private static final int MAX_SEARCH_CANDIDATES = 64;
    private static final int MAX_VEIN_BLOCKS = 64;
    private static final int MAX_TERRAIN_GOALS = 384;
    private static final int PATH_SEARCH_BUDGET_PER_TICK = 256;
    private static final int MAX_PATH_SEARCH_EXPANSIONS = 12_000;
    private static final int MAX_PROSPECT_SEARCH_EXPANSIONS = 64;
    private static final int MAX_TERRAIN_REPLANS = 3;
    private static final double MAX_BREAK_DISTANCE_SQUARED = 4.5D * 4.5D;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final int[] STAND_Y_OFFSETS = {0, 1, -1};
    private static final Set<String> VANILLA_ORE_BLOCKS = Set.of(
            "coal_ore", "deepslate_coal_ore", "copper_ore", "deepslate_copper_ore",
            "iron_ore", "deepslate_iron_ore", "gold_ore", "deepslate_gold_ore",
            "redstone_ore", "deepslate_redstone_ore", "emerald_ore", "deepslate_emerald_ore",
            "lapis_ore", "deepslate_lapis_ore", "diamond_ore", "deepslate_diamond_ore",
            "nether_gold_ore", "nether_quartz_ore");
    private static final Set<String> VANILLA_ORE_TAGS = Set.of(
            "coal_ores", "copper_ores", "diamond_ores", "emerald_ores",
            "gold_ores", "iron_ores", "lapis_ores", "redstone_ores");
    private static final TagKey<Block> CONVENTIONAL_ORES = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores"));

    private final BlockPos explicitTarget;
    private final Predicate<BlockState> selector;
    private final String selectorDescription;
    private final int searchRadius;
    private final int maxBlocks;
    private final ToolPolicy toolPolicy;
    private final double speed;
    private final MiningPlan miningPlan;
    private final boolean veinMining;
    private final MaidVeinTracker veinTracker;
    private final EnumSet<Direction> prospectDirectionsTried =
            EnumSet.noneOf(Direction.class);
    private final List<Direction> prospectDirectionAttemptOrder = new ArrayList<>();
    private boolean prospectDirectionsExhausted;

    private Stage stage = Stage.VALIDATING;
    private final List<BlockPos> candidates = new ArrayList<>();
    private final Set<BlockPos> rejectedCandidates = new HashSet<>();
    private final Set<BlockPos> harvestedPositions = new HashSet<>();
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
    private long terrainReplans;
    private int consecutiveTerrainReplans;
    private long routeBlocksCleared;
    private PlanningPurpose planningPurpose = PlanningPurpose.HARVEST;
    private Direction prospectDirection;
    private BlockPos prospectDirectionOrigin;
    private BlockPos prospectGoal;
    private MiningPlan.StepMode prospectStepMode;
    private long prospectSteps;
    private long prospectDescentSteps;
    private long segmentIndex;
    private int segmentSteps;
    private int segmentDescentSteps;
    private boolean prospectDescentBlocked;
    private boolean prospectWorldBottomReached;
    private long prospectBlocksCleared;
    private long prospectRescans;
    private String lastProspectFallbackReason;
    private String lastProspectFallbackDirection;
    private long prospectFallbacks;
    private BlockPos veinSeed;
    private String veinStopReason;
    private JsonObject lastNavigationFailureDetail;

    public HarvestBlocksAction(BlockPos explicitTarget, Predicate<BlockState> selector,
                               String selectorDescription, int searchRadius, int maxBlocks,
                               ToolPolicy toolPolicy, double speed) {
        this(explicitTarget, selector, selectorDescription, searchRadius, maxBlocks,
                toolPolicy, speed, MiningPlan.nearby(), false);
    }

    public HarvestBlocksAction(BlockPos explicitTarget, Predicate<BlockState> selector,
                               String selectorDescription, int searchRadius, int maxBlocks,
                               ToolPolicy toolPolicy, double speed, MiningPlan miningPlan) {
        this(explicitTarget, selector, selectorDescription, searchRadius, maxBlocks,
                toolPolicy, speed, miningPlan, false);
    }

    public HarvestBlocksAction(BlockPos explicitTarget, Predicate<BlockState> selector,
                               String selectorDescription, int searchRadius, int maxBlocks,
                               ToolPolicy toolPolicy, double speed, MiningPlan miningPlan,
                               boolean veinMining) {
        if ((explicitTarget == null) == (selector == null)) {
            throw new IllegalArgumentException("Exactly one of explicitTarget and selector is required");
        }
        this.explicitTarget = explicitTarget == null ? null : explicitTarget.immutable();
        this.selector = selector;
        this.selectorDescription = Objects.requireNonNull(selectorDescription, "selectorDescription");
        this.searchRadius = Math.max(1, Math.min(12, searchRadius));
        this.maxBlocks = Math.max(1, Math.min(
                veinMining ? MAX_VEIN_BLOCKS : 8, maxBlocks));
        this.toolPolicy = Objects.requireNonNull(toolPolicy, "toolPolicy");
        this.speed = Math.max(0.4D, Math.min(1.0D, speed));
        this.miningPlan = Objects.requireNonNull(miningPlan, "miningPlan");
        this.veinMining = veinMining;
        this.veinTracker = veinMining
                ? MaidVeinTracker.unbounded() : new MaidVeinTracker();
        if (veinMining && explicitTarget != null) {
            throw new IllegalArgumentException("vein_mining requires selector targeting");
        }
        if (explicitTarget != null && miningPlan.enabled()) {
            throw new IllegalArgumentException("mining_plan exploration requires selector targeting");
        }
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
        boolean oreSelectorHint = false;
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
            oreSelectorHint = looksLikeOreSelector(type, id);
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

        boolean oreSelector = hasSelector
                && (oreSelectorHint || isPureOreSelector(selectorPredicate));
        boolean veinMining = optionalBoolean(args, "vein_mining", oreSelector);
        if (veinMining && !hasSelector) {
            throw new IllegalArgumentException("vein_mining requires selector targeting");
        }
        int radius = optionalInt(args, "search_radius", 12);
        int maxBlocks = optionalInt(args, "max_blocks", 1);
        double speed = optionalDouble(args, "speed", 0.7D);
        requireRange(radius, "search_radius", 1, 12);
        requireRange(maxBlocks, "max_blocks", 1, veinMining ? MAX_VEIN_BLOCKS : 8);
        requireRange(speed, "speed", 0.4D, 1.0D);
        ToolPolicy policy = ToolPolicy.fromWireName(optionalString(args, "tool_policy", "require_correct"));
        MiningPlan miningPlan = MiningPlan.fromArgs(args, hasSelector, oreSelector);
        return new HarvestBlocksAction(targetPos, selectorPredicate, description,
                radius, maxBlocks, policy, speed, miningPlan, veinMining);
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
        prospectDirection = miningPlan.resolveDirection(context.maid().getDirection());
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
            case PROSPECTING -> prospect(context);
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
            if (veinMining && veinTracker.locked()) {
                int remaining = liveVeinRemaining(context);
                int rejected = veinRejectedCount(context);
                if ((remaining > 0 || rejected > 0) && veinStopReason == null) {
                    veinStopReason = "remaining_vein_targets_rejected_or_disconnected";
                }
                if (remaining > 0 || rejected > 0) {
                    return failure(ActionEndReason.PATH_NOT_FOUND,
                            "committed_vein_remaining_unreachable");
                }
                veinStopReason = null;
                return success(context);
            }
            if (miningPlan.hasNextStep(prospectSteps, prospectDescentSteps,
                    segmentSteps, segmentDescentSteps)) {
                return beginProspecting(context);
            }
            if (miningPlan.canAdvanceSegment(
                    segmentIndex, prospectSteps, prospectDescentSteps)) {
                return advanceProspectingSegment(context);
            }
            if (harvested > 0) {
                return success(context);
            }
            String message = miningPlan.enabled()
                    ? "prospecting_budget_exhausted_without_match"
                    : matchedBlocks == 0 ? "no_matching_block_found" : "no_terrain_path_goal_found";
            ActionEndReason exhaustionReason = miningPlan.enabled()
                    ? ActionEndReason.PATH_NOT_FOUND
                    : matchedBlocks == 0
                    ? ActionEndReason.TARGET_CHANGED : ActionEndReason.PATH_NOT_FOUND;
            return failure(exhaustionReason, message);
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
            int discoveryRadius = veinMining && veinTracker.locked()
                    ? Math.max(2, searchRadius) : searchRadius;
            List<BlockPos> discovered = new ArrayList<>();
            Map<BlockPos, Integer> approachRanks = new HashMap<>();
            for (BlockPos mutablePos : BlockPos.betweenClosed(
                    origin.offset(-discoveryRadius, -discoveryRadius, -discoveryRadius),
                    origin.offset(discoveryRadius, discoveryRadius, discoveryRadius))) {
                // The vanilla iterator reuses a MutableBlockPos, so freeze it
                // before retaining it beyond this loop iteration.
                BlockPos pos = mutablePos.immutable();
                if (pos.distSqr(origin) > (double) discoveryRadius * discoveryRadius
                        || !context.level().hasChunkAt(pos)) {
                    continue;
                }
                BlockState state = context.level().getBlockState(pos);
                if (!selector.test(state)) {
                    continue;
                }
                matchedBlocks++;
                boolean excluded = rejectedCandidates.contains(pos)
                        || pos.equals(context.maid().getOnPos())
                        || pos.equals(context.maid().blockPosition().below());
                if (excluded && !(veinMining && veinTracker.locked())) {
                    continue;
                }
                boolean exposed = hasSafeAdjacentStandPosition(context, pos);
                if (exposed) {
                    safeStandCandidates++;
                }
                discovered.add(pos);
                approachRanks.put(pos, sideApproachAvailable(context, pos) ? 0 : exposed ? 1 : 2);
            }
            if (veinMining && veinTracker.locked()) {
                veinTracker.pruneUnharvested(pos -> !context.level().hasChunkAt(pos)
                        || selector.test(context.level().getBlockState(pos)));
                for (BlockPos member : veinTracker.members()) {
                    if (!context.level().hasChunkAt(member)
                            || !selector.test(context.level().getBlockState(member))
                            || discovered.contains(member)) {
                        continue;
                    }
                    discovered.add(member);
                    boolean exposed = hasSafeAdjacentStandPosition(context, member);
                    approachRanks.put(member,
                            sideApproachAvailable(context, member) ? 0 : exposed ? 1 : 2);
                }
            }
            Comparator<BlockPos> priority = Comparator
                    .comparingInt((BlockPos pos) -> approachRanks.getOrDefault(pos, 2))
                    .thenComparingDouble(pos -> miningSelectionScore(origin, pos));
            discovered.sort(priority);
            if (veinMining && veinTracker.locked()) {
                discovered = veinTracker.retainConnected(discovered, priority);
                discovered = discovered.stream()
                        .filter(pos -> !rejectedCandidates.contains(pos))
                        .filter(pos -> !pos.equals(context.maid().getOnPos()))
                        .filter(pos -> !pos.equals(context.maid().blockPosition().below()))
                        .toList();
            }
            candidates.addAll(discovered.subList(0, Math.min(MAX_SEARCH_CANDIDATES, discovered.size())));
        }
        return null;
    }

    private MaidActionTickResult beginProspecting(MaidActionContext context) {
        if (!miningPlan.hasNextStep(prospectSteps, prospectDescentSteps,
                segmentSteps, segmentDescentSteps)) {
            return harvested > 0
                    ? success(context)
                    : failure(ActionEndReason.PATH_NOT_FOUND,
                    "prospecting_distance_or_depth_budget_exhausted");
        }
        // A prospecting route has no known ore state yet. Lease the best real
        // tool for ordinary stone so the terrain evaluator cannot promise a
        // tunnel that the held item is unable to clear safely.
        MaidActionTickResult toolFailure = ensureHeldTool(
                context, Blocks.STONE.defaultBlockState());
        if (toolFailure != null) {
            return toolFailure;
        }

        BlockPos start = context.maid().blockPosition().immutable();
        alignProspectDirectionSweep(start);
        prospectStepMode = miningPlan.mode() == MiningPlan.Mode.AUTO
                && prospectDescentBlocked
                ? MiningPlan.StepMode.FORWARD
                : miningPlan.nextStepMode(segmentDescentSteps);
        BlockPos forward = start.relative(prospectDirection);
        prospectGoal = (prospectStepMode == MiningPlan.StepMode.DESCEND
                ? forward.below() : forward).immutable();
        if (prospectStepMode == MiningPlan.StepMode.DESCEND
                && prospectGoal.getY() <= context.level().getMinBuildHeight()) {
            if (miningPlan.mode() == MiningPlan.Mode.AUTO) {
                prospectWorldBottomReached = true;
                prospectDescentBlocked = true;
                recordProspectFallback("world_bottom_reached", false);
                prospectStepMode = MiningPlan.StepMode.FORWARD;
                prospectGoal = forward.immutable();
            } else {
                return harvested > 0
                        ? success(context)
                        : failure(ActionEndReason.PATH_NOT_FOUND,
                        "world_bottom_reached");
            }
        }
        MaidTerrainWorldEvaluator evaluator = new MaidTerrainWorldEvaluator(
                context.level(), context.maid(), start, 4, 4,
                toolPolicy == ToolPolicy.REQUIRE_CORRECT,
                pos -> isRouteClearanceAllowed(context, pos));
        Set<com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep.Kind> allowedKinds =
                prospectStepMode == MiningPlan.StepMode.DESCEND
                        ? EnumSet.of(com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep.Kind.DESCEND)
                        : EnumSet.of(com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep.Kind.TRAVERSE);

        planningPurpose = PlanningPurpose.PROSPECT;
        terrainGoalTargets = Map.of();
        pathAttempts++;
        terrainSearch = new MaidTerrainSearch(start, Set.of(prospectGoal), evaluator,
                MAX_PROSPECT_SEARCH_EXPANSIONS, allowedKinds);
        report(context, Stage.PATHFINDING, overallProgress(), prospectGoal);
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult beginTerrainSearch(MaidActionContext context) {
        clearProspectDirectionSweep();
        BlockPos start = context.maid().blockPosition().immutable();
        BlockState planningState = candidates.stream()
                .filter(pos -> isEligibleTarget(pos, context.level().getBlockState(pos)))
                .map(context.level()::getBlockState)
                .findFirst()
                .orElse(null);
        if (planningState == null) {
            MaidActionTickResult partial = finishLockedVeinPartial(
                    context, "all_known_vein_targets_changed_before_planning");
            if (partial != null) {
                return partial;
            }
            return failure(ActionEndReason.TARGET_CHANGED, "all_targets_changed_before_planning");
        }
        MaidActionTickResult toolFailure = ensureHeldTool(context, planningState);
        if (toolFailure != null) {
            return toolFailure;
        }
        MaidTerrainWorldEvaluator evaluator = new MaidTerrainWorldEvaluator(
                context.level(), context.maid(), searchOrigin,
                planningHorizontalRadius(start), planningVerticalRadius(start),
                toolPolicy == ToolPolicy.REQUIRE_CORRECT,
                pos -> isRouteClearanceAllowed(context, pos));
        LinkedHashMap<BlockPos, BlockPos> goals = new LinkedHashMap<>();

        for (BlockPos candidate : candidates) {
            BlockState candidateState = context.level().getBlockState(candidate);
            if (!isEligibleTarget(candidate, candidateState)
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
            MaidActionTickResult partial = finishLockedVeinPartial(
                    context, "remaining_vein_has_no_clearable_mining_stance");
            if (partial != null) {
                return partial;
            }
            return failure(ActionEndReason.PATH_NOT_FOUND, "no_clearable_mining_stance_found");
        }
        terrainGoalTargets = Map.copyOf(goals);
        planningPurpose = PlanningPurpose.HARVEST;
        pathAttempts++;
        Set<com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep.Kind> allowedKinds =
                miningPlan.enabled()
                        ? EnumSet.of(
                        com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep.Kind.TRAVERSE,
                        com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep.Kind.ASCEND,
                        com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep.Kind.DESCEND)
                        : EnumSet.allOf(
                        com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep.Kind.class);
        terrainSearch = new MaidTerrainSearch(start, goals.keySet(), evaluator,
                MAX_PATH_SEARCH_EXPANSIONS, allowedKinds);
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
        detail.addProperty("planning_purpose", planningPurpose.wireName);
        context.execution().reportProgress(Stage.PATHFINDING.wireName, overallProgress(), detail);
        if (status == MaidTerrainSearch.Status.SEARCHING) {
            return MaidActionTickResult.running();
        }
        if (status == MaidTerrainSearch.Status.FAILED) {
            unreachablePaths++;
            if (planningPurpose == PlanningPurpose.PROSPECT
                    && prospectStepMode == MiningPlan.StepMode.DESCEND
                    && miningPlan.mode() == MiningPlan.Mode.AUTO
                    && !prospectDescentBlocked) {
                // Bedrock or an unsafe descent can occur above the formal
                // build floor. AUTO keeps prospecting horizontally instead
                // of treating the failed downward branch as terminal.
                prospectDescentBlocked = true;
                recordProspectFallback("no_safe_descend_step_found", false);
                prospectGoal = null;
                prospectStepMode = null;
                resetTerrainPlan();
                searchOrigin = context.maid().blockPosition().immutable();
                candidates.clear();
                searchPrepared = false;
                stage = Stage.SEARCHING;
                return MaidActionTickResult.running();
            }
            if (planningPurpose == PlanningPurpose.PROSPECT
                    && prospectStepMode == MiningPlan.StepMode.FORWARD) {
                MaidActionTickResult alternate = tryAlternateAutoDirection(
                        context, "no_safe_forward_step_found");
                if (alternate != null) {
                    return alternate;
                }
            }
            MaidActionTickResult partial = finishLockedVeinPartial(
                    context, "remaining_vein_terrain_search_exhausted");
            if (partial != null) {
                return partial;
            }
            boolean exhaustedAutoDirections = planningPurpose == PlanningPurpose.PROSPECT
                    && prospectStepMode == MiningPlan.StepMode.FORWARD
                    && prospectDirectionsTried.size() == 4
                    && miningPlan.mode() == MiningPlan.Mode.AUTO
                    && miningPlan.heading() == MiningPlan.Heading.MAID_FACING;
            prospectDirectionsExhausted = exhaustedAutoDirections;
            return failure(ActionEndReason.PATH_NOT_FOUND,
                    planningPurpose == PlanningPurpose.PROSPECT
                            ? (exhaustedAutoDirections
                            ? "all_auto_prospect_directions_exhausted"
                            : "no_safe_prospecting_step_found")
                            : "terrain_search_exhausted");
        }

        terrainPath = terrainSearch.result().orElse(null);
        if (terrainPath == null) {
            return failure(ActionEndReason.INTERNAL_ERROR, "terrain_search_found_without_path");
        }
        if (planningPurpose == PlanningPurpose.PROSPECT) {
            if (!terrainPath.target().equals(prospectGoal)) {
                return failure(ActionEndReason.INTERNAL_ERROR,
                        "prospecting_search_returned_wrong_goal");
            }
            terrainSearch = null;
            navigation = new MaidTerrainNavigator(
                    terrainPath, handLease, speed,
                    toolPolicy == ToolPolicy.REQUIRE_CORRECT);
            navigation.start(context);
            report(context, Stage.PROSPECTING, overallProgress(), prospectGoal);
            return MaidActionTickResult.running();
        }
        currentStandPos = terrainPath.target();
        currentTarget = terrainGoalTargets.get(currentStandPos);
        if (currentTarget == null) {
            return failure(ActionEndReason.INTERNAL_ERROR, "terrain_goal_lost_target_mapping");
        }
        expectedState = context.level().getBlockState(currentTarget);
        if (!isEligibleTarget(currentTarget, expectedState)) {
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
            HarvestRecord record = recordHarvestedTarget(cleared.pos(), cleared.state());
            if (record.harvested()) {
                if (!veinMining && harvested >= maxBlocks) {
                    navigation.stop(context);
                    navigation = null;
                    return success(context);
                }
                if (record.newlyLocked()) {
                    return restartAfterVeinLock(context);
                }
            } else if (isProtectedForeignOre(cleared.pos(), cleared.state())) {
                return failure(ActionEndReason.INTERNAL_ERROR,
                        "planner_cleared_unrequested_or_foreign_ore");
            } else if (miningPlan.enabled()) {
                prospectBlocksCleared++;
            }
        }
        if (result.outcome() == MaidTerrainNavigator.Outcome.FAILED) {
            rememberNavigationFailure(result);
            boolean retry = result.replanRecommended()
                    && consecutiveTerrainReplans < MAX_TERRAIN_REPLANS;
            navigation.stop(context);
            navigation = null;
            if (retry) {
                terrainReplans++;
                consecutiveTerrainReplans++;
                return restartTerrainSearch(context, result.reason(), "terrain_execution_requires_replan");
            }
            return onCandidateFailure(context, result.reason(),
                    result.detail().has("message")
                            ? result.detail().get("message").getAsString()
                            : "terrain_path_execution_failed");
        }
        if (result.outcome() == MaidTerrainNavigator.Outcome.ARRIVED) {
            navigation = null;
            consecutiveTerrainReplans = 0;
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

    private MaidActionTickResult prospect(MaidActionContext context) {
        if (navigation == null || prospectGoal == null || prospectStepMode == null) {
            return failure(ActionEndReason.INTERNAL_ERROR,
                    "prospecting_navigation_state_missing");
        }
        if (handLease == null
                || handLease.validate(context.maid()) != HandLease.LeaseHealth.HEALTHY) {
            return failure(ActionEndReason.HAND_CONFLICT,
                    "held_tool_changed_while_prospecting");
        }

        MaidTerrainNavigator.TickResult result = navigation.tick(context);
        for (MaidTerrainNavigator.ClearedBlock cleared : navigation.drainClearedBlocks()) {
            routeBlocksCleared++;
            HarvestRecord record = recordHarvestedTarget(cleared.pos(), cleared.state());
            if (record.harvested()) {
                if (!veinMining && harvested >= maxBlocks) {
                    navigation.stop(context);
                    navigation = null;
                    return success(context);
                }
                if (record.newlyLocked()) {
                    return restartAfterVeinLock(context);
                }
            } else if (isProtectedForeignOre(cleared.pos(), cleared.state())) {
                return failure(ActionEndReason.INTERNAL_ERROR,
                        "prospecting_cleared_unrequested_or_foreign_ore");
            } else {
                prospectBlocksCleared++;
            }
        }

        if (result.outcome() == MaidTerrainNavigator.Outcome.FAILED) {
            rememberNavigationFailure(result);
            String message = result.detail().has("message")
                    ? result.detail().get("message").getAsString()
                    : "prospecting_step_failed";
            boolean retry = result.replanRecommended()
                    && consecutiveTerrainReplans < MAX_TERRAIN_REPLANS;
            navigation.stop(context);
            navigation = null;
            if (retry) {
                terrainReplans++;
                consecutiveTerrainReplans++;
                return restartProspectingFromActualPosition(context);
            }
            if (canFallbackFromAutoDescent(context, result, message)) {
                return fallbackFromAutoDescent(context, message);
            }
            if ("maid_is_no_longer_at_terrain_step_origin".equals(message)) {
                return failure(ActionEndReason.STUCK,
                        "terrain_origin_drift_replan_exhausted");
            }
            return failure(result.reason(), message);
        }
        if (result.outcome() == MaidTerrainNavigator.Outcome.ARRIVED) {
            navigation = null;
            consecutiveTerrainReplans = 0;
            prospectSteps++;
            segmentSteps++;
            if (prospectStepMode == MiningPlan.StepMode.DESCEND) {
                prospectDescentSteps++;
                segmentDescentSteps++;
            }
            prospectRescans++;
            clearProspectDirectionSweep();
            prospectGoal = null;
            prospectStepMode = null;
            planningPurpose = PlanningPurpose.HARVEST;
            resetTerrainPlan();
            searchOrigin = context.maid().blockPosition().immutable();
            candidates.clear();
            searchPrepared = false;
            stage = Stage.SEARCHING;
            return MaidActionTickResult.running();
        }

        stage = Stage.PROSPECTING;
        JsonObject detail = result.detail();
        addProspectingDiagnostics(detail);
        context.execution().reportProgress(
                Stage.PROSPECTING.wireName, overallProgress(), detail);
        return MaidActionTickResult.running();
    }

    private boolean canFallbackFromAutoDescent(
            MaidActionContext context,
            MaidTerrainNavigator.TickResult result,
            String message) {
        return miningPlan.mode() == MiningPlan.Mode.AUTO
                && miningPlan.heading() == MiningPlan.Heading.MAID_FACING
                && prospectStepMode == MiningPlan.StepMode.DESCEND
                && result.reason() == ActionEndReason.STUCK
                && "controlled_descend_made_no_progress".equals(message)
                && context.maid().onGround()
                && result.detail().has("actual_x")
                && result.detail().has("actual_y")
                && result.detail().has("actual_z")
                && result.detail().has("from_x")
                && result.detail().has("from_y")
                && result.detail().has("from_z")
                && result.detail().get("actual_x").getAsInt()
                == result.detail().get("from_x").getAsInt()
                && result.detail().get("actual_y").getAsInt()
                == result.detail().get("from_y").getAsInt()
                && result.detail().get("actual_z").getAsInt()
                == result.detail().get("from_z").getAsInt();
    }

    /**
     * AUTO may continue on a new horizontal branch only after bounded attempts
     * to execute a safe descent have failed. Rotate away from the cleared
     * staircase column: its support may already have been removed while the
     * terrain step was prepared, so walking straight ahead would be unsafe.
     */
    private MaidActionTickResult fallbackFromAutoDescent(
            MaidActionContext context, String message) {
        prospectDescentBlocked = true;
        MaidActionTickResult alternate = tryAlternateAutoDirection(context, message);
        return alternate != null
                ? alternate
                : failure(ActionEndReason.STUCK, message);
    }

    private MaidActionTickResult tryAlternateAutoDirection(
            MaidActionContext context, String reason) {
        if (miningPlan.mode() != MiningPlan.Mode.AUTO
                || miningPlan.heading() != MiningPlan.Heading.MAID_FACING) {
            return null;
        }
        BlockPos liveOrigin = context.maid().blockPosition().immutable();
        if (prospectDirectionOrigin == null
                || !liveOrigin.equals(prospectDirectionOrigin)
                || !isSafeProspectSweepAnchor(context, liveOrigin)) {
            return null;
        }
        if (prospectDirectionsTried.add(prospectDirection)) {
            prospectDirectionAttemptOrder.add(prospectDirection);
        }
        Direction previous = prospectDirection;
        Direction alternate = nextUntriedHorizontalDirection(
                prospectDirection, prospectDirectionsTried);
        if (alternate == null) {
            return null;
        }
        prospectDirection = alternate;
        prospectDescentBlocked = true;
        recordProspectFallback(reason, true, previous);
        consecutiveTerrainReplans = 0;
        return restartProspectingFromActualPosition(context);
    }

    private static boolean isSafeProspectSweepAnchor(
            MaidActionContext context, BlockPos origin) {
        BlockPos headPos = origin.above();
        BlockPos supportPos = origin.below();
        if (!context.maid().onGround()
                || !context.level().hasChunkAt(origin)
                || !context.level().hasChunkAt(headPos)
                || !context.level().hasChunkAt(supportPos)) {
            return false;
        }
        BlockState feet = context.level().getBlockState(origin);
        BlockState head = context.level().getBlockState(headPos);
        BlockState support = context.level().getBlockState(supportPos);
        Vec3 center = Vec3.atBottomCenterOf(origin);
        double dx = context.maid().getX() - center.x;
        double dz = context.maid().getZ() - center.z;
        return dx * dx + dz * dz <= 0.45D * 0.45D
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && feet.getCollisionShape(context.level(), origin).isEmpty()
                && head.getCollisionShape(context.level(), headPos).isEmpty()
                && MaidTerrainWorldEvaluator.isSafeStandSupport(
                context.level(), supportPos, support);
    }

    private void alignProspectDirectionSweep(BlockPos liveOrigin) {
        BlockPos immutable = liveOrigin.immutable();
        if (prospectDirectionOrigin == null || !prospectDirectionOrigin.equals(immutable)) {
            prospectDirectionOrigin = immutable;
            prospectDirectionsTried.clear();
            prospectDirectionAttemptOrder.clear();
            prospectDirectionsExhausted = false;
        }
        if (prospectDirectionsTried.add(prospectDirection)) {
            prospectDirectionAttemptOrder.add(prospectDirection);
        }
    }

    private void clearProspectDirectionSweep() {
        prospectDirectionOrigin = null;
        prospectDirectionsTried.clear();
        prospectDirectionAttemptOrder.clear();
        prospectDirectionsExhausted = false;
    }

    static Direction nextUntriedHorizontalDirection(
            Direction current, Set<Direction> tried) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(tried, "tried");
        if (!current.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("current direction must be horizontal");
        }
        Direction candidate = current;
        for (int index = 0; index < 3; index++) {
            candidate = candidate.getClockWise();
            if (!tried.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void recordProspectFallback(String reason, boolean directionChanged) {
        recordProspectFallback(reason, directionChanged, prospectDirection);
    }

    private void recordProspectFallback(
            String reason, boolean directionChanged, Direction previousDirection) {
        lastProspectFallbackReason = reason;
        Direction previous = previousDirection == null ? prospectDirection : previousDirection;
        Direction current = directionChanged ? prospectDirection : previous;
        lastProspectFallbackDirection = previous.getName() + "->" + current.getName();
        prospectFallbacks++;
    }

    /**
     * Rolls into another bounded prospecting segment from the maid's live
     * position. Action-wide counters, leases and timeout deliberately remain
     * untouched. Segment count is diagnostic only and never stops mining.
     */
    private MaidActionTickResult advanceProspectingSegment(MaidActionContext context) {
        if (!miningPlan.canAdvanceSegment(
                segmentIndex, prospectSteps, prospectDescentSteps)) {
            return harvested > 0
                    ? success(context)
                    : failure(ActionEndReason.PATH_NOT_FOUND,
                    "prospecting_segment_limit_exhausted");
        }
        if (navigation != null) {
            navigation.stop(context);
            navigation = null;
        }

        segmentIndex = segmentIndex == Long.MAX_VALUE ? Long.MAX_VALUE : segmentIndex + 1L;
        segmentSteps = 0;
        segmentDescentSteps = 0;
        prospectDescentBlocked = prospectWorldBottomReached;
        clearProspectDirectionSweep();
        prospectGoal = null;
        prospectStepMode = null;
        planningPurpose = PlanningPurpose.HARVEST;
        resetTerrainPlan();
        searchOrigin = context.maid().blockPosition().immutable();
        candidates.clear();
        searchPrepared = false;
        report(context, Stage.SEARCHING, overallProgress(), searchOrigin);
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult breakBlock(MaidActionContext context) {
        BlockState state = context.level().getBlockState(currentTarget);
        if (!state.equals(expectedState)) {
            return onCandidateFailure(context, ActionEndReason.TARGET_CHANGED, "target_changed_while_breaking");
        }
        if (!MaidTerrainWorldEvaluator.isSafeToClear(
                context.level(), currentTarget, expectedState)) {
            return onCandidateFailure(context, ActionEndReason.PATH_NOT_FOUND,
                    "target_break_became_unsafe");
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
        HarvestRecord record = recordHarvestedTarget(currentTarget, expectedState);
        if (!record.harvested()) {
            return failure(ActionEndReason.INTERNAL_ERROR,
                    "successfully_broken_block_was_not_an_eligible_target");
        }
        consecutiveTerrainReplans = 0;
        currentTarget = null;
        currentStandPos = null;
        expectedState = null;
        resetTerrainPlan();
        clearProspectDirectionSweep();
        if ((!veinMining && harvested >= maxBlocks) || explicitTarget != null) {
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
        if (veinMining && veinTracker.locked()) {
            veinStopReason = message;
        }
        resetTerrainPlan();
        currentTarget = null;
        currentStandPos = null;
        expectedState = null;
        if (!veinMining && explicitTarget == null && harvested > 0
                && !miningPlan.hasNextStep(prospectSteps, prospectDescentSteps,
                segmentSteps, segmentDescentSteps)
                && !miningPlan.canAdvanceSegment(
                segmentIndex, prospectSteps, prospectDescentSteps)) {
            return success(context);
        }
        if (explicitTarget != null || rejectedCandidates.size() >= MAX_SEARCH_CANDIDATES) {
            MaidActionTickResult partial = finishLockedVeinPartial(context, message);
            if (partial != null) {
                return partial;
            }
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
        resetTerrainPlan();
        currentTarget = null;
        currentStandPos = null;
        expectedState = null;
        searchOrigin = context.maid().blockPosition().immutable();
        candidates.clear();
        searchPrepared = false;
        stage = Stage.SEARCHING;
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult restartProspectingFromActualPosition(
            MaidActionContext context) {
        resetTerrainPlan();
        prospectGoal = null;
        prospectStepMode = null;
        planningPurpose = PlanningPurpose.HARVEST;
        searchOrigin = context.maid().blockPosition().immutable();
        candidates.clear();
        searchPrepared = false;
        stage = Stage.SEARCHING;
        return MaidActionTickResult.running();
    }

    private void rememberNavigationFailure(MaidTerrainNavigator.TickResult result) {
        lastNavigationFailureDetail = result.detail() == null
                ? null : result.detail().deepCopy();
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

    private boolean isEligibleTarget(BlockPos pos, BlockState state) {
        if (explicitTarget != null) {
            return pos.equals(explicitTarget) && !state.isAir();
        }
        return selector.test(state)
                && (!veinMining || !veinTracker.locked() || veinTracker.contains(pos));
    }

    private boolean isProtectedForeignOre(BlockPos pos, BlockState state) {
        return isAnyOre(state) && !isEligibleTarget(pos, state);
    }

    private boolean isRouteClearanceAllowed(MaidActionContext context, BlockPos pos) {
        return !isProtectedForeignOre(pos, context.level().getBlockState(pos));
    }

    static boolean isAnyOre(BlockState state) {
        if (state.is(CONVENTIONAL_ORES)) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "minecraft".equals(id.getNamespace())
                && VANILLA_ORE_BLOCKS.contains(id.getPath());
    }

    private HarvestRecord recordHarvestedTarget(BlockPos pos, BlockState originalState) {
        BlockPos immutable = pos.immutable();
        if (!isEligibleTarget(immutable, originalState)
                || !harvestedPositions.add(immutable)) {
            return HarvestRecord.NOT_HARVESTED;
        }
        boolean newlyLocked = veinMining && !veinTracker.locked();
        if (veinMining) {
            if (!veinTracker.rememberHarvested(immutable)) {
                harvestedPositions.remove(immutable);
                return HarvestRecord.NOT_HARVESTED;
            }
            if (newlyLocked) {
                veinSeed = immutable;
            }
        }
        harvested++;
        rejectedCandidates.add(immutable);
        return new HarvestRecord(true, newlyLocked);
    }

    private MaidActionTickResult finishLockedVeinPartial(MaidActionContext context,
                                                          String stopReason) {
        if (!veinMining || !veinTracker.locked() || harvested <= 0) {
            return null;
        }
        veinStopReason = stopReason;
        return failure(ActionEndReason.PATH_NOT_FOUND, stopReason);
    }

    private MaidActionTickResult restartAfterVeinLock(MaidActionContext context) {
        if (navigation != null) {
            navigation.stop(context);
            navigation = null;
        }
        resetTerrainPlan();
        currentTarget = null;
        currentStandPos = null;
        expectedState = null;
        prospectGoal = null;
        prospectStepMode = null;
        planningPurpose = PlanningPurpose.HARVEST;
        clearProspectDirectionSweep();
        searchOrigin = context.maid().blockPosition().immutable();
        candidates.clear();
        searchPrepared = false;
        stage = Stage.SEARCHING;
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult success(MaidActionContext context) {
        JsonObject result = new JsonObject();
        result.addProperty("harvested", harvested);
        result.addProperty("requested", maxBlocks);
        int veinRemaining = liveVeinRemaining(context);
        int veinRejected = veinRejectedCount(context);
        boolean countLimitReached = harvested >= maxBlocks;
        boolean veinTruncated = veinTracker.truncated();
        boolean veinBoundaryLoaded = !committedVeinTouchesUnloadedChunk(context);
        boolean veinComplete = veinMining
                && veinRemaining == 0 && veinRejected == 0
                && veinStopReason == null && veinBoundaryLoaded;
        if (veinMining && !veinComplete) {
            return failure(ActionEndReason.PATH_NOT_FOUND,
                    !veinBoundaryLoaded ? "committed_vein_boundary_unloaded"
                            : veinStopReason == null
                            ? "committed_vein_not_exhausted" : veinStopReason);
        }
        report(context, Stage.VERIFYING, 1.0D, null);
        boolean requestSatisfied = veinMining ? veinComplete : countLimitReached;
        boolean veinLimitReached = false;
        boolean partial = !veinMining && harvested < maxBlocks;
        result.addProperty("partial", partial);
        result.addProperty("request_satisfied", requestSatisfied);
        if (partial) {
            result.addProperty("message", veinMining
                    ? "connected_vein_partially_harvested"
                    : "partial_harvest_completed_before_search_ended");
        }
        addSearchDiagnostics(result, "none");
        addVeinDiagnostics(result, veinRemaining, veinRejected, veinTruncated,
                veinComplete, veinLimitReached);
        return MaidActionTickResult.succeeded(result);
    }

    private int liveVeinRemaining(MaidActionContext context) {
        if (!veinMining) {
            return 0;
        }
        return (int) veinTracker.members().stream()
                .filter(pos -> !context.level().hasChunkAt(pos)
                        || selector.test(context.level().getBlockState(pos)))
                .count();
    }

    private int veinRejectedCount(MaidActionContext context) {
        if (!veinMining) {
            return 0;
        }
        return (int) rejectedCandidates.stream()
                .filter(veinTracker::contains)
                .filter(pos -> context.level().hasChunkAt(pos)
                        && selector.test(context.level().getBlockState(pos)))
                .count();
    }

    private boolean committedVeinTouchesUnloadedChunk(MaidActionContext context) {
        if (!veinMining || !veinTracker.locked()) {
            return false;
        }
        for (BlockPos member : veinTracker.members()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos adjacent = member.offset(dx, dy, dz);
                        if (adjacent.getY() < context.level().getMinBuildHeight()
                                || adjacent.getY()
                                >= context.level().getMaxBuildHeight()) {
                            continue;
                        }
                        if (!context.level().hasChunkAt(adjacent)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void addVeinDiagnostics(JsonObject result, int remaining,
                                    int rejected, boolean truncated,
                                    boolean complete, boolean limitReached) {
        result.addProperty("collection_scope", veinMining ? "connected_vein" : "nearest");
        result.addProperty("vein_mining", veinMining);
        if (!veinMining) {
            return;
        }
        if (veinSeed != null) {
            result.add("vein_seed", positionDetail(veinSeed));
        }
        result.addProperty("vein_discovered", veinTracker.knownMembers());
        result.addProperty("vein_harvested", harvested);
        result.addProperty("vein_remaining", remaining);
        result.addProperty("vein_rejected", rejected);
        result.addProperty("minimum_target", maxBlocks);
        result.addProperty("target_overshoot", Math.max(0, harvested - maxBlocks));
        result.addProperty("vein_limit", -1);
        result.addProperty("vein_truncated", truncated);
        result.addProperty("vein_complete", complete);
        result.addProperty("vein_limit_reached", limitReached);
        if (veinStopReason != null) {
            result.addProperty("vein_stop_reason", veinStopReason);
        }
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
        detail.addProperty("collection_scope", veinMining ? "connected_vein" : "nearest");
        if (veinMining) {
            detail.addProperty("vein_discovered", veinTracker.knownMembers());
            detail.addProperty("vein_harvested", harvested);
            detail.addProperty("vein_truncated", veinTracker.truncated());
        }
        if (miningPlan.enabled()) {
            addProspectingDiagnostics(detail);
        }
        context.execution().reportProgress(nextStage.wireName, progress, detail);
    }

    private double overallProgress() {
        return Math.min(0.99D, (double) harvested / maxBlocks);
    }

    private MaidActionTickResult failure(ActionEndReason reason, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("message", message);
        result.addProperty("diagnostic_code", message);
        result.addProperty("decision_required", true);
        result.addProperty("recoverability", recoverability(reason));
        if (veinMining) {
            result.addProperty("minimum_target", maxBlocks);
            result.addProperty("minimum_reached", harvested >= maxBlocks);
            result.addProperty("vein_locked", veinTracker.locked());
            result.addProperty("vein_harvested", harvested);
            result.addProperty("vein_known", veinTracker.knownMembers());
            result.addProperty("vein_complete", false);
            result.addProperty("completion_rule",
                    "minimum_target_then_finish_connected_vein");
        }
        addSearchDiagnostics(result, retryHint(reason, message));
        return MaidActionTickResult.failed(reason, result);
    }

    private static String recoverability(ActionEndReason reason) {
        return switch (reason) {
            case PATH_NOT_FOUND, STUCK, TARGET_CHANGED, VALIDATION_FAILED, TIMEOUT ->
                    "llm_decision";
            case TOOL_NOT_FOUND, HAND_CONFLICT -> "needs_resource";
            case BLOCK_PROTECTED, USER_OVERRIDE, SAFETY_PREEMPTED ->
                    "player_confirmation";
            case ENTITY_UNLOADED, ENTITY_DEAD, SERVER_STATE_LOST, INTERNAL_ERROR ->
                    "fatal";
            case COMPLETED, REQUESTED, SUPERSEDED -> "none";
        };
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
        result.addProperty("consecutive_terrain_replans", consecutiveTerrainReplans);
        result.addProperty("route_blocks_cleared", routeBlocksCleared);
        if (lastNavigationFailureDetail != null) {
            result.add("last_navigation_failure", lastNavigationFailureDetail.deepCopy());
        }
        result.addProperty("search_radius", searchRadius);
        result.addProperty("selector", selectorDescription);
        result.addProperty("collection_scope", veinMining ? "connected_vein" : "nearest");
        result.addProperty("vein_mining", veinMining);
        if (veinMining) {
            if (veinSeed != null) {
                result.add("vein_seed", positionDetail(veinSeed));
            }
            result.addProperty("vein_discovered", veinTracker.knownMembers());
            result.addProperty("vein_harvested", harvested);
            result.addProperty("minimum_target", maxBlocks);
            result.addProperty("target_overshoot", Math.max(0, harvested - maxBlocks));
            result.addProperty("vein_limit", -1);
            result.addProperty("vein_truncated", veinTracker.truncated());
        }
        addProspectingDiagnostics(result);
        result.addProperty("retry_hint", retryHint);
    }

    private void addProspectingDiagnostics(JsonObject result) {
        result.addProperty("mining_plan", miningPlan.mode().wireName());
        result.addProperty("prospect_direction",
                prospectDirection == null ? "unresolved" : prospectDirection.getName());
        result.addProperty("prospect_steps", prospectSteps);
        result.addProperty("prospect_max_distance", miningPlan.maxDistance());
        result.addProperty("prospect_descent_steps", prospectDescentSteps);
        result.addProperty("prospect_max_depth", miningPlan.maxDepth());
        result.addProperty("prospect_segment",
                segmentIndex == Long.MAX_VALUE ? Long.MAX_VALUE : segmentIndex + 1L);
        result.addProperty("prospect_unbounded", miningPlan.enabled());
        result.addProperty("prospect_limit_mode", "unbounded");
        result.addProperty("prospect_max_segments", miningPlan.maxSegments());
        result.addProperty("prospect_segment_steps", segmentSteps);
        result.addProperty("prospect_segment_descent_steps", segmentDescentSteps);
        result.addProperty("prospect_total_step_limit", -1);
        result.addProperty("prospect_total_descent_limit", -1);
        result.addProperty("prospect_blocks_cleared", prospectBlocksCleared);
        result.addProperty("prospect_excavation_budget", -1);
        result.addProperty("prospect_remaining_excavation_budget", -1);
        result.addProperty("prospect_descent_blocked", prospectDescentBlocked);
        result.addProperty("prospect_world_bottom_reached", prospectWorldBottomReached);
        if (lastProspectFallbackReason != null) {
            result.addProperty("prospect_fallback_reason", lastProspectFallbackReason);
        }
        if (lastProspectFallbackDirection != null) {
            result.addProperty("prospect_fallback_direction", lastProspectFallbackDirection);
        }
        result.addProperty("prospect_fallbacks", prospectFallbacks);
        result.addProperty("prospect_directions_tried",
                prospectDirectionAttemptOrder.stream()
                        .map(Direction::getName)
                        .collect(java.util.stream.Collectors.joining(",")));
        result.addProperty("prospect_directions_tried_count", prospectDirectionsTried.size());
        result.addProperty("prospect_direction_attempts", prospectDirectionsTried.size());
        JsonArray attemptedDirections = new JsonArray();
        for (Direction attempted : prospectDirectionAttemptOrder) {
            attemptedDirections.add(attempted.getName());
        }
        result.add("prospect_attempted_directions", attemptedDirections);
        result.addProperty("prospect_directions_exhausted",
                prospectDirectionsExhausted);
        if (prospectDirectionOrigin != null) {
            result.add("prospect_origin", positionDetail(prospectDirectionOrigin));
        }
        if (prospectStepMode != null) {
            result.addProperty("prospect_step_mode",
                    prospectStepMode.name().toLowerCase(java.util.Locale.ROOT));
            result.addProperty("last_prospect_step_mode",
                    prospectStepMode.name().toLowerCase(java.util.Locale.ROOT));
        }
        result.addProperty("prospect_rescans", prospectRescans);
    }

    private String retryHint(ActionEndReason reason, String message) {
        if ("no_safe_prospecting_step_found".equals(message)
                || "all_auto_prospect_directions_exhausted".equals(message)) {
            return miningPlan.mode() == MiningPlan.Mode.AUTO
                    && miningPlan.heading() == MiningPlan.Heading.MAID_FACING
                    && prospectDirectionsExhausted
                    ? "All four horizontal directions were evaluated at the current origin; do not increase search_radius or repeat the same plan, and inspect hazards, two-block clearance, support, and loaded chunks before proposing a different authorized route"
                    : "The requested mining direction has no safe executable step; inspect hazards, support, and loaded chunks before changing the plan";
        }
        if (isLocalNavigationEdgeFailure(message)) {
            if (miningPlan.enabled()) {
                return "The terrain route was found but its local movement edge could not be executed; choose a different safe mining direction instead of increasing search_radius";
            }
            if (explicitTarget != null) {
                return "Keep the specified target and safely reposition the maid or request clearance for the blocked local edge; increasing search_radius will not help";
            }
            return "The server exhausted executable routes to this candidate set; choose another nearby target or a different safe terrain-clearance plan instead of increasing search_radius";
        }
        return switch (reason) {
            case PATH_NOT_FOUND, STUCK ->
                    "Move the maid closer, provide the required tools, or increase search_radius within loaded chunks";
            case TOOL_NOT_FOUND -> "Provide a correct harvesting tool or use tool_policy=allow_wrong";
            case TARGET_CHANGED -> "Refresh the target or retry with a broader block/tag selector";
            case VALIDATION_FAILED -> "Check the target coordinates and loaded area before retrying";
            default -> "Refresh world state and retry the action";
        };
    }

    static boolean isLocalNavigationEdgeFailure(String message) {
        return "native_navigation_cannot_reach_terrain_step".equals(message)
                || "native_navigation_rejected_terrain_step".equals(message)
                || "native_navigation_finished_before_terrain_step".equals(message)
                || "direct_waypoint_made_no_progress".equals(message)
                || "controlled_descend_made_no_progress".equals(message);
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

    private static boolean optionalBoolean(JsonObject parent, String name, boolean fallback) {
        if (!parent.has(name)) {
            return fallback;
        }
        if (!parent.get(name).isJsonPrimitive()
                || !parent.getAsJsonPrimitive(name).isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return parent.get(name).getAsBoolean();
    }

    private static boolean isPureOreSelector(Predicate<BlockState> selector) {
        if (selector == null) {
            return false;
        }
        boolean matched = false;
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (!selector.test(state)) {
                continue;
            }
            matched = true;
            if (!state.is(CONVENTIONAL_ORES)) {
                return false;
            }
        }
        return matched;
    }

    static boolean looksLikeOreSelector(String type, ResourceLocation id) {
        String namespace = id.getNamespace();
        String path = id.getPath();
        if ("block".equals(type)) {
            return "minecraft".equals(namespace) && VANILLA_ORE_BLOCKS.contains(path);
        }
        return "tag".equals(type) && (("minecraft".equals(namespace)
                && VANILLA_ORE_TAGS.contains(path))
                || ("c".equals(namespace)
                && (path.equals("ores") || path.startsWith("ores/"))));
    }

    private static double miningSelectionScore(BlockPos origin, BlockPos target) {
        double distance = Math.sqrt(origin.distSqr(target));
        double depthPenalty = Math.max(0, origin.getY() - target.getY()) * 3.0D;
        return distance + depthPenalty;
    }

    private int planningHorizontalRadius(BlockPos start) {
        int radius = searchRadius + 2;
        for (BlockPos candidate : candidates) {
            long dx = (long) candidate.getX() - start.getX();
            long dz = (long) candidate.getZ() - start.getZ();
            radius = Math.max(radius, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz)) + 2);
        }
        return radius;
    }

    private int planningVerticalRadius(BlockPos start) {
        int radius = searchRadius + 2;
        for (BlockPos candidate : candidates) {
            radius = Math.max(radius, Math.abs(candidate.getY() - start.getY()) + 2);
        }
        return radius;
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

    private record HarvestRecord(boolean harvested, boolean newlyLocked) {
        private static final HarvestRecord NOT_HARVESTED = new HarvestRecord(false, false);
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
        PROSPECTING("prospecting"),
        BREAKING("breaking"),
        VERIFYING("verifying");

        private final String wireName;

        Stage(String wireName) {
            this.wireName = wireName;
        }
    }

    private enum PlanningPurpose {
        HARVEST("harvest"),
        PROSPECT("prospect");

        private final String wireName;

        PlanningPurpose(String wireName) {
            this.wireName = wireName;
        }
    }
}
