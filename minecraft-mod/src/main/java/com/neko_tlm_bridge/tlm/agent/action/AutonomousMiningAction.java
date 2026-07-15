package com.neko_tlm_bridge.tlm.agent.action;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.MaidAction;
import com.neko_tlm_bridge.tlm.agent.MaidActionContext;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.MaidActionResource;
import com.neko_tlm_bridge.tlm.agent.MaidActionTickResult;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainPath;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainSearch;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainWorldEvaluator;
import com.neko_tlm_bridge.tlm.agent.runtime.HandLease;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import com.neko_tlm_bridge.tlm.agent.world.MiningWorldModelSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Long-running mine-search loop.  It owns exactly one body/action execution
 * and one {@link HandLease}; excavation and harvesting deliberately reuse the
 * lower-level terrain planner/executor instead of nesting top-level actions.
 */
public final class AutonomousMiningAction implements MaidAction {
    private static final int SCAN_RADIUS = 12;
    private static final int SEARCH_BUDGET_PER_TICK = 256;
    private static final int MAX_EXCAVATION_EXPANSIONS = 64;
    private static final int MAX_HARVEST_EXPANSIONS = 4_096;
    private static final int MAX_HARVEST_GOALS = 64;
    private static final int MAX_CANDIDATES = 16;
    private static final double MAX_BREAK_DISTANCE_SQUARED = 4.5D * 4.5D;
    private static final Set<String> ALLOWED_ARGS = Set.of(
            "selector", "target_count", "direction", "shape",
            "segment_length", "speed", "discovery_mode");
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final int[] STAND_Y_OFFSETS = {0, -1, 1};

    private final Predicate<BlockState> selector;
    private final String selectorDescription;
    private final int targetCount;
    private final DirectionMode directionMode;
    private final ShapeMode shapeMode;
    private final int segmentLength;
    private final double speed;
    private final DiscoveryMode discoveryMode;
    private final JsonObject normalizedArgs;
    private AutonomousMiningState state;
    private final Set<BlockPos> harvestedPositions = new HashSet<>();

    private MaidVeinTracker veinTracker = new MaidVeinTracker(256);
    private BlockPos origin;
    private BlockPos realEnd;
    private Direction activeDirection;
    private ExcavateSegmentAction.Shape activeShape;
    private List<Direction> directionAttempts = List.of();
    private int directionAttemptIndex;
    private BlockPos directionSweepOrigin;
    private int stepsInCurrentSegment;
    private int currentStepCleared;
    private boolean started;

    private HandLease handLease;
    private MaidTerrainSearch terrainSearch;
    private PlanningPurpose planningPurpose;
    private MaidTerrainNavigator navigator;
    private Map<BlockPos, BlockPos> terrainGoalTargets = Map.of();
    private BlockPos stepFrom;
    private BlockPos stepTo;
    private MaidTerrainStep.Kind stepKind;
    private BlockPos currentTarget;
    private BlockState expectedTargetState;
    private MaidProgressiveBlockBreaker breaker;
    private int expandedNodes;
    private boolean persistentSessionActive;

    public AutonomousMiningAction(Predicate<BlockState> selector,
                                  String selectorDescription,
                                  int targetCount,
                                  DirectionMode directionMode,
                                  ShapeMode shapeMode,
                                  int segmentLength,
                                  double speed,
                                  DiscoveryMode discoveryMode) {
        this.selector = Objects.requireNonNull(selector, "selector");
        this.selectorDescription = Objects.requireNonNull(
                selectorDescription, "selectorDescription");
        if (targetCount < 1) {
            throw new IllegalArgumentException("target_count must be at least 1");
        }
        if (segmentLength < 1 || segmentLength > 8) {
            throw new IllegalArgumentException("segment_length must be between 1 and 8");
        }
        if (!Double.isFinite(speed) || speed < 0.4D || speed > 1.0D) {
            throw new IllegalArgumentException("speed must be between 0.4 and 1.0");
        }
        this.targetCount = targetCount;
        this.directionMode = Objects.requireNonNull(directionMode, "directionMode");
        this.shapeMode = Objects.requireNonNull(shapeMode, "shapeMode");
        this.segmentLength = segmentLength;
        this.speed = speed;
        this.discoveryMode = Objects.requireNonNull(discoveryMode, "discoveryMode");
        this.state = new AutonomousMiningState(targetCount);
        this.normalizedArgs = normalizedArgs(selectorDescription, targetCount,
                directionMode, shapeMode, segmentLength, speed, discoveryMode);
    }

    public static AutonomousMiningAction fromArgs(JsonObject args) {
        Objects.requireNonNull(args, "args");
        for (String field : args.keySet()) {
            if (!ALLOWED_ARGS.contains(field)) {
                throw new IllegalArgumentException(
                        "Unsupported autonomous_mining field: " + field);
            }
        }
        JsonObject selectorJson = requireObject(args, "selector");
        for (String field : selectorJson.keySet()) {
            if (!Set.of("type", "id").contains(field)) {
                throw new IllegalArgumentException(
                        "Unsupported selector field: " + field);
            }
        }
        String selectorType = requireString(selectorJson, "type")
                .trim().toLowerCase(Locale.ROOT);
        ResourceLocation selectorId = parseResourceLocation(
                requireString(selectorJson, "id"));
        Predicate<BlockState> selector;
        String description;
        if ("block".equals(selectorType)) {
            selector = candidate -> candidate.getBlock()
                    .builtInRegistryHolder().is(selectorId);
            description = "block:" + selectorId;
        } else if ("tag".equals(selectorType)) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, selectorId);
            selector = candidate -> candidate.is(tag);
            description = "tag:#" + selectorId;
        } else {
            throw new IllegalArgumentException("selector.type must be block or tag");
        }

        int targetCount = optionalInt(args, "target_count", 1);
        int segmentLength = optionalInt(args, "segment_length", 8);
        double speed = optionalDouble(args, "speed", 0.7D);
        if (targetCount < 1) {
            throw new IllegalArgumentException("target_count must be at least 1");
        }
        if (segmentLength < 1 || segmentLength > 8) {
            throw new IllegalArgumentException("segment_length must be between 1 and 8");
        }
        if (speed < 0.4D || speed > 1.0D) {
            throw new IllegalArgumentException("speed must be between 0.4 and 1.0");
        }
        return new AutonomousMiningAction(selector, description, targetCount,
                DirectionMode.fromWireName(optionalString(args, "direction", "auto")),
                ShapeMode.fromWireName(optionalString(args, "shape", "auto")),
                segmentLength, speed,
                DiscoveryMode.fromWireName(optionalString(
                        args, "discovery_mode", "loaded_scan")));
    }

    @Override
    public MaidActionKind kind() {
        return MaidActionKind.AUTONOMOUS_MINING;
    }

    @Override
    public Set<MaidActionResource> resources() {
        return Set.of(MaidActionResource.MOVE, MaidActionResource.HAND,
                MaidActionResource.BREAK);
    }

    @Override
    public void start(MaidActionContext context) {
        started = true;
        MiningWorldModelSavedData.OperationSnapshot snapshot =
                MiningWorldModelSavedData.getOrCreate(
                        context.level(), context.execution().actionId(),
                        context.maid().getUUID(), normalizedArgs);
        if (snapshot.blocked() || snapshot.terminal()) {
            state.block(snapshot.blocked()
                    ? "blocked_session_requires_decision"
                    : "terminal_session_cannot_resume");
            origin = snapshot.originPos() == null
                    ? context.maid().blockPosition().immutable()
                    : snapshot.originPos();
            realEnd = snapshot.currentWorkfacePos() == null
                    ? context.maid().blockPosition().immutable()
                    : snapshot.currentWorkfacePos();
            report(context, AutonomousMiningState.Phase.BLOCKED,
                    detail(state.blockedReason()));
            return;
        }
        state = AutonomousMiningState.restore(targetCount,
                snapshot.collectedCount(), snapshot.segmentsDug(),
                snapshot.clearedBlocks());
        origin = snapshot.originPos() == null
                ? context.maid().blockPosition().immutable()
                : snapshot.originPos();
        realEnd = context.maid().blockPosition().immutable();
        restoreRoute(snapshot);
        MiningWorldModelSavedData model = MiningWorldModelSavedData.get(context.level());
        model.updateGeneration(context.execution().actionId(),
                context.execution().generation(), context.gameTime());
        model.setOperationStatus(context.execution().actionId(),
                MiningWorldModelSavedData.OperationStatus.ACTIVE, context.gameTime());
        model.setOrigin(context.execution().actionId(), origin, context.gameTime());
        model.updateWorkface(context.execution().actionId(), realEnd, context.gameTime());
        persistentSessionActive = true;
        report(context, AutonomousMiningState.Phase.VALIDATING,
                detail("initial_validation"));
    }

    @Override
    public MaidActionTickResult tick(MaidActionContext context) {
        if (!started) {
            start(context);
        }
        return switch (state.phase()) {
            case VALIDATING -> validateAndLeaseTool(context);
            case SELECTING_SITE -> selectExcavationStep(context);
            case EXCAVATING -> advanceExcavation(context);
            case SCANNING -> scanAndPlanHarvest(context);
            case HARVESTING -> advanceHarvest(context);
            case CONTINUING -> continueProspecting(context);
            case COMPLETED -> MaidActionTickResult.succeeded(result("none", false));
            case BLOCKED -> MaidActionTickResult.failed(
                    ActionEndReason.INTERNAL_ERROR,
                    result(state.blockedReason(), true));
        };
    }

    @Override
    public void stop(MaidActionContext context, ActionEndReason reason) {
        if (breaker != null) {
            breaker.stop(context);
            breaker = null;
        }
        if (navigator != null) {
            navigator.stop(context);
            navigator = null;
        }
        terrainSearch = null;
        planningPurpose = null;
        if (context != null && context.maid() != null) {
            realEnd = context.maid().blockPosition().immutable();
            persistExternalStop(context, reason);
        }
    }

    @Override
    public JsonObject terminationResult(MaidActionContext context,
                                        ActionEndReason reason) {
        BlockPos liveEnd = context == null || context.maid() == null
                ? realEnd : context.maid().blockPosition().immutable();
        if (liveEnd != null) {
            realEnd = liveEnd;
        }
        String terminalReason = reason == null
                ? "internal_error" : reason.name().toLowerCase(Locale.ROOT);
        return result(terminalReason, false);
    }

    int targetCount() {
        return targetCount;
    }

    DirectionMode directionMode() {
        return directionMode;
    }

    ShapeMode shapeMode() {
        return shapeMode;
    }

    int segmentLength() {
        return segmentLength;
    }

    double speed() {
        return speed;
    }

    DiscoveryMode discoveryMode() {
        return discoveryMode;
    }

    String selectorDescription() {
        return selectorDescription;
    }

    JsonObject normalizedArgs() {
        return normalizedArgs.deepCopy();
    }

    private MaidActionTickResult validateAndLeaseTool(MaidActionContext context) {
        if (!loadedBuildPosition(context, origin)
                || !loadedBuildPosition(context, origin.above())
                || !loadedBuildPosition(context, origin.below())
                || !context.maid().onGround()) {
            return blocked(context, ActionEndReason.VALIDATION_FAILED,
                    "unsafe_or_unloaded_start");
        }
        BlockState support = context.level().getBlockState(origin.below());
        if (MaidTerrainWorldEvaluator.assessStandSupport(
                context.level(), origin.below(), support)
                != MaidTerrainWorldEvaluator.SupportAssessment.SAFE) {
            return blocked(context, ActionEndReason.PATH_NOT_FOUND,
                    "unsafe_support");
        }

        List<BlockState> representativeStates = new ArrayList<>();
        representativeStates.add(Blocks.STONE.defaultBlockState());
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState candidate = block.defaultBlockState();
            if (selector.test(candidate)) {
                representativeStates.add(candidate);
                break;
            }
        }
        ToolCandidate selected = findBestTool(context.maid(), representativeStates);
        if (selected == null) {
            return blocked(context, ActionEndReason.TOOL_NOT_FOUND,
                    "tool_not_found");
        }
        try {
            handLease = selected.slot() == HandLease.HELD_TOOL_SLOT
                    ? HandLease.heldTool(context.maid())
                    : HandLease.equipFromBackpack(context.maid(), selected.slot());
        } catch (RuntimeException conflict) {
            return blocked(context, ActionEndReason.HAND_CONFLICT,
                    "hand_conflict");
        }
        if (!MaidActionStore.getInstance().attachHandLease(
                context.execution().actionId(), context.execution().generation(), handLease)) {
            handLease.release(context.maid());
            handLease = null;
            return blocked(context, ActionEndReason.SUPERSEDED,
                    "superseded");
        }

        transition(context, state.goalReached()
                        ? AutonomousMiningState.Phase.SCANNING
                        : AutonomousMiningState.Phase.SELECTING_SITE,
                detail(state.goalReached()
                        ? "restored_goal_vein_rescan" : "tool_leased"));
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult selectExcavationStep(MaidActionContext context) {
        BlockPos live = context.maid().blockPosition().immutable();
        if (!context.maid().onGround()) {
            return alternateOrBlocked(context, ActionEndReason.STUCK,
                    "maid_not_grounded_at_segment_origin");
        }
        alignDirectionSweep(live, context.maid().getDirection());
        if (activeShape == null) {
            activeShape = resolveShape(context);
        }

        stepFrom = live;
        stepTo = ExcavateSegmentAction.nextPosition(
                stepFrom, activeDirection, activeShape).immutable();
        stepKind = activeShape == ExcavateSegmentAction.Shape.STAIRCASE_DOWN
                ? MaidTerrainStep.Kind.DESCEND : MaidTerrainStep.Kind.TRAVERSE;
        currentStepCleared = 0;

        List<BlockPos> inspected = new ArrayList<>(
                ExcavateSegmentAction.clearanceFor(stepTo, activeShape));
        inspected.add(stepTo.below());
        for (BlockPos pos : inspected) {
            if (!loadedBuildPosition(context, pos)) {
                return alternateOrBlocked(context, ActionEndReason.PATH_NOT_FOUND,
                        "unloaded_excavation_edge");
            }
            BlockState blockState = context.level().getBlockState(pos);
            if (HarvestBlocksAction.isAnyOre(blockState)) {
                if (selector.test(blockState)) {
                    transition(context, AutonomousMiningState.Phase.SCANNING,
                            positionDetail("selected_ore_encountered", pos));
                    return MaidActionTickResult.running();
                }
                return alternateOrBlocked(context, ActionEndReason.PATH_NOT_FOUND,
                        "foreign_ore_obstruction");
            }
        }

        BlockPos supportPos = stepTo.below();
        BlockState support = context.level().getBlockState(supportPos);
        MaidTerrainWorldEvaluator.SupportAssessment supportAssessment =
                MaidTerrainWorldEvaluator.assessStandSupport(
                        context.level(), supportPos, support);
        if (supportAssessment != MaidTerrainWorldEvaluator.SupportAssessment.SAFE) {
            return alternateOrBlocked(context, supportEndReason(supportAssessment),
                    supportAssessment.name());
        }
        for (BlockPos pos : ExcavateSegmentAction.clearanceFor(stepTo, activeShape)) {
            BlockState blockState = context.level().getBlockState(pos);
            MaidTerrainWorldEvaluator.ClearanceAssessment clearance =
                    MaidTerrainWorldEvaluator.assessClearance(
                            context.level(), pos, blockState);
            if (clearance != MaidTerrainWorldEvaluator.ClearanceAssessment.CLEAR
                    && clearance != MaidTerrainWorldEvaluator.ClearanceAssessment.BREAKABLE) {
                return alternateOrBlocked(context, clearanceEndReason(clearance),
                        clearance.name());
            }
            if (clearance == MaidTerrainWorldEvaluator.ClearanceAssessment.BREAKABLE
                    && blockState.requiresCorrectToolForDrops()
                    && !context.maid().getMainHandItem()
                    .isCorrectToolForDrops(blockState)) {
                return alternateOrBlocked(context, ActionEndReason.TOOL_NOT_FOUND,
                        "tool_not_found");
            }
        }

        MaidTerrainWorldEvaluator evaluator = new MaidTerrainWorldEvaluator(
                context.level(), context.maid(), stepFrom, 3, 3, true,
                pos -> !HarvestBlocksAction.isAnyOre(
                        context.level().getBlockState(pos)));
        terrainSearch = new MaidTerrainSearch(stepFrom, Set.of(stepTo), evaluator,
                MAX_EXCAVATION_EXPANSIONS, EnumSet.of(stepKind));
        terrainGoalTargets = Map.of();
        planningPurpose = PlanningPurpose.EXCAVATION;
        transition(context, AutonomousMiningState.Phase.EXCAVATING,
                stepDetail("pathfinding"));
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult advanceExcavation(MaidActionContext context) {
        if (terrainSearch != null) {
            MaidTerrainSearch.Status status = terrainSearch.advance(
                    SEARCH_BUDGET_PER_TICK);
            if (status == MaidTerrainSearch.Status.SEARCHING) {
                report(context, AutonomousMiningState.Phase.EXCAVATING,
                        stepDetail("pathfinding"));
                return MaidActionTickResult.running();
            }
            expandedNodes += terrainSearch.expandedNodes();
            if (status == MaidTerrainSearch.Status.FAILED) {
                return alternateOrBlocked(context, ActionEndReason.PATH_NOT_FOUND,
                        "path_not_found");
            }
            MaidTerrainPath path = terrainSearch.result().orElse(null);
            terrainSearch = null;
            if (!isExactExcavationStep(path)) {
                return blocked(context, ActionEndReason.INTERNAL_ERROR,
                        "planner_returned_non_direct_excavation_step");
            }
            navigator = new MaidTerrainNavigator(path, handLease, speed, true);
            navigator.start(context);
            report(context, AutonomousMiningState.Phase.EXCAVATING,
                    stepDetail("moving"));
            return MaidActionTickResult.running();
        }
        if (navigator == null || planningPurpose != PlanningPurpose.EXCAVATION) {
            return blocked(context, ActionEndReason.INTERNAL_ERROR,
                    "excavation_runtime_missing");
        }
        MaidTerrainNavigator.TickResult tick = navigator.tick(context);
        for (MaidTerrainNavigator.ClearedBlock event : navigator.drainClearedBlocks()) {
            if (HarvestBlocksAction.isAnyOre(event.state())) {
                return blocked(context, ActionEndReason.INTERNAL_ERROR,
                        "route_cleared_ore_invariant_breached");
            }
            currentStepCleared++;
        }
        if (tick.outcome() == MaidTerrainNavigator.Outcome.FAILED) {
            navigator = null;
            return alternateOrBlocked(context, defaultReason(tick.reason()),
                    tickMessage(tick, "excavation_failed"));
        }
        if (tick.outcome() == MaidTerrainNavigator.Outcome.ARRIVED) {
            navigator = null;
            planningPurpose = null;
            realEnd = context.maid().blockPosition().immutable();
            state.recordExcavationStep(currentStepCleared);
            stepsInCurrentSegment++;
            transition(context, AutonomousMiningState.Phase.SCANNING,
                    stepDetail("step_complete"));
            return MaidActionTickResult.running();
        }
        JsonObject detail = tick.detail().deepCopy();
        detail.addProperty("substage", "moving");
        report(context, AutonomousMiningState.Phase.EXCAVATING, detail);
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult scanAndPlanHarvest(MaidActionContext context) {
        List<BlockPos> discovered = scan(context);
        if (veinTracker.locked()) {
            List<BlockPos> connected = veinTracker.retainConnected(
                    discovered, targetComparator(context));
            if (!connected.isEmpty()) {
                discovered = connected;
            } else {
                if (state.goalReached()) {
                    return complete(context, "connected_vein_exhausted");
                }
                veinTracker = new MaidVeinTracker(256);
            }
        }
        if (discovered.isEmpty()) {
            if (state.goalReached()) {
                return complete(context, "target_minimum_reached");
            }
            transition(context, AutonomousMiningState.Phase.CONTINUING,
                    detail("no_target_found"));
            return MaidActionTickResult.running();
        }
        List<BlockPos> candidates = discovered.stream()
                .limit(MAX_CANDIDATES)
                .toList();
        return planHarvestApproach(context, candidates);
    }

    private MaidActionTickResult planHarvestApproach(
            MaidActionContext context, List<BlockPos> candidates) {
        BlockPos start = context.maid().blockPosition().immutable();
        for (BlockPos target : candidates) {
            BlockState candidateState = context.level().getBlockState(target);
            if (!eligibleTarget(target, candidateState)) {
                continue;
            }
            if (candidateState.requiresCorrectToolForDrops()
                    && !context.maid().getMainHandItem()
                    .isCorrectToolForDrops(candidateState)) {
                return blocked(context, ActionEndReason.TOOL_NOT_FOUND,
                        "tool_not_found");
            }
            if (canReachVisibleFace(context, target)
                    && !target.equals(start.below())) {
                currentTarget = target;
                expectedTargetState = candidateState;
                breaker = new MaidProgressiveBlockBreaker(
                        currentTarget, expectedTargetState, handLease, true);
                transition(context, AutonomousMiningState.Phase.HARVESTING,
                        positionDetail("breaking", currentTarget));
                return MaidActionTickResult.running();
            }
        }

        MaidTerrainWorldEvaluator evaluator = new MaidTerrainWorldEvaluator(
                context.level(), context.maid(), start,
                SCAN_RADIUS + 3, SCAN_RADIUS + 3, true,
                pos -> !HarvestBlocksAction.isAnyOre(
                        context.level().getBlockState(pos)));
        LinkedHashMap<BlockPos, BlockPos> goals = new LinkedHashMap<>();
        for (BlockPos target : candidates) {
            BlockState candidateState = context.level().getBlockState(target);
            if (!eligibleTarget(target, candidateState)) {
                continue;
            }
            for (BlockPos stand : standPositionCandidates(target)) {
                if (goals.size() >= MAX_HARVEST_GOALS) {
                    break;
                }
                if (potentialMiningStance(evaluator, stand, target)) {
                    goals.putIfAbsent(stand.immutable(), target.immutable());
                }
            }
        }
        if (goals.isEmpty()) {
            return blocked(context, ActionEndReason.PATH_NOT_FOUND,
                    "no_safe_mining_stance");
        }
        terrainGoalTargets = Map.copyOf(goals);
        terrainSearch = new MaidTerrainSearch(start, goals.keySet(), evaluator,
                MAX_HARVEST_EXPANSIONS, EnumSet.of(
                MaidTerrainStep.Kind.TRAVERSE,
                MaidTerrainStep.Kind.ASCEND,
                MaidTerrainStep.Kind.DESCEND));
        planningPurpose = PlanningPurpose.HARVEST;
        transition(context, AutonomousMiningState.Phase.HARVESTING,
                detail("pathfinding_to_ore"));
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult advanceHarvest(MaidActionContext context) {
        if (terrainSearch != null) {
            MaidTerrainSearch.Status status = terrainSearch.advance(
                    SEARCH_BUDGET_PER_TICK);
            if (status == MaidTerrainSearch.Status.SEARCHING) {
                report(context, AutonomousMiningState.Phase.HARVESTING,
                        detail("pathfinding_to_ore"));
                return MaidActionTickResult.running();
            }
            expandedNodes += terrainSearch.expandedNodes();
            if (status == MaidTerrainSearch.Status.FAILED) {
                return blocked(context, ActionEndReason.PATH_NOT_FOUND,
                        "ore_path_not_found");
            }
            MaidTerrainPath path = terrainSearch.result().orElse(null);
            terrainSearch = null;
            if (path == null) {
                return blocked(context, ActionEndReason.INTERNAL_ERROR,
                        "ore_path_missing");
            }
            currentTarget = terrainGoalTargets.get(path.target());
            terrainGoalTargets = Map.of();
            if (currentTarget == null) {
                return blocked(context, ActionEndReason.INTERNAL_ERROR,
                        "ore_goal_mapping_lost");
            }
            expectedTargetState = context.level().getBlockState(currentTarget);
            if (!eligibleTarget(currentTarget, expectedTargetState)) {
                return blocked(context, ActionEndReason.TARGET_CHANGED,
                        "target_changed");
            }
            navigator = new MaidTerrainNavigator(path, handLease, speed, true);
            navigator.start(context);
            report(context, AutonomousMiningState.Phase.HARVESTING,
                    positionDetail("approaching", currentTarget));
            return MaidActionTickResult.running();
        }
        if (navigator != null) {
            MaidTerrainNavigator.TickResult tick = navigator.tick(context);
            int cleared = 0;
            for (MaidTerrainNavigator.ClearedBlock event : navigator.drainClearedBlocks()) {
                if (HarvestBlocksAction.isAnyOre(event.state())) {
                    return blocked(context, ActionEndReason.INTERNAL_ERROR,
                            "route_cleared_ore_invariant_breached");
                }
                cleared++;
            }
            state.recordRouteClearance(cleared);
            if (tick.outcome() == MaidTerrainNavigator.Outcome.FAILED) {
                return blocked(context, defaultReason(tick.reason()),
                        tickMessage(tick, "ore_approach_failed"));
            }
            if (tick.outcome() == MaidTerrainNavigator.Outcome.ARRIVED) {
                navigator = null;
                expectedTargetState = context.level().getBlockState(currentTarget);
                if (!eligibleTarget(currentTarget, expectedTargetState)) {
                    return blocked(context, ActionEndReason.TARGET_CHANGED,
                            "target_changed");
                }
                breaker = new MaidProgressiveBlockBreaker(
                        currentTarget, expectedTargetState, handLease, true);
                report(context, AutonomousMiningState.Phase.HARVESTING,
                        positionDetail("breaking", currentTarget));
                return MaidActionTickResult.running();
            }
            JsonObject detail = tick.detail().deepCopy();
            detail.addProperty("substage", "approaching");
            report(context, AutonomousMiningState.Phase.HARVESTING, detail);
            return MaidActionTickResult.running();
        }
        if (breaker == null) {
            return blocked(context, ActionEndReason.INTERNAL_ERROR,
                    "harvest_runtime_missing");
        }
        MaidProgressiveBlockBreaker.TickResult tick = breaker.tick(context);
        if (tick.outcome() == MaidProgressiveBlockBreaker.Outcome.FAILED) {
            breaker = null;
            return blocked(context, defaultReason(tick.reason()),
                    tick.detail().has("message")
                            ? tick.detail().get("message").getAsString()
                            : "ore_break_failed");
        }
        if (tick.outcome() == MaidProgressiveBlockBreaker.Outcome.CLEARED) {
            breaker = null;
            harvestedPositions.add(currentTarget.immutable());
            veinTracker.rememberHarvested(currentTarget);
            state.recordHarvest();
            realEnd = context.maid().blockPosition().immutable();
            currentTarget = null;
            expectedTargetState = null;
            transition(context, AutonomousMiningState.Phase.SCANNING,
                    detail("vein_rescan"));
            return MaidActionTickResult.running();
        }
        JsonObject detail = tick.detail().deepCopy();
        detail.addProperty("substage", "breaking");
        report(context, AutonomousMiningState.Phase.HARVESTING, detail);
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult continueProspecting(MaidActionContext context) {
        if (stepsInCurrentSegment >= segmentLength) {
            stepsInCurrentSegment = 0;
            activeShape = null;
        }
        transition(context, AutonomousMiningState.Phase.SELECTING_SITE,
                detail("next_excavation_step"));
        return MaidActionTickResult.running();
    }

    private List<BlockPos> scan(MaidActionContext context) {
        BlockPos center = context.maid().blockPosition();
        List<BlockPos> discovered = new ArrayList<>();
        for (BlockPos mutable : BlockPos.betweenClosed(
                center.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                center.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            BlockPos pos = mutable.immutable();
            if (discovered.size() >= 512
                    || harvestedPositions.contains(pos)
                    || !loadedBuildPosition(context, pos)) {
                continue;
            }
            BlockState candidate = context.level().getBlockState(pos);
            if (!selector.test(candidate)) {
                continue;
            }
            if (discoveryMode == DiscoveryMode.EXPOSED_ONLY
                    && !hasExposedFace(context, pos)) {
                continue;
            }
            discovered.add(pos);
        }
        discovered.sort(targetComparator(context));
        return List.copyOf(discovered);
    }

    private Comparator<BlockPos> targetComparator(MaidActionContext context) {
        BlockPos live = context.maid().blockPosition();
        return Comparator.<BlockPos>comparingDouble(live::distSqr)
                .thenComparingInt(pos -> pos.getY())
                .thenComparingInt(pos -> pos.getX())
                .thenComparingInt(pos -> pos.getZ());
    }

    private boolean eligibleTarget(BlockPos pos, BlockState candidate) {
        return !harvestedPositions.contains(pos) && selector.test(candidate);
    }

    private ExcavateSegmentAction.Shape resolveShape(MaidActionContext context) {
        return AutonomousMiningStrategy.chooseShape(
                shapeMode, selectorDescription,
                context.maid().blockPosition().getY());
    }

    private void alignDirectionSweep(BlockPos live, Direction maidFacing) {
        if (directionSweepOrigin != null && directionSweepOrigin.equals(live)
                && !directionAttempts.isEmpty()) {
            activeDirection = directionAttempts.get(directionAttemptIndex);
            return;
        }
        Direction primary = activeDirection == null
                ? resolveDirection(directionMode, maidFacing) : activeDirection;
        directionAttempts = directionMode == DirectionMode.AUTO
                ? AutonomousMiningStrategy.directionAttempts(primary)
                : List.of(primary);
        directionAttemptIndex = 0;
        directionSweepOrigin = live.immutable();
        activeDirection = directionAttempts.getFirst();
    }

    private MaidActionTickResult alternateOrBlocked(
            MaidActionContext context, ActionEndReason reason, String message) {
        if (directionMode != DirectionMode.AUTO
                || directionSweepOrigin == null
                || directionAttemptIndex + 1 >= directionAttempts.size()
                || !directionSweepOrigin.equals(context.maid().blockPosition())) {
            return blocked(context, reason, message);
        }
        if (navigator != null) {
            navigator.stop(context);
            navigator = null;
        }
        terrainSearch = null;
        planningPurpose = null;
        directionAttemptIndex++;
        activeDirection = directionAttempts.get(directionAttemptIndex);
        stepFrom = null;
        stepTo = null;
        stepKind = null;
        currentStepCleared = 0;
        JsonObject detail = detail("alternate_direction");
        detail.addProperty("previous_failure",
                AutonomousMiningState.normalizeReason(message));
        detail.addProperty("direction_attempt", directionAttemptIndex + 1);
        if (state.phase() == AutonomousMiningState.Phase.SELECTING_SITE) {
            report(context, AutonomousMiningState.Phase.SELECTING_SITE, detail);
        } else {
            transition(context, AutonomousMiningState.Phase.SELECTING_SITE, detail);
        }
        return MaidActionTickResult.running();
    }

    static Direction resolveDirection(DirectionMode mode, Direction maidFacing) {
        Objects.requireNonNull(mode, "mode");
        if (mode.direction != null) {
            return mode.direction;
        }
        return maidFacing != null && maidFacing.getAxis().isHorizontal()
                ? maidFacing : Direction.NORTH;
    }

    private boolean isExactExcavationStep(MaidTerrainPath path) {
        if (path == null || path.steps().size() != 1
                || !path.target().equals(stepTo)) {
            return false;
        }
        MaidTerrainStep step = path.steps().getFirst();
        return step.kind() == stepKind
                && step.from().equals(stepFrom)
                && step.to().equals(stepTo);
    }

    private void transition(MaidActionContext context,
                            AutonomousMiningState.Phase phase,
                            JsonObject detail) {
        state.transitionTo(phase);
        report(context, phase, detail);
    }

    private void report(MaidActionContext context,
                        AutonomousMiningState.Phase phase,
                        JsonObject detail) {
        JsonObject report = detail == null ? new JsonObject() : detail.deepCopy();
        report.addProperty("phase", phase.name());
        report.addProperty("selector", selectorDescription);
        report.addProperty("collected_count", state.collectedCount());
        report.addProperty("target_count", state.targetCount());
        report.addProperty("segments_dug", state.segmentsDug());
        report.addProperty("cleared_blocks", state.clearedBlocks());
        report.addProperty("segment_steps", stepsInCurrentSegment);
        report.addProperty("segment_length", segmentLength);
        report.addProperty("planner_expanded_nodes", expandedNodes);
        if (activeDirection != null) {
            report.addProperty("direction", activeDirection.getName());
        }
        if (activeShape != null) {
            report.addProperty("shape", shapeWireName(activeShape));
        }
        syncCheckpoint(context, phase);
        context.execution().reportProgress(phase.wireName(), progress(), report);
    }

    private void syncCheckpoint(MaidActionContext context,
                                AutonomousMiningState.Phase phase) {
        if (!persistentSessionActive) {
            return;
        }
        MiningWorldModelSavedData model = MiningWorldModelSavedData.get(context.level());
        model.updateGeneration(context.execution().actionId(),
                context.execution().generation(), context.gameTime());
        model.updatePhase(context.execution().actionId(),
                phase.wireName(), context.gameTime());
        model.updateCounts(context.execution().actionId(),
                state.collectedCount(), state.segmentsDug(),
                state.clearedBlocks(), context.gameTime());
        model.updateWorkface(context.execution().actionId(),
                realEnd == null ? context.maid().blockPosition() : realEnd,
                context.gameTime());
        model.updateMainRoute(context.execution().actionId(),
                activeDirection == null ? directionMode.wireName
                        : activeDirection.getName(),
                activeShape == null ? shapeMode.wireName
                        : shapeWireName(activeShape),
                segmentLength, context.gameTime());
    }

    private void persistExternalStop(MaidActionContext context,
                                     ActionEndReason reason) {
        if (!persistentSessionActive || state.phase() == AutonomousMiningState.Phase.BLOCKED) {
            return;
        }
        syncCheckpoint(context, state.phase());
        MiningWorldModelSavedData model = MiningWorldModelSavedData.get(context.level());
        UUID actionId = context.execution().actionId();
        if (reason == ActionEndReason.ENTITY_UNLOADED
                || reason == ActionEndReason.SERVER_STATE_LOST) {
            model.setOperationStatus(actionId,
                    MiningWorldModelSavedData.OperationStatus.PAUSED,
                    context.gameTime());
            persistentSessionActive = false;
            return;
        }
        MiningWorldModelSavedData.OperationStatus terminalStatus = switch (
                reason == null ? ActionEndReason.INTERNAL_ERROR : reason) {
            case COMPLETED -> MiningWorldModelSavedData.OperationStatus.COMPLETED;
            case REQUESTED -> MiningWorldModelSavedData.OperationStatus.CANCELLED;
            case SUPERSEDED -> MiningWorldModelSavedData.OperationStatus.SUPERSEDED;
            default -> MiningWorldModelSavedData.OperationStatus.FAILED;
        };
        model.markTerminal(actionId, terminalStatus,
                reason == null ? "internal_error"
                        : reason.name().toLowerCase(Locale.ROOT),
                context.gameTime());
        persistentSessionActive = false;
    }

    private void restoreRoute(MiningWorldModelSavedData.OperationSnapshot snapshot) {
        if (directionMode == DirectionMode.AUTO
                && snapshot.mainDirection() != null
                && !"auto".equals(snapshot.mainDirection())) {
            try {
                activeDirection = DirectionMode.fromWireName(
                        snapshot.mainDirection()).direction;
            } catch (IllegalArgumentException ignored) {
                activeDirection = null;
            }
        }
        if (shapeMode == ShapeMode.AUTO && snapshot.shape() != null) {
            activeShape = switch (snapshot.shape()) {
                case "level" -> ExcavateSegmentAction.Shape.LEVEL;
                case "staircase_down" -> ExcavateSegmentAction.Shape.STAIRCASE_DOWN;
                default -> null;
            };
        }
    }

    private MaidActionTickResult blocked(MaidActionContext context,
                                         ActionEndReason reason,
                                         String blockedReason) {
        if (breaker != null) {
            breaker.stop(context);
            breaker = null;
        }
        if (navigator != null) {
            navigator.stop(context);
            navigator = null;
        }
        terrainSearch = null;
        planningPurpose = null;
        realEnd = context.maid().blockPosition().immutable();
        state.block(blockedReason);
        report(context, AutonomousMiningState.Phase.BLOCKED,
                detail(state.blockedReason()));
        if (persistentSessionActive) {
            MiningWorldModelSavedData.get(context.level()).markBlocked(
                    context.execution().actionId(), state.blockedReason(),
                    context.gameTime());
            persistentSessionActive = false;
        }
        return MaidActionTickResult.failed(reason,
                result(state.blockedReason(), true));
    }

    private MaidActionTickResult complete(MaidActionContext context,
                                          String completionReason) {
        state.complete();
        realEnd = context.maid().blockPosition().immutable();
        report(context, AutonomousMiningState.Phase.COMPLETED,
                detail(completionReason));
        if (persistentSessionActive) {
            MiningWorldModelSavedData.get(context.level()).markTerminal(
                    context.execution().actionId(),
                    MiningWorldModelSavedData.OperationStatus.COMPLETED,
                    "completed", context.gameTime());
            persistentSessionActive = false;
        }
        return MaidActionTickResult.succeeded(result("none", false));
    }

    private JsonObject result(String blockedReason, boolean decisionRequired) {
        JsonObject result = new JsonObject();
        result.addProperty("phase", state.phase().name());
        result.addProperty("collected_count", state.collectedCount());
        result.addProperty("target_count", state.targetCount());
        result.addProperty("segments_dug", state.segmentsDug());
        result.addProperty("cleared_blocks", state.clearedBlocks());
        result.add("origin", position(origin));
        result.add("real_end", position(realEnd));
        result.addProperty("blocked_reason",
                AutonomousMiningState.normalizeReason(blockedReason));
        result.addProperty("decision_required", decisionRequired);
        result.addProperty("selector", selectorDescription);
        result.addProperty("segment_length", segmentLength);
        result.addProperty("planner_expanded_nodes", expandedNodes);
        if (activeDirection != null) {
            result.addProperty("direction", activeDirection.getName());
        }
        if (activeShape != null) {
            result.addProperty("shape", shapeWireName(activeShape));
        }
        return result;
    }

    private double progress() {
        return state.goalReached() ? 1.0D
                : Math.min(0.99D,
                (double) state.collectedCount() / state.targetCount());
    }

    private JsonObject detail(String substage) {
        JsonObject detail = new JsonObject();
        detail.addProperty("substage", substage);
        return detail;
    }

    private JsonObject stepDetail(String substage) {
        JsonObject detail = detail(substage);
        if (stepFrom != null) {
            detail.add("from", position(stepFrom));
        }
        if (stepTo != null) {
            detail.add("to", position(stepTo));
        }
        return detail;
    }

    private JsonObject positionDetail(String substage, BlockPos pos) {
        JsonObject detail = detail(substage);
        detail.add("target", position(pos));
        return detail;
    }

    private static JsonObject position(BlockPos pos) {
        BlockPos safe = pos == null ? BlockPos.ZERO : pos;
        JsonObject json = new JsonObject();
        json.addProperty("x", safe.getX());
        json.addProperty("y", safe.getY());
        json.addProperty("z", safe.getZ());
        return json;
    }

    private static String shapeWireName(ExcavateSegmentAction.Shape shape) {
        return shape == ExcavateSegmentAction.Shape.STAIRCASE_DOWN
                ? "staircase_down" : "level";
    }

    private static boolean loadedBuildPosition(
            MaidActionContext context, BlockPos pos) {
        return pos.getY() >= context.level().getMinBuildHeight()
                && pos.getY() < context.level().getMaxBuildHeight()
                && context.level().hasChunkAt(pos);
    }

    private static boolean hasExposedFace(
            MaidActionContext context, BlockPos target) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = target.relative(direction);
            if (!loadedBuildPosition(context, adjacent)) {
                continue;
            }
            BlockState state = context.level().getBlockState(adjacent);
            if (state.getFluidState().isEmpty()
                    && state.getCollisionShape(context.level(), adjacent).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> standPositionCandidates(BlockPos target) {
        List<BlockPos> positions = new ArrayList<>(
                HORIZONTAL_DIRECTIONS.length * STAND_Y_OFFSETS.length);
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            BlockPos side = target.relative(direction);
            for (int yOffset : STAND_Y_OFFSETS) {
                positions.add(side.offset(0, yOffset, 0).immutable());
            }
        }
        return positions;
    }

    private static boolean potentialMiningStance(
            MaidTerrainWorldEvaluator evaluator,
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
        return verticalOffset >= -1 && verticalOffset <= 1
                && (verticalOffset != 1
                || evaluator.clearCost(target.above()) == 0.0D);
    }

    private static boolean canReachVisibleFace(
            MaidActionContext context, BlockPos target) {
        Vec3 eye = context.maid().getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);
        if (eye.distanceToSqr(center) > MAX_BREAK_DISTANCE_SQUARED) {
            return false;
        }
        BlockHitResult hit = context.level().clip(new ClipContext(
                eye, center, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, context.maid()));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target);
    }

    private static ToolCandidate findBestTool(
            EntityMaid maid, List<BlockState> states) {
        ToolCandidate best = toolCandidate(
                HandLease.HELD_TOOL_SLOT, maid.getMainHandItem(), states);
        IItemHandler inventory = maid.getAvailableBackpackInv();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ToolCandidate candidate = toolCandidate(
                    slot, inventory.getStackInSlot(slot), states);
            if (candidate != null
                    && (best == null || candidate.score() > best.score())) {
                best = candidate;
            }
        }
        return best;
    }

    private static ToolCandidate toolCandidate(
            int slot, ItemStack stack, List<BlockState> states) {
        double score = 0.0D;
        for (BlockState state : states) {
            if (state.requiresCorrectToolForDrops()
                    && !stack.isCorrectToolForDrops(state)) {
                return null;
            }
            score += Math.max(1.0F, stack.getDestroySpeed(state));
        }
        return new ToolCandidate(slot, score);
    }

    private static ActionEndReason supportEndReason(
            MaidTerrainWorldEvaluator.SupportAssessment assessment) {
        return switch (assessment) {
            case TARGET_CHANGED -> ActionEndReason.TARGET_CHANGED;
            default -> ActionEndReason.PATH_NOT_FOUND;
        };
    }

    private static ActionEndReason clearanceEndReason(
            MaidTerrainWorldEvaluator.ClearanceAssessment assessment) {
        return switch (assessment) {
            case PROTECTED_BLOCK -> ActionEndReason.BLOCK_PROTECTED;
            case TARGET_CHANGED -> ActionEndReason.TARGET_CHANGED;
            default -> ActionEndReason.PATH_NOT_FOUND;
        };
    }

    private static ActionEndReason defaultReason(ActionEndReason reason) {
        return reason == null ? ActionEndReason.INTERNAL_ERROR : reason;
    }

    private static String tickMessage(MaidTerrainNavigator.TickResult tick,
                                      String fallback) {
        return tick.detail().has("message")
                ? tick.detail().get("message").getAsString() : fallback;
    }

    private static JsonObject requireObject(JsonObject parent, String name) {
        if (!parent.has(name) || !parent.get(name).isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return parent.getAsJsonObject(name);
    }

    private static String requireString(JsonObject parent, String name) {
        if (!parent.has(name) || !parent.get(name).isJsonPrimitive()
                || !parent.getAsJsonPrimitive(name).isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return parent.get(name).getAsString();
    }

    private static String optionalString(
            JsonObject parent, String name, String fallback) {
        return parent.has(name) ? requireString(parent, name) : fallback;
    }

    private static int optionalInt(
            JsonObject parent, String name, int fallback) {
        if (!parent.has(name)) {
            return fallback;
        }
        if (!parent.get(name).isJsonPrimitive()
                || !parent.getAsJsonPrimitive(name).isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        double number = parent.get(name).getAsDouble();
        int value = parent.get(name).getAsInt();
        if (!Double.isFinite(number) || number != value) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return value;
    }

    private static double optionalDouble(
            JsonObject parent, String name, double fallback) {
        if (!parent.has(name)) {
            return fallback;
        }
        if (!parent.get(name).isJsonPrimitive()
                || !parent.getAsJsonPrimitive(name).isNumber()) {
            throw new IllegalArgumentException(name + " must be a number");
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
            throw new IllegalArgumentException(
                    "Invalid resource location: " + value);
        }
        return id;
    }

    private static JsonObject normalizedArgs(
            String selectorDescription, int targetCount,
            DirectionMode directionMode, ShapeMode shapeMode,
            int segmentLength, double speed, DiscoveryMode discoveryMode) {
        JsonObject selector = new JsonObject();
        if (selectorDescription.startsWith("block:")) {
            selector.addProperty("type", "block");
            selector.addProperty("id", selectorDescription.substring("block:".length()));
        } else if (selectorDescription.startsWith("tag:#")) {
            selector.addProperty("type", "tag");
            selector.addProperty("id", selectorDescription.substring("tag:#".length()));
        } else {
            throw new IllegalArgumentException(
                    "selectorDescription must use block: or tag:# prefix");
        }
        JsonObject args = new JsonObject();
        args.add("selector", selector);
        args.addProperty("target_count", targetCount);
        args.addProperty("direction", directionMode.wireName);
        args.addProperty("shape", shapeMode.wireName);
        args.addProperty("segment_length", segmentLength);
        args.addProperty("speed", speed);
        args.addProperty("discovery_mode", discoveryMode.wireName);
        return args;
    }

    public enum DirectionMode {
        AUTO(null, "auto"),
        NORTH(Direction.NORTH, "north"),
        SOUTH(Direction.SOUTH, "south"),
        EAST(Direction.EAST, "east"),
        WEST(Direction.WEST, "west");

        private final Direction direction;
        private final String wireName;

        DirectionMode(Direction direction, String wireName) {
            this.direction = direction;
            this.wireName = wireName;
        }

        static DirectionMode fromWireName(String value) {
            String normalized = value == null ? ""
                    : value.trim().toLowerCase(Locale.ROOT);
            for (DirectionMode mode : values()) {
                if (mode.wireName.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "direction must be auto, north, south, east or west");
        }
    }

    public enum ShapeMode {
        AUTO("auto"),
        LEVEL("level"),
        STAIRCASE_DOWN("staircase_down");

        private final String wireName;

        ShapeMode(String wireName) {
            this.wireName = wireName;
        }

        static ShapeMode fromWireName(String value) {
            String normalized = value == null ? ""
                    : value.trim().toLowerCase(Locale.ROOT);
            for (ShapeMode mode : values()) {
                if (mode.wireName.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "shape must be auto, level or staircase_down");
        }
    }

    public enum DiscoveryMode {
        LOADED_SCAN("loaded_scan"),
        EXPOSED_ONLY("exposed_only");

        private final String wireName;

        DiscoveryMode(String wireName) {
            this.wireName = wireName;
        }

        static DiscoveryMode fromWireName(String value) {
            String normalized = value == null ? ""
                    : value.trim().toLowerCase(Locale.ROOT);
            for (DiscoveryMode mode : values()) {
                if (mode.wireName.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "discovery_mode must be loaded_scan or exposed_only");
        }
    }

    private enum PlanningPurpose {
        EXCAVATION,
        HARVEST
    }

    private record ToolCandidate(int slot, double score) {
    }
}
