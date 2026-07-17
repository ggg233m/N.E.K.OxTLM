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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.ArrayDeque;
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
import java.util.function.Function;
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
    private static final int DRY_RELOCATION_RADIUS = 8;
    private static final int MAX_DRY_RELOCATION_PATH_ATTEMPTS = 32;
    private static final long DRY_RELOCATION_STUCK_TICKS = 80L;
    private static final long UNREACHABLE_ORE_RETRY_STEPS = 12L;
    private static final int MAX_DEFERRED_ORE_TARGETS = 256;
    private static final int RECENT_PASSAGE_MEMORY = 48;
    private static final int MAX_CONSECUTIVE_PASSAGE_STEPS = 24;
    private static final int NATURAL_PASSAGE_LOOKAHEAD = 6;
    private static final int MAX_HARVEST_NAVIGATION_REPLANS = 3;
    private static final Set<String> ALLOWED_ARGS = Set.of(
            "selector", "target_count", "direction", "shape",
            "segment_length", "speed", "discovery_mode",
            "placement_policy", "max_placements");
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
    private final PlacementPolicy placementPolicy;
    private final int maxPlacements;
    private final JsonObject normalizedArgs;
    private final MiningPlanner miningPlanner = new MiningPlanner();
    private AutonomousMiningState state;
    private final Set<BlockPos> harvestedPositions = new HashSet<>();

    private MaidVeinTracker veinTracker = MaidVeinTracker.unbounded();
    private BlockPos origin;
    private BlockPos realEnd;
    private Direction activeDirection;
    private ExcavateSegmentAction.Shape activeShape;
    private int stepsInCurrentSegment;
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
    private int placementsUsed;
    private int bridgeSupportsPlaced;
    private int waterSealsPlaced;
    private BlockPos dryRelocationTarget;
    private long dryRelocationStartedAt = Long.MIN_VALUE;
    private double dryRelocationWindowDistance;
    private long dryRelocationWindowStartedAt = Long.MIN_VALUE;
    private JsonObject lastNavigatorFailure;
    private int harvestNavigationReplans;
    private final Map<BlockPos, Long> deferredOreTargets = new LinkedHashMap<>();
    private final Set<HarvestStance> rejectedHarvestStances = new HashSet<>();
    private final ArrayDeque<BlockPos> recentPassagePositions = new ArrayDeque<>();
    private BlockPos lastPassageFrom;
    private BlockPos lastPassageTo;
    private long preventedImmediateBacktracks;
    private boolean followingNaturalPassage;
    private long naturalPassageSteps;
    private int consecutiveNaturalPassageSteps;
    private JsonObject lastPlannerDecision;
    private long plannerDecisionCount;
    private String selectedPlannerCandidateId = "";
    private BlockPos failedPlannerOrigin;
    private final Set<String> failedPlannerCandidates = new LinkedHashSet<>();
    private String lastPlannerFailure = "none";
    private boolean persistentSessionActive;
    private BackpackCapacitySummary lastBackpackCapacity;
    private String lastCapacityCheckMode = "none";
    private int lastCapacityCandidatesChecked;
    private int lastCapacityCandidatesStorable;

    public AutonomousMiningAction(Predicate<BlockState> selector,
                                  String selectorDescription,
                                  int targetCount,
                                  DirectionMode directionMode,
                                  ShapeMode shapeMode,
                                  int segmentLength,
                                  double speed,
                                  DiscoveryMode discoveryMode,
                                  PlacementPolicy placementPolicy,
                                  int maxPlacements) {
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
        this.placementPolicy = Objects.requireNonNull(
                placementPolicy, "placementPolicy");
        if (maxPlacements < 0 || maxPlacements > 4096) {
            throw new IllegalArgumentException(
                    "max_placements must be between 0 and 4096");
        }
        this.maxPlacements = maxPlacements;
        this.state = new AutonomousMiningState(targetCount);
        this.normalizedArgs = normalizedArgs(selectorDescription, targetCount,
                directionMode, shapeMode, segmentLength, speed, discoveryMode,
                placementPolicy, maxPlacements);
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
        int maxPlacements = optionalInt(args, "max_placements", 0);
        if (targetCount < 1) {
            throw new IllegalArgumentException("target_count must be at least 1");
        }
        if (segmentLength < 1 || segmentLength > 8) {
            throw new IllegalArgumentException("segment_length must be between 1 and 8");
        }
        if (speed < 0.4D || speed > 1.0D) {
            throw new IllegalArgumentException("speed must be between 0.4 and 1.0");
        }
        if (maxPlacements < 0 || maxPlacements > 4096) {
            throw new IllegalArgumentException(
                    "max_placements must be between 0 and 4096");
        }
        return new AutonomousMiningAction(selector, description, targetCount,
                DirectionMode.fromWireName(optionalString(args, "direction", "auto")),
                ShapeMode.fromWireName(optionalString(args, "shape", "auto")),
                segmentLength, speed,
                DiscoveryMode.fromWireName(optionalString(
                        args, "discovery_mode", "loaded_scan")),
                PlacementPolicy.fromWireName(optionalString(args,
                        "placement_policy", "disabled")),
                maxPlacements);
    }

    @Override
    public MaidActionKind kind() {
        return MaidActionKind.AUTONOMOUS_MINING;
    }

    @Override
    public Set<MaidActionResource> resources() {
        return placementPolicy.enabled()
                ? Set.of(MaidActionResource.MOVE, MaidActionResource.HAND,
                MaidActionResource.BREAK, MaidActionResource.PLACE)
                : Set.of(MaidActionResource.MOVE, MaidActionResource.HAND,
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
        veinTracker = MaidVeinTracker.restore(
                snapshot.veinMembers(), snapshot.veinHarvestedMembers());
        harvestedPositions.addAll(snapshot.veinHarvestedMembers());
        placementsUsed = Math.toIntExact(Math.min(
                Integer.MAX_VALUE, snapshot.placementsUsed()));
        bridgeSupportsPlaced = Math.toIntExact(Math.min(
                Integer.MAX_VALUE, snapshot.bridgeSupportsPlaced()));
        waterSealsPlaced = Math.toIntExact(Math.min(
                Integer.MAX_VALUE, snapshot.waterSealsPlaced()));
        origin = snapshot.originPos() == null
                ? context.maid().blockPosition().immutable()
                : snapshot.originPos();
        realEnd = context.maid().blockPosition().immutable();
        rememberPassagePosition(realEnd);
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
        dryRelocationTarget = null;
        dryRelocationStartedAt = Long.MIN_VALUE;
        dryRelocationWindowStartedAt = Long.MIN_VALUE;
        terrainSearch = null;
        planningPurpose = null;
        if (context != null && context.maid() != null) {
            context.maid().getNavigation().stop();
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
        if (dryRelocationTarget != null) {
            return advanceDryRelocation(context);
        }
        if (maidBodyTouchesWater(context, live)) {
            return beginDryRelocation(context, live);
        }
        if (!context.maid().onGround()) {
            return alternateOrBlocked(context, ActionEndReason.STUCK,
                    "maid_not_grounded_at_segment_origin");
        }
        PlannedStep planned = choosePlannedStep(context, live);
        if (planned == null) {
            String failure = "none".equals(lastPlannerFailure)
                    ? "all_planner_candidates_exhausted" : lastPlannerFailure;
            return blocked(context,
                    "no_building_material".equals(failure)
                            ? ActionEndReason.TOOL_NOT_FOUND
                            : ActionEndReason.PATH_NOT_FOUND,
                    failure);
        }
        activeDirection = planned.direction();
        activeShape = planned.shape();
        stepFrom = live;
        stepTo = planned.destination();
        stepKind = planned.kind();
        followingNaturalPassage = planned.candidate().naturalPassage();
        selectedPlannerCandidateId = planned.candidate().id();
        if (!followingNaturalPassage) {
            consecutiveNaturalPassageSteps = 0;
        }

        List<BlockPos> inspected = new ArrayList<>(
                ExcavateSegmentAction.clearanceFor(stepTo, activeShape));
        inspected.add(stepTo.below());
        for (BlockPos pos : inspected) {
            if (!loadedBuildPosition(context, pos)) {
                return alternateOrBlocked(context, ActionEndReason.PATH_NOT_FOUND,
                        "unloaded_excavation_edge");
            }
        }

        MaidTerrainWorldEvaluator evaluator = new MaidTerrainWorldEvaluator(
                context.level(), context.maid(), stepFrom, 3, 3, true,
                ignored -> true,
                ignored -> plannerConstructionAvailable(context));
        BlockPos supportPos = stepTo.below();
        BlockState support = context.level().getBlockState(supportPos);
        MaidTerrainWorldEvaluator.SupportAssessment supportAssessment =
                MaidTerrainWorldEvaluator.assessStandSupport(
                        context.level(), supportPos, support);
        if (!Double.isFinite(evaluator.supportCost(supportPos))) {
            return alternateOrBlocked(context, supportEndReason(supportAssessment),
                    supportAssessment.name());
        }
        for (BlockPos pos : ExcavateSegmentAction.clearanceFor(stepTo, activeShape)) {
            BlockState blockState = context.level().getBlockState(pos);
            MaidTerrainWorldEvaluator.ClearanceAssessment clearance =
                    MaidTerrainWorldEvaluator.assessClearance(
                            context.level(), pos, blockState);
            if (!Double.isFinite(evaluator.clearCost(pos))) {
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

        MaidTerrainPath naturalRun = buildNaturalPassageRun(context, planned);
        if (naturalRun.steps().size() > 1) {
            stepTo = naturalRun.target();
            terrainSearch = null;
            planningPurpose = PlanningPurpose.EXCAVATION;
            navigator = new MaidTerrainNavigator(
                    naturalRun, handLease, speed, true, constructionEnabled(),
                    remainingPlacementBudget());
            navigator.start(context);
            transition(context, AutonomousMiningState.Phase.EXCAVATING,
                    stepDetail("moving_natural_passage"));
            return MaidActionTickResult.running();
        }

        terrainSearch = new MaidTerrainSearch(stepFrom, Set.of(stepTo), evaluator,
                MAX_EXCAVATION_EXPANSIONS, EnumSet.of(stepKind));
        terrainGoalTargets = Map.of();
        planningPurpose = PlanningPurpose.EXCAVATION;
        transition(context, AutonomousMiningState.Phase.EXCAVATING,
                stepDetail("pathfinding"));
        return MaidActionTickResult.running();
    }

    /**
     * Native navigation can leave shallow water, while construction must never
     * replace the two cells currently occupied by the maid. Relocate to the
     * nearest reachable dry stance before choosing a tunnel direction instead
     * of misreporting those occupied cells as a failed seal placement.
     */
    private MaidActionTickResult beginDryRelocation(
            MaidActionContext context, BlockPos live) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos candidate : BlockPos.withinManhattan(
                live, DRY_RELOCATION_RADIUS, 3, DRY_RELOCATION_RADIUS)) {
            BlockPos immutable = candidate.immutable();
            if (!immutable.equals(live) && isDrySafeStance(context, immutable)) {
                candidates.add(immutable);
            }
        }
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(live))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));

        int attempts = 0;
        for (BlockPos candidate : candidates) {
            if (attempts++ >= MAX_DRY_RELOCATION_PATH_ATTEMPTS) {
                break;
            }
            Path path = context.maid().getNavigation().createPath(candidate, 0);
            if (path == null || path.getNodeCount() == 0 || !path.canReach()) {
                continue;
            }
            if (!context.maid().getNavigation().moveTo(path, speed)) {
                continue;
            }
            dryRelocationTarget = candidate;
            dryRelocationStartedAt = context.gameTime();
            dryRelocationWindowStartedAt = context.gameTime();
            dryRelocationWindowDistance = distanceToBlock(
                    context.maid(), candidate);
            context.maid().getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                    new BlockPosTracker(candidate));
            JsonObject detail = positionDetail(
                    "relocating_from_occupied_water", candidate);
            detail.addProperty("path_attempts", attempts);
            report(context, AutonomousMiningState.Phase.SELECTING_SITE, detail);
            return MaidActionTickResult.running();
        }
        return blocked(context, ActionEndReason.PATH_NOT_FOUND,
                "waterlogged_start_no_reachable_dry_stance");
    }

    private MaidActionTickResult advanceDryRelocation(MaidActionContext context) {
        BlockPos live = context.maid().blockPosition().immutable();
        if (!maidBodyTouchesWater(context, live) && isDrySafeStance(context, live)) {
            context.maid().getNavigation().stop();
            realEnd = live;
            dryRelocationTarget = null;
            dryRelocationStartedAt = Long.MIN_VALUE;
            dryRelocationWindowStartedAt = Long.MIN_VALUE;
            transition(context, AutonomousMiningState.Phase.SELECTING_SITE,
                    detail("dry_stance_reached"));
            return MaidActionTickResult.running();
        }
        if (context.maid().getNavigation().isDone()) {
            dryRelocationTarget = null;
            return blocked(context, ActionEndReason.PATH_NOT_FOUND,
                    "dry_relocation_path_finished_early");
        }
        double distance = distanceToBlock(context.maid(), dryRelocationTarget);
        if (context.gameTime() - dryRelocationWindowStartedAt
                >= DRY_RELOCATION_STUCK_TICKS) {
            if (dryRelocationWindowDistance - distance < 0.5D) {
                context.maid().getNavigation().stop();
                dryRelocationTarget = null;
                return blocked(context, ActionEndReason.STUCK,
                        "dry_relocation_made_no_progress");
            }
            dryRelocationWindowDistance = distance;
            dryRelocationWindowStartedAt = context.gameTime();
        }
        JsonObject detail = positionDetail(
                "relocating_from_occupied_water", dryRelocationTarget);
        detail.addProperty("distance", distance);
        detail.addProperty("elapsed_ticks", Math.max(
                0L, context.gameTime() - dryRelocationStartedAt));
        report(context, AutonomousMiningState.Phase.SELECTING_SITE, detail);
        return MaidActionTickResult.running();
    }

    private static boolean maidBodyTouchesWater(
            MaidActionContext context, BlockPos feet) {
        return context.level().getFluidState(feet).is(FluidTags.WATER)
                || context.level().getFluidState(feet.above()).is(FluidTags.WATER);
    }

    private static boolean isDrySafeStance(
            MaidActionContext context, BlockPos feet) {
        if (!loadedBuildPosition(context, feet)
                || !loadedBuildPosition(context, feet.above())
                || !loadedBuildPosition(context, feet.below())) {
            return false;
        }
        BlockState feetState = context.level().getBlockState(feet);
        BlockState headState = context.level().getBlockState(feet.above());
        BlockState support = context.level().getBlockState(feet.below());
        return feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && feetState.getCollisionShape(context.level(), feet).isEmpty()
                && headState.getCollisionShape(
                context.level(), feet.above()).isEmpty()
                && MaidTerrainWorldEvaluator.isSafeStandSupport(
                context.level(), feet.below(), support);
    }

    private static double distanceToBlock(EntityMaid maid, BlockPos pos) {
        return maid.position().distanceTo(Vec3.atBottomCenterOf(pos));
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
            navigator = new MaidTerrainNavigator(
                    path, handLease, speed, true, constructionEnabled(),
                    remainingPlacementBudget());
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
        recordPlacements(navigator);
        for (MaidTerrainNavigator.ClearedBlock event : navigator.drainClearedBlocks()) {
            recordRouteClearedBlock(event.pos(), event.state());
            state.recordRouteClearance(1);
        }
        for (BlockPos crossed : navigator.drainCompletedStepPositions()) {
            recordPassageTransition(crossed);
            state.recordExcavationStep(0);
            rememberPassagePosition(crossed);
            stepsInCurrentSegment++;
            if (followingNaturalPassage) {
                naturalPassageSteps++;
                consecutiveNaturalPassageSteps++;
            }
        }
        if (tick.outcome() == MaidTerrainNavigator.Outcome.FAILED) {
            lastNavigatorFailure = tick.detail().deepCopy();
            navigator = null;
            return alternateOrBlocked(context, defaultReason(tick.reason()),
                    tickMessage(tick, "excavation_failed"));
        }
        if (tick.outcome() == MaidTerrainNavigator.Outcome.ARRIVED) {
            navigator = null;
            planningPurpose = null;
            realEnd = context.maid().blockPosition().immutable();
            failedPlannerOrigin = realEnd;
            failedPlannerCandidates.clear();
            selectedPlannerCandidateId = "";
            lastPlannerFailure = "none";
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
            veinTracker.pruneUnharvested(pos -> !loadedBuildPosition(context, pos)
                    || selector.test(context.level().getBlockState(pos)));
            List<BlockPos> connected = veinTracker.retainConnected(
                    discovered, targetComparator(context));
            if (!connected.isEmpty()) {
                discovered = connected;
            } else {
                if (!veinTracker.pendingMembers().isEmpty()) {
                    boolean unloaded = veinTracker.pendingMembers().stream()
                            .anyMatch(pos -> !loadedBuildPosition(context, pos));
                    if (unloaded) {
                        return blocked(context, ActionEndReason.ENTITY_UNLOADED,
                                "committed_vein_member_unloaded");
                    }
                    return blocked(context, ActionEndReason.PATH_NOT_FOUND,
                            "committed_vein_remaining_unreachable");
                }
                if (committedVeinTouchesUnloadedChunk(context)) {
                    return blocked(context, ActionEndReason.ENTITY_UNLOADED,
                            "committed_vein_boundary_unloaded");
                }
                switch (reduceExhaustedCommitment(state.goalReached())) {
                    case COMPLETE_CURRENT -> {
                        // Keep the exhausted commitment until complete()
                        // snapshots vein_complete, but hide unrelated veins.
                        discovered = List.of();
                    }
                    case RELEASE_FOR_NEXT ->
                            veinTracker = MaidVeinTracker.unbounded();
                }
            }
        }
        // 已锁定的矿脉继续挖完，不因容量门禁中途打断；但锁定矿脉可能在本
        // tick 恰好耗尽并解锁，因此必须在开始下一段工作前重新执行容量门禁。
        boolean capacityAvailable = true;
        if (!veinTracker.locked() && !state.goalReached()) {
            IItemHandler backpack = context.maid().getAvailableBackpackInv();
            lastBackpackCapacity = summarizeBackpackCapacity(backpack);
            List<BlockPos> capacityTargets = discovered.stream()
                    .limit(MAX_CANDIDATES)
                    .toList();
            if (capacityTargets.isEmpty()) {
                lastCapacityCheckMode = "physical_stack_capacity";
                lastCapacityCandidatesChecked = 0;
                lastCapacityCandidatesStorable = 0;
                capacityAvailable = !lastBackpackCapacity.full();
            } else {
                lastCapacityCheckMode = "target_drop_simulation";
                lastCapacityCandidatesChecked = capacityTargets.size();
                discovered = filterStorableTargets(context, capacityTargets);
                lastCapacityCandidatesStorable = discovered.size();
                capacityAvailable = !discovered.isEmpty();
            }
        }
        ScanDecision decision = reduceScanDecision(new ScanDecisionFacts(
                veinTracker.locked(), state.goalReached(),
                !discovered.isEmpty(), capacityAvailable));
        return switch (decision) {
            case COMPLETE -> complete(context, veinTracker.locked()
                    ? "connected_vein_exhausted" : "target_minimum_reached");
            case CONTINUE -> {
                transition(context, AutonomousMiningState.Phase.CONTINUING,
                        detail("no_target_found"));
                yield MaidActionTickResult.running();
            }
            case BLOCK_CAPACITY -> blocked(context,
                    ActionEndReason.SAFETY_PREEMPTED, "backpack_full");
            case HARVEST -> {
                List<BlockPos> candidates = veinTracker.locked()
                        ? discovered
                        : discovered.stream().limit(MAX_CANDIDATES).toList();
                yield planHarvestApproach(context, candidates);
            }
        };
    }

    static ScanDecision reduceScanDecision(ScanDecisionFacts facts) {
        Objects.requireNonNull(facts, "facts");
        // A committed vein always wins while it still has targets: reaching
        // the minimum or filling the backpack must not leave a half-dug vein.
        if (facts.veinLocked() && facts.targetsAvailable()) {
            return ScanDecision.HARVEST;
        }
        if (facts.goalReached()) {
            return ScanDecision.COMPLETE;
        }
        if (!facts.capacityAvailable()) {
            return ScanDecision.BLOCK_CAPACITY;
        }
        return facts.targetsAvailable()
                ? ScanDecision.HARVEST : ScanDecision.CONTINUE;
    }

    static ExhaustedCommitmentDecision reduceExhaustedCommitment(
            boolean goalReached) {
        return goalReached
                ? ExhaustedCommitmentDecision.COMPLETE_CURRENT
                : ExhaustedCommitmentDecision.RELEASE_FOR_NEXT;
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
            if (!rejectedHarvestStances.contains(new HarvestStance(start, target))
                    && canReachVisibleFace(context, target)
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
                ignored -> true, ignored -> constructionEnabled());
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
                if (!rejectedHarvestStances.contains(
                        new HarvestStance(stand, target))
                        && potentialMiningStance(evaluator, stand, target)) {
                    goals.putIfAbsent(stand.immutable(), target.immutable());
                }
            }
        }
        if (goals.isEmpty()) {
            if (veinTracker.locked()) {
                return blocked(context, ActionEndReason.PATH_NOT_FOUND,
                        "committed_vein_has_no_safe_mining_stance");
            }
            deferOreTargets(candidates);
            transition(context, AutonomousMiningState.Phase.CONTINUING,
                    deferredOreDetail("no_safe_mining_stance", candidates.size()));
            return MaidActionTickResult.running();
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
                Set<BlockPos> unreachable = new LinkedHashSet<>(
                        terrainGoalTargets.values());
                if (veinTracker.locked()) {
                    terrainSearch = null;
                    terrainGoalTargets = Map.of();
                    planningPurpose = null;
                    currentTarget = null;
                    expectedTargetState = null;
                    return blocked(context, ActionEndReason.PATH_NOT_FOUND,
                            "committed_vein_path_not_found");
                }
                deferOreTargets(unreachable);
                terrainSearch = null;
                terrainGoalTargets = Map.of();
                planningPurpose = null;
                currentTarget = null;
                expectedTargetState = null;
                transition(context, AutonomousMiningState.Phase.CONTINUING,
                        deferredOreDetail(
                                "ore_path_not_found", unreachable.size()));
                return MaidActionTickResult.running();
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
            navigator = new MaidTerrainNavigator(
                    path, handLease, speed, true, constructionEnabled(),
                    remainingPlacementBudget());
            navigator.start(context);
            report(context, AutonomousMiningState.Phase.HARVESTING,
                    positionDetail("approaching", currentTarget));
            return MaidActionTickResult.running();
        }
        if (navigator != null) {
            MaidTerrainNavigator.TickResult tick = navigator.tick(context);
            recordPlacements(navigator);
            int cleared = 0;
            for (MaidTerrainNavigator.ClearedBlock event : navigator.drainClearedBlocks()) {
                recordRouteClearedBlock(event.pos(), event.state());
                cleared++;
            }
            state.recordRouteClearance(cleared);
            if (tick.outcome() == MaidTerrainNavigator.Outcome.FAILED) {
                lastNavigatorFailure = tick.detail().deepCopy();
                if (tick.replanRecommended()
                        && harvestNavigationReplans
                        < MAX_HARVEST_NAVIGATION_REPLANS
                        && currentTarget != null
                        && eligibleTarget(currentTarget,
                        context.level().getBlockState(currentTarget))) {
                    harvestNavigationReplans++;
                    navigator = null;
                    planningPurpose = null;
                    currentTarget = null;
                    expectedTargetState = null;
                    JsonObject detail = detail("ore_approach_replan");
                    detail.addProperty("replan_attempt", harvestNavigationReplans);
                    detail.addProperty("replan_limit",
                            MAX_HARVEST_NAVIGATION_REPLANS);
                    transition(context, AutonomousMiningState.Phase.SCANNING, detail);
                    return MaidActionTickResult.running();
                }
                return blocked(context, defaultReason(tick.reason()),
                        tickMessage(tick, "ore_approach_failed"));
            }
            if (tick.outcome() == MaidTerrainNavigator.Outcome.ARRIVED) {
                navigator = null;
                expectedTargetState = context.level().getBlockState(currentTarget);
                if (!eligibleTarget(currentTarget, expectedTargetState)) {
                    if (harvestedPositions.contains(currentTarget)) {
                        currentTarget = null;
                        expectedTargetState = null;
                        planningPurpose = null;
                        transition(context, AutonomousMiningState.Phase.SCANNING,
                                detail("route_harvested_target"));
                        return MaidActionTickResult.running();
                    }
                    return blocked(context, ActionEndReason.TARGET_CHANGED,
                            "target_changed");
                }
                if (!canReachVisibleFace(context, currentTarget)) {
                    return reselectHarvestStance(context,
                            "ore_stance_not_visible_after_arrival");
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
            String message = tick.detail().has("message")
                    ? tick.detail().get("message").getAsString()
                    : "ore_break_failed";
            if (isPositionalReachFailure(message)) {
                return reselectHarvestStance(context, message);
            }
            return blocked(context, defaultReason(tick.reason()),
                    message);
        }
        if (tick.outcome() == MaidProgressiveBlockBreaker.Outcome.CLEARED) {
            breaker = null;
            harvestedPositions.add(currentTarget.immutable());
            veinTracker.rememberHarvested(currentTarget);
            state.recordHarvest();
            harvestNavigationReplans = 0;
            rejectedHarvestStances.removeIf(
                    stance -> stance.target().equals(currentTarget));
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
        long excavationStep = state.segmentsDug();
        deferredOreTargets.entrySet().removeIf(
                entry -> entry.getValue() <= excavationStep);
        Set<BlockPos> discovered = new LinkedHashSet<>();
        for (BlockPos mutable : BlockPos.betweenClosed(
                center.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                center.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            BlockPos pos = mutable.immutable();
            if (harvestedPositions.contains(pos)
                    || (!veinTracker.locked() && deferredOreTargets.containsKey(pos))
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
        if (veinTracker.locked()) {
            for (BlockPos pos : veinTracker.pendingMembers()) {
                if (!loadedBuildPosition(context, pos)
                        || harvestedPositions.contains(pos)) {
                    continue;
                }
                BlockState state = context.level().getBlockState(pos);
                if (selector.test(state)
                        && (discoveryMode != DiscoveryMode.EXPOSED_ONLY
                        || hasExposedFace(context, pos))) {
                    discovered.add(pos.immutable());
                }
            }
        }
        return discovered.stream().sorted(targetComparator(context)).toList();
    }

    private void deferOreTargets(Iterable<BlockPos> targets) {
        long retryAt = Math.addExact(
                state.segmentsDug(), UNREACHABLE_ORE_RETRY_STEPS);
        for (BlockPos target : targets) {
            if (deferredOreTargets.size() >= MAX_DEFERRED_ORE_TARGETS
                    && !deferredOreTargets.containsKey(target)) {
                BlockPos oldest = deferredOreTargets.keySet().iterator().next();
                deferredOreTargets.remove(oldest);
            }
            deferredOreTargets.put(target.immutable(), retryAt);
        }
    }

    private MaidActionTickResult reselectHarvestStance(
            MaidActionContext context, String reason) {
        if (currentTarget == null) {
            return blocked(context, ActionEndReason.INTERNAL_ERROR,
                    "harvest_target_missing_during_stance_reselect");
        }
        rejectedHarvestStances.add(new HarvestStance(
                context.maid().blockPosition(), currentTarget));
        harvestNavigationReplans++;
        if (harvestNavigationReplans > MAX_HARVEST_NAVIGATION_REPLANS) {
            if (veinTracker.locked()) {
                return blocked(context, ActionEndReason.PATH_NOT_FOUND,
                        "committed_vein_has_no_visible_reachable_stance");
            }
            deferOreTargets(List.of(currentTarget));
            currentTarget = null;
            expectedTargetState = null;
            planningPurpose = null;
            transition(context, AutonomousMiningState.Phase.CONTINUING,
                    deferredOreDetail(reason, 1));
            return MaidActionTickResult.running();
        }
        currentTarget = null;
        expectedTargetState = null;
        planningPurpose = null;
        JsonObject detail = detail("reselecting_ore_stance");
        detail.addProperty("reason", reason);
        detail.addProperty("replan_attempt", harvestNavigationReplans);
        transition(context, AutonomousMiningState.Phase.SCANNING, detail);
        return MaidActionTickResult.running();
    }

    static boolean isPositionalReachFailure(String message) {
        return "terrain_block_is_not_visible_or_in_reach".equals(message);
    }

    private JsonObject deferredOreDetail(String reason, int targetCount) {
        JsonObject detail = detail("ore_candidates_temporarily_deferred");
        detail.addProperty("defer_reason", reason);
        detail.addProperty("deferred_targets", targetCount);
        detail.addProperty("retry_after_excavation_steps",
                UNREACHABLE_ORE_RETRY_STEPS);
        return detail;
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

    /** Records a committed route break without treating non-target ore as a goal. */
    boolean recordRouteClearedBlock(BlockPos pos, BlockState originalState) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(originalState, "originalState");
        if (!selector.test(originalState)) {
            return false;
        }
        BlockPos harvested = pos.immutable();
        if (!harvestedPositions.add(harvested)) {
            return false;
        }
        veinTracker.rememberHarvested(harvested);
        state.recordHarvest();
        return true;
    }

    private void recordPlacements(MaidTerrainNavigator activeNavigator) {
        for (MaidTerrainNavigator.PlacedBlock placed
                : activeNavigator.drainPlacedBlocks()) {
            placementsUsed++;
            if (placed.purpose() == MaidTerrainBuilder.Purpose.SEAL_FLUID) {
                waterSealsPlaced++;
            } else {
                bridgeSupportsPlaced++;
            }
        }
    }

    private boolean constructionEnabled() {
        return placementPolicy.enabled();
    }

    private int remainingPlacementBudget() {
        if (!placementPolicy.enabled()) {
            return 0;
        }
        if (maxPlacements == 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, maxPlacements - placementsUsed);
    }

    private boolean plannerConstructionAvailable(MaidActionContext context) {
        return constructionEnabled()
                && remainingPlacementBudget() > 0
                && MaidTerrainBuilder.chooseMaterial(context.maid()).isPresent();
    }

    /** Builds one continuous native route through an already-open level corridor. */
    private MaidTerrainPath buildNaturalPassageRun(
            MaidActionContext context, PlannedStep first) {
        if (!first.candidate().naturalPassage()
                || first.shape() != ExcavateSegmentAction.Shape.LEVEL
                || first.kind() != MaidTerrainStep.Kind.TRAVERSE) {
            return new MaidTerrainPath(List.of(), first.destination(), 0.0D, 0);
        }
        int remainingInSegment = Math.max(1, segmentLength - stepsInCurrentSegment);
        int limit = Math.min(NATURAL_PASSAGE_LOOKAHEAD, remainingInSegment);
        List<MaidTerrainStep> steps = new ArrayList<>(limit);
        BlockPos from = context.maid().blockPosition().immutable();
        for (int index = 0; index < limit; index++) {
            BlockPos to = from.relative(first.direction()).immutable();
            if (!isDrySafeStance(context, to)
                    || recentPassagePositions.contains(to)) {
                break;
            }
            List<BlockPos> clearance = List.of(to, to.above());
            steps.add(new MaidTerrainStep(MaidTerrainStep.Kind.TRAVERSE,
                    from, to, clearance, List.of(), 1.0D));
            from = to;
        }
        if (steps.isEmpty()) {
            return new MaidTerrainPath(List.of(), first.destination(), 0.0D, 0);
        }
        return new MaidTerrainPath(steps, from, steps.size(), 0);
    }

    private PlannedStep choosePlannedStep(
            MaidActionContext context, BlockPos live) {
        alignPassageTransition(live);
        if (failedPlannerOrigin == null || !failedPlannerOrigin.equals(live)) {
            failedPlannerOrigin = live.immutable();
            failedPlannerCandidates.clear();
            lastPlannerFailure = "none";
        }
        Direction preferred = activeDirection == null
                ? resolveDirection(directionMode, context.maid().getDirection())
                : activeDirection;
        List<Direction> directions = directionMode == DirectionMode.AUTO
                ? AutonomousMiningStrategy.directionAttempts(preferred)
                : List.of(directionMode.direction);
        List<ExcavateSegmentAction.Shape> shapes = plannerShapes(live.getY());
        MaidTerrainWorldEvaluator evaluator = new MaidTerrainWorldEvaluator(
                context.level(), context.maid(), live, 3, 3, true,
                ignored -> true,
                ignored -> plannerConstructionAvailable(context));

        List<MiningPlanner.Candidate> candidates = new ArrayList<>();
        Map<String, PlannedStep> steps = new LinkedHashMap<>();
        int attempted = 0;
        for (int directionIndex = 0;
             directionIndex < directions.size(); directionIndex++) {
            Direction direction = directions.get(directionIndex);
            for (ExcavateSegmentAction.Shape shape : shapes) {
                attempted++;
                PlannedStep step = evaluatePlannerCandidate(
                        context, evaluator, live, preferred,
                        direction, directionIndex, shape);
                if (step == null
                        || failedPlannerCandidates.contains(
                        step.candidate().id())) {
                    continue;
                }
                if (isImmediateBacktrack(live, step.destination())) {
                    preventedImmediateBacktracks++;
                    continue;
                }
                candidates.add(step.candidate());
                steps.put(step.candidate().id(), step);
            }
        }

        MiningPlanner.Decision decision = miningPlanner.plan(candidates);
        lastPlannerDecision = plannerDecisionJson(decision, attempted);
        plannerDecisionCount++;
        MiningPlanner.Candidate selected = decision.selected().orElse(null);
        if (selected == null && attempted > 0 && lastPassageFrom != null) {
            lastPlannerFailure = "only_immediate_backtrack_or_unsafe_routes";
        }
        return selected == null ? null : steps.get(selected.id());
    }

    private List<ExcavateSegmentAction.Shape> plannerShapes(int currentY) {
        if (shapeMode == ShapeMode.LEVEL) {
            return List.of(ExcavateSegmentAction.Shape.LEVEL);
        }
        if (shapeMode == ShapeMode.STAIRCASE_DOWN) {
            return List.of(ExcavateSegmentAction.Shape.STAIRCASE_DOWN);
        }
        int workingY = AutonomousMiningStrategy.targetY(selectorDescription)
                .orElse(currentY);
        return currentY > workingY
                ? List.of(ExcavateSegmentAction.Shape.STAIRCASE_DOWN,
                ExcavateSegmentAction.Shape.LEVEL)
                : List.of(ExcavateSegmentAction.Shape.LEVEL);
    }

    private PlannedStep evaluatePlannerCandidate(
            MaidActionContext context,
            MaidTerrainWorldEvaluator evaluator,
            BlockPos live,
            Direction preferred,
            Direction direction,
            int directionIndex,
            ExcavateSegmentAction.Shape shape) {
        BlockPos destination = ExcavateSegmentAction.nextPosition(
                live, direction, shape).immutable();
        BlockPos support = destination.below();
        double supportCost = evaluator.supportCost(support);
        if (!Double.isFinite(supportCost)) {
            return null;
        }

        double breakCost = 0.0D;
        double constructionCost = supportCost > 0.0D ? 1.0D : 0.0D;
        boolean natural = supportCost == 0.0D;
        for (BlockPos clearance : ExcavateSegmentAction.clearanceFor(
                destination, shape)) {
            double cost = evaluator.clearCost(clearance);
            if (!Double.isFinite(cost)) {
                return null;
            }
            MaidTerrainWorldEvaluator.ClearanceAssessment assessment =
                    MaidTerrainWorldEvaluator.assessClearance(
                            context.level(), clearance,
                            context.level().getBlockState(clearance));
            if (assessment
                    == MaidTerrainWorldEvaluator.ClearanceAssessment.WATER_HAZARD) {
                if (context.level().getFluidState(clearance).is(FluidTags.WATER)) {
                    constructionCost += cost;
                } else {
                    breakCost += cost;
                    constructionCost += 1.0D;
                }
            } else {
                breakCost += cost;
            }
            natural &= cost == 0.0D;
        }

        int currentY = live.getY();
        int workingY = AutonomousMiningStrategy.targetY(selectorDescription)
                .orElse(currentY);
        boolean towardLayer = Math.abs(destination.getY() - workingY)
                < Math.abs(currentY - workingY);
        boolean recentlyVisited = recentPassagePositions.contains(destination);
        double steeringRisk = direction.equals(preferred) ? 0.0D
                : direction.equals(preferred.getOpposite()) ? 0.15D
                : 0.03D * Math.max(1, directionIndex);
        if (natural
                && consecutiveNaturalPassageSteps
                >= MAX_CONSECUTIVE_PASSAGE_STEPS) {
            steeringRisk += 1.0D;
        }
        String id = direction.getName() + ":" + shapeWireName(shape);
        MiningPlanner.Candidate candidate = MiningPlanner.Candidate.builder(
                        id, direction.getName(), shapeWireName(shape))
                .breakCost(breakCost)
                .supportCost(supportCost)
                .constructionCost(constructionCost)
                .risk(steeringRisk)
                .towardTargetLayer(towardLayer)
                .naturalPassage(natural)
                .recentlyVisited(recentlyVisited)
                .build();
        MaidTerrainStep.Kind kind = shape
                == ExcavateSegmentAction.Shape.STAIRCASE_DOWN
                ? MaidTerrainStep.Kind.DESCEND : MaidTerrainStep.Kind.TRAVERSE;
        return new PlannedStep(direction, shape, destination, kind, candidate);
    }

    private JsonObject plannerDecisionJson(
            MiningPlanner.Decision decision, int attempted) {
        JsonObject json = new JsonObject();
        json.addProperty("mode", "cost_based");
        json.addProperty("policy", decision.diagnostics().policy());
        json.addProperty("reason", decision.diagnostics().reason());
        json.addProperty("candidates_evaluated", attempted);
        json.addProperty("candidates_feasible",
                decision.diagnostics().ranking().size());
        json.addProperty("candidates_rejected",
                Math.max(0, attempted - decision.diagnostics().ranking().size()));
        json.addProperty("previous_failure", lastPlannerFailure);
        decision.selectedScore().ifPresent(selected -> {
            MiningPlanner.Candidate candidate = selected.candidate();
            MiningPlanner.ScoreBreakdown score = selected.score();
            json.addProperty("choice", candidate.naturalPassage()
                    ? "natural_passage" : candidate.constructionCost() > 0.0D
                    ? "terrain_construction" : "excavation");
            json.addProperty("candidate_id", candidate.id());
            json.addProperty("direction", candidate.direction());
            json.addProperty("shape", candidate.shape());
            json.addProperty("total_cost", score.totalScore());
            JsonObject costs = new JsonObject();
            costs.addProperty("estimated_time", score.estimatedTime());
            costs.addProperty("risk", score.riskPenalty());
            costs.addProperty("material", score.materialPenalty());
            costs.addProperty("preference", score.preferenceAdjustment());
            json.add("costs", costs);
        });
        return json;
    }

    private void rememberPassagePosition(BlockPos pos) {
        BlockPos immutable = pos.immutable();
        recentPassagePositions.remove(immutable);
        recentPassagePositions.addLast(immutable);
        while (recentPassagePositions.size() > RECENT_PASSAGE_MEMORY) {
            recentPassagePositions.removeFirst();
        }
    }

    private void alignPassageTransition(BlockPos live) {
        BlockPos immutable = live.immutable();
        if (lastPassageTo == null || !lastPassageTo.equals(immutable)) {
            lastPassageFrom = null;
            lastPassageTo = immutable;
        }
    }

    private void recordPassageTransition(BlockPos crossed) {
        BlockPos immutable = crossed.immutable();
        if (lastPassageTo == null) {
            lastPassageTo = immutable;
            return;
        }
        if (!lastPassageTo.equals(immutable)) {
            lastPassageFrom = lastPassageTo;
            lastPassageTo = immutable;
        }
    }

    private boolean isImmediateBacktrack(BlockPos live, BlockPos destination) {
        return isImmediateBacktrack(
                lastPassageFrom, lastPassageTo, live, destination);
    }

    static boolean isImmediateBacktrack(
            BlockPos lastFrom, BlockPos lastTo,
            BlockPos live, BlockPos destination) {
        return lastFrom != null && lastTo != null
                && lastTo.equals(live) && lastFrom.equals(destination);
    }

    private MaidActionTickResult alternateOrBlocked(
            MaidActionContext context, ActionEndReason reason, String message) {
        if (directionMode != DirectionMode.AUTO
                && shapeMode != ShapeMode.AUTO) {
            return blocked(context, reason, message);
        }
        if (navigator != null) {
            navigator.stop(context);
            navigator = null;
        }
        terrainSearch = null;
        planningPurpose = null;
        BlockPos live = context.maid().blockPosition().immutable();
        if (failedPlannerOrigin == null || !failedPlannerOrigin.equals(live)) {
            failedPlannerOrigin = live;
            failedPlannerCandidates.clear();
        }
        if (!selectedPlannerCandidateId.isBlank()) {
            failedPlannerCandidates.add(selectedPlannerCandidateId);
        }
        lastPlannerFailure = AutonomousMiningState.normalizeReason(message);
        stepFrom = null;
        stepTo = null;
        stepKind = null;
        JsonObject detail = detail("planner_replan");
        detail.addProperty("previous_failure",
                AutonomousMiningState.normalizeReason(message));
        detail.addProperty("rejected_candidate", selectedPlannerCandidateId);
        detail.addProperty("rejected_candidates_at_origin",
                failedPlannerCandidates.size());
        transition(context, AutonomousMiningState.Phase.SELECTING_SITE, detail);
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
        addVeinStatus(report);
        report.addProperty("segments_dug", state.segmentsDug());
        report.addProperty("cleared_blocks", state.clearedBlocks());
        report.addProperty("segment_steps", stepsInCurrentSegment);
        report.addProperty("segment_length", segmentLength);
        report.addProperty("planner_expanded_nodes", expandedNodes);
        report.addProperty("placements_used", placementsUsed);
        report.addProperty("bridge_supports_placed", bridgeSupportsPlaced);
        report.addProperty("water_seals_placed", waterSealsPlaced);
        report.addProperty("current_y", context.maid().blockPosition().getY());
        AutonomousMiningStrategy.targetY(selectorDescription).ifPresent(
                targetY -> report.addProperty("working_y", targetY));
        report.addProperty("deferred_ore_targets", deferredOreTargets.size());
        report.addProperty("route_choice", followingNaturalPassage
                ? "natural_passage" : "excavation");
        report.addProperty("natural_passage_steps", naturalPassageSteps);
        report.addProperty("planner_decisions", plannerDecisionCount);
        report.addProperty("prevented_immediate_backtracks",
                preventedImmediateBacktracks);
        report.addProperty("harvest_navigation_replans", harvestNavigationReplans);
        report.addProperty("rejected_harvest_stances",
                rejectedHarvestStances.size());
        addBackpackCapacityStatus(report);
        if (lastPlannerDecision != null) {
            report.add("planner_decision", lastPlannerDecision.deepCopy());
        }
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
        model.updateConstructionCounts(context.execution().actionId(),
                placementsUsed, bridgeSupportsPlaced, waterSealsPlaced,
                context.gameTime());
        model.updateVeinState(context.execution().actionId(),
                veinTracker.members(), veinTracker.harvestedMembers(),
                context.gameTime());
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
        addVeinStatus(result);
        result.addProperty("segments_dug", state.segmentsDug());
        result.addProperty("cleared_blocks", state.clearedBlocks());
        result.add("origin", position(origin));
        result.add("real_end", position(realEnd));
        String normalizedReason = AutonomousMiningState.normalizeReason(blockedReason);
        result.addProperty("blocked_reason", normalizedReason);
        // 容量感知重启事实:Java 只提供权威计数,Python 据此构造完整 restart_parameters。
        // remaining_target_count 恒为非负;collected 已超 target 时为 0。
        RestartProjection restart = restartProjection(
                normalizedReason, state.targetCount(), state.collectedCount());
        result.addProperty("remaining_target_count",
                restart.remainingTargetCount());
        // restart_supported 只是 Java 对 backpack_full 的直接重启能力提示。
        // Python 会独立校验此布尔值，并按其他 blocked 原因的安全策略决定是否
        // 能生成带前置条件的重启模板；false 不代表 remaining 进度事实无效。
        result.addProperty("restart_supported", restart.restartSupported());
        result.addProperty("decision_required", decisionRequired);
        result.addProperty("selector", selectorDescription);
        result.addProperty("segment_length", segmentLength);
        result.addProperty("planner_expanded_nodes", expandedNodes);
        result.addProperty("placement_policy", placementPolicy.wireName);
        result.addProperty("max_placements", maxPlacements);
        result.addProperty("placements_used", placementsUsed);
        result.addProperty("bridge_supports_placed", bridgeSupportsPlaced);
        result.addProperty("water_seals_placed", waterSealsPlaced);
        AutonomousMiningStrategy.targetY(selectorDescription).ifPresent(
                targetY -> result.addProperty("working_y", targetY));
        result.addProperty("deferred_ore_targets", deferredOreTargets.size());
        result.addProperty("natural_passage_steps", naturalPassageSteps);
        result.addProperty("planner_decisions", plannerDecisionCount);
        result.addProperty("prevented_immediate_backtracks",
                preventedImmediateBacktracks);
        result.addProperty("harvest_navigation_replans", harvestNavigationReplans);
        result.addProperty("rejected_harvest_stances",
                rejectedHarvestStances.size());
        addBackpackCapacityStatus(result);
        if (lastPlannerDecision != null) {
            result.add("last_planner_decision",
                    lastPlannerDecision.deepCopy());
        }
        if (lastNavigatorFailure != null) {
            result.add("execution_failure", lastNavigatorFailure.deepCopy());
        }
        if (activeDirection != null) {
            result.addProperty("direction", activeDirection.getName());
        }
        if (activeShape != null) {
            result.addProperty("shape", shapeWireName(activeShape));
        }
        return result;
    }

    private double progress() {
        return state.phase() == AutonomousMiningState.Phase.COMPLETED ? 1.0D
                : Math.min(0.99D,
                (double) state.collectedCount() / state.targetCount());
    }

    private void addVeinStatus(JsonObject json) {
        boolean locked = veinTracker.locked();
        int remainingKnown = veinTracker.pendingMembers().size();
        json.addProperty("minimum_reached", state.goalReached());
        json.addProperty("vein_locked", locked);
        json.addProperty("vein_harvested", veinTracker.harvestedMembers().size());
        json.addProperty("vein_remaining_known", remainingKnown);
        json.addProperty("vein_complete",
                locked && remainingKnown == 0
                        && state.phase() == AutonomousMiningState.Phase.COMPLETED);
        json.addProperty("target_overshoot",
                Math.max(0, state.collectedCount() - state.targetCount()));
        json.addProperty("completion_rule",
                "target_count_is_minimum_finish_committed_vein");
    }

    private void addBackpackCapacityStatus(JsonObject json) {
        if (lastBackpackCapacity == null) {
            return;
        }
        json.addProperty("capacity_check_mode", lastCapacityCheckMode);
        json.addProperty("backpack_slots", lastBackpackCapacity.slots());
        json.addProperty("backpack_empty_slots", lastBackpackCapacity.emptySlots());
        json.addProperty("backpack_partial_stack_slots",
                lastBackpackCapacity.partialStackSlots());
        json.addProperty("backpack_saturated_slots",
                lastBackpackCapacity.saturatedSlots());
        json.addProperty("capacity_candidates_checked",
                lastCapacityCandidatesChecked);
        json.addProperty("capacity_candidates_storable",
                lastCapacityCandidatesStorable);
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

    /** Completion requires proving that every 26-neighbour boundary is loaded. */
    private boolean committedVeinTouchesUnloadedChunk(MaidActionContext context) {
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
                || !Double.isFinite(evaluator.supportCost(standPos.below()))) {
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
        return MaidProgressiveBlockBreaker.canReachVisibleFace(context, target);
    }

    /**
     * 检测女仆背包是否缺少任何通用的、安全可用容量。
     *
     * 未发现目标矿石时，以物理背包容量作为继续探矿的门禁：完全空槽或仍
     * 可接收同类物品的部分堆叠都算余量。发现目标后会由 canStoreDrops 对
     * 真实掉落执行更严格的逐候选模拟，因此部分堆叠不会放行不兼容掉落。
     */
    static boolean isBackpackFull(EntityMaid maid) {
        IItemHandler inventory = maid.getAvailableBackpackInv();
        return isBackpackFull(inventory);
    }

    /**
     * 检测 IItemHandler 是否没有通用空 slot。
     * 包内可见以便单元测试,逻辑独立于 EntityMaid。
     */
    static boolean isBackpackFull(IItemHandler inventory) {
        return summarizeBackpackCapacity(inventory).full();
    }

    static BackpackCapacitySummary summarizeBackpackCapacity(
            IItemHandler inventory) {
        if (inventory == null) {
            return new BackpackCapacitySummary(0, 0, 0, 0, false);
        }
        int emptySlots = 0;
        int partialStackSlots = 0;
        int saturatedSlots = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() && inventory.getSlotLimit(slot) > 0) {
                emptySlots++;
                continue;
            }
            int limit = stack.isEmpty() ? 0 : Math.min(
                    inventory.getSlotLimit(slot), stack.getMaxStackSize());
            if (!stack.isEmpty() && stack.getCount() < limit
                    && inventory.isItemValid(slot, stack)) {
                partialStackSlots++;
            } else {
                saturatedSlots++;
            }
        }
        boolean full = inventory.getSlots() == 0
                || (emptySlots == 0 && partialStackSlots == 0);
        return new BackpackCapacitySummary(inventory.getSlots(), emptySlots,
                partialStackSlots, saturatedSlots, full);
    }

    static RestartProjection restartProjection(
            String blockedReason, int targetCount, int collectedCount) {
        if (targetCount < 1 || collectedCount < 0) {
            throw new IllegalArgumentException(
                    "terminal mining counts must be non-negative and target positive");
        }
        String normalizedReason = AutonomousMiningState.normalizeReason(blockedReason);
        int remaining = Math.max(0, targetCount - collectedCount);
        return new RestartProjection(remaining,
                "backpack_full".equals(normalizedReason) && remaining > 0);
    }

    private List<BlockPos> filterStorableTargets(
            MaidActionContext context, List<BlockPos> targets) {
        IItemHandler inventory = context.maid().getAvailableBackpackInv();
        return filterStorableCandidates(inventory, targets, target -> {
            BlockState targetState = context.level().getBlockState(target);
            if (!eligibleTarget(target, targetState)) {
                return null;
            }
            BlockEntity blockEntity = targetState.hasBlockEntity()
                    ? context.level().getBlockEntity(target)
                    : null;
            return Block.getDrops(
                    targetState, context.level(), target, blockEntity,
                    context.maid(), context.maid().getMainHandItem());
        });
    }

    static <T> List<T> filterStorableCandidates(
            IItemHandler inventory,
            List<T> candidates,
            Function<? super T, List<ItemStack>> dropResolver) {
        if (candidates == null || dropResolver == null) {
            return List.of();
        }
        List<T> storable = new ArrayList<>();
        for (T candidate : candidates) {
            List<ItemStack> drops = dropResolver.apply(candidate);
            if (drops != null && canStoreDrops(inventory, drops)) {
                storable.add(candidate);
            }
        }
        return List.copyOf(storable);
    }

    /**
     * 在内存副本中模拟一组掉落物依次插入 IItemHandler。
     *
     * 模拟同时遵守 slotLimit、isItemValid 和物品组件兼容性；不会修改真实背包。
     */
    static boolean canStoreDrops(
            IItemHandler inventory, List<ItemStack> drops) {
        if (inventory == null || drops == null) {
            return false;
        }
        List<ItemStack> simulated = new ArrayList<>(inventory.getSlots());
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            simulated.add(inventory.getStackInSlot(slot).copy());
        }
        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            ItemStack remaining = drop.copy();
            for (int slot = 0;
                    slot < simulated.size() && !remaining.isEmpty(); slot++) {
                ItemStack present = simulated.get(slot);
                if (present.isEmpty()
                        || !inventory.isItemValid(slot, remaining)
                        || !ItemStack.isSameItemSameComponents(
                        present, remaining)) {
                    continue;
                }
                int limit = Math.min(inventory.getSlotLimit(slot),
                        present.getMaxStackSize());
                int inserted = Math.min(remaining.getCount(),
                        Math.max(0, limit - present.getCount()));
                if (inserted > 0) {
                    present.grow(inserted);
                    remaining.shrink(inserted);
                }
            }
            for (int slot = 0;
                    slot < simulated.size() && !remaining.isEmpty(); slot++) {
                if (!simulated.get(slot).isEmpty()
                        || !inventory.isItemValid(slot, remaining)) {
                    continue;
                }
                int limit = Math.min(inventory.getSlotLimit(slot),
                        remaining.getMaxStackSize());
                int inserted = Math.min(remaining.getCount(),
                        Math.max(0, limit));
                if (inserted > 0) {
                    ItemStack insertedStack = remaining.copy();
                    insertedStack.setCount(inserted);
                    simulated.set(slot, insertedStack);
                    remaining.shrink(inserted);
                }
            }
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
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
            int segmentLength, double speed, DiscoveryMode discoveryMode,
            PlacementPolicy placementPolicy, int maxPlacements) {
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
        args.addProperty("placement_policy", placementPolicy.wireName);
        args.addProperty("max_placements", maxPlacements);
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

    public enum PlacementPolicy {
        DISABLED("disabled"),
        SAFE_SUPPORT_AND_WATER_SEAL("safe_support_and_water_seal");

        private final String wireName;

        PlacementPolicy(String wireName) {
            this.wireName = wireName;
        }

        boolean enabled() {
            return this == SAFE_SUPPORT_AND_WATER_SEAL;
        }

        static PlacementPolicy fromWireName(String value) {
            String normalized = value == null ? ""
                    : value.trim().toLowerCase(Locale.ROOT);
            for (PlacementPolicy policy : values()) {
                if (policy.wireName.equals(normalized)) {
                    return policy;
                }
            }
            throw new IllegalArgumentException(
                    "placement_policy must be disabled or "
                            + "safe_support_and_water_seal");
        }
    }

    enum ScanDecision {
        COMPLETE,
        CONTINUE,
        HARVEST,
        BLOCK_CAPACITY
    }

    enum ExhaustedCommitmentDecision {
        COMPLETE_CURRENT,
        RELEASE_FOR_NEXT
    }

    record ScanDecisionFacts(
            boolean veinLocked,
            boolean goalReached,
            boolean targetsAvailable,
            boolean capacityAvailable) {
        ScanDecisionFacts {
            if (veinLocked && !targetsAvailable && !goalReached) {
                throw new IllegalArgumentException(
                        "an exhausted incomplete vein must be unlocked before reduction");
            }
        }
    }

    record RestartProjection(
            int remainingTargetCount,
            boolean restartSupported) {
    }

    record BackpackCapacitySummary(
            int slots,
            int emptySlots,
            int partialStackSlots,
            int saturatedSlots,
            boolean full) {
    }

    private enum PlanningPurpose {
        EXCAVATION,
        HARVEST
    }

    private record ToolCandidate(int slot, double score) {
    }

    private record HarvestStance(BlockPos stand, BlockPos target) {
        private HarvestStance {
            stand = Objects.requireNonNull(stand, "stand").immutable();
            target = Objects.requireNonNull(target, "target").immutable();
        }
    }

    private record PlannedStep(
            Direction direction,
            ExcavateSegmentAction.Shape shape,
            BlockPos destination,
            MaidTerrainStep.Kind kind,
            MiningPlanner.Candidate candidate) {
    }
}
