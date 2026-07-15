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
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainPath;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainSearch;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainStep;
import com.neko_tlm_bridge.tlm.agent.path.MaidTerrainWorldEvaluator;
import com.neko_tlm_bridge.tlm.agent.runtime.HandLease;
import com.neko_tlm_bridge.tlm.agent.runtime.MaidActionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Excavates one bounded, explicitly directed tunnel segment at a time.
 * Every adjacent edge is planned and revalidated against the live server
 * world; ore is an observation boundary and is never route clearance.
 */
public final class ExcavateSegmentAction implements MaidAction {
    private static final int MIN_LENGTH = 1;
    private static final int MAX_LENGTH = 8;
    private static final int SEARCH_BUDGET_PER_TICK = 64;
    private static final int MAX_SEARCH_EXPANSIONS = 64;
    private static final double MOVEMENT_SPEED = 0.7D;

    private final Direction direction;
    private final Shape shape;
    private final int length;
    private final LinkedHashMap<BlockPos, String> clearedBlocks = new LinkedHashMap<>();
    private final LinkedHashMap<BlockPos, Encounter> encounteredBlocks = new LinkedHashMap<>();

    private Stage stage = Stage.VALIDATING;
    private BlockPos start;
    private BlockPos realEnd;
    private BlockPos segmentFrom;
    private BlockPos segmentTo;
    private MaidTerrainStep.Kind segmentKind;
    private MaidTerrainSearch terrainSearch;
    private MaidTerrainNavigator navigator;
    private HandLease handLease;
    private int segmentsDug;
    private int expandedNodes;
    private boolean started;

    public ExcavateSegmentAction(Direction direction, Shape shape, int length) {
        this.direction = Objects.requireNonNull(direction, "direction");
        if (!direction.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("direction must be horizontal");
        }
        this.shape = Objects.requireNonNull(shape, "shape");
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException("length must be between 1 and 8");
        }
        this.length = length;
    }

    public static ExcavateSegmentAction fromArgs(JsonObject args) {
        Objects.requireNonNull(args, "args");
        Set<String> allowed = Set.of("direction", "shape", "length");
        for (String name : args.keySet()) {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(
                        "Unsupported excavate_segment field: " + name);
            }
        }
        Direction direction = switch (requireString(args, "direction").toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> throw new IllegalArgumentException(
                    "direction must be north, south, east or west");
        };
        Shape shape = Shape.fromWireName(requireString(args, "shape"));
        int length = requireInt(args, "length");
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException("length must be between 1 and 8");
        }
        return new ExcavateSegmentAction(direction, shape, length);
    }

    @Override
    public MaidActionKind kind() {
        return MaidActionKind.EXCAVATE_SEGMENT;
    }

    @Override
    public Set<MaidActionResource> resources() {
        return Set.of(MaidActionResource.MOVE, MaidActionResource.HAND,
                MaidActionResource.BREAK);
    }

    @Override
    public void start(MaidActionContext context) {
        started = true;
        start = context.maid().blockPosition().immutable();
        realEnd = start;
        report(context, Stage.VALIDATING, 0.0D, new JsonObject());
    }

    @Override
    public MaidActionTickResult tick(MaidActionContext context) {
        if (!started) {
            start(context);
        }
        return switch (stage) {
            case VALIDATING -> validateAndLeaseTool(context);
            case PLANNING -> beginSegmentPlanning(context);
            case PATHFINDING -> advanceSegmentPlanning(context);
            case EXCAVATING -> executeSegment(context);
        };
    }

    @Override
    public void stop(MaidActionContext context, ActionEndReason reason) {
        if (navigator != null) {
            navigator.stop(context);
            navigator = null;
        }
        terrainSearch = null;
        if (context != null && context.maid() != null) {
            realEnd = context.maid().blockPosition().immutable();
        }
    }

    @Override
    public JsonObject terminationResult(MaidActionContext context, ActionEndReason reason) {
        BlockPos liveEnd = context == null || context.maid() == null
                ? realEnd : context.maid().blockPosition().immutable();
        StopReason stopReason = stopReasonFor(reason);
        return result(stopReason, liveEnd, null);
    }

    Direction direction() {
        return direction;
    }

    Shape shape() {
        return shape;
    }

    int length() {
        return length;
    }

    static BlockPos nextPosition(BlockPos from, Direction direction, Shape shape) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(shape, "shape");
        BlockPos forward = from.relative(direction);
        return shape == Shape.STAIRCASE_DOWN ? forward.below() : forward;
    }

    static List<BlockPos> clearanceFor(BlockPos destination, Shape shape) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(shape, "shape");
        return shape == Shape.STAIRCASE_DOWN
                ? List.of(destination, destination.above(), destination.above(2))
                : List.of(destination, destination.above());
    }

    private MaidActionTickResult validateAndLeaseTool(MaidActionContext context) {
        if (!isLoadedBuildPosition(context, start)
                || !isLoadedBuildPosition(context, start.above())
                || !isLoadedBuildPosition(context, start.below())) {
            return failure(context, ActionEndReason.VALIDATION_FAILED,
                    StopReason.PATH_NOT_FOUND, "excavation_start_is_not_loaded");
        }
        if (!context.maid().onGround()) {
            return failure(context, ActionEndReason.VALIDATION_FAILED,
                    StopReason.UNSAFE_SUPPORT, "excavation_start_has_unsafe_support");
        }
        BlockPos startSupportPos = start.below();
        BlockState startSupport = context.level().getBlockState(startSupportPos);
        MaidActionTickResult startSupportFailure = supportFailure(
                context, startSupportPos, startSupport,
                MaidTerrainWorldEvaluator.assessStandSupport(
                        context.level(), startSupportPos, startSupport));
        if (startSupportFailure != null) {
            return startSupportFailure;
        }

        List<PlannedBlock> anticipated = collectAnticipatedBreakBlocks(context);
        ToolCandidate selected = findBestTool(context.maid(), anticipated);
        if (selected == null) {
            anticipated.stream()
                    .filter(block -> block.state().requiresCorrectToolForDrops())
                    .findFirst()
                    .ifPresent(block -> recordEncounter(
                            block.pos(), block.state(), StopReason.TOOL_NOT_FOUND));
            return failure(context, ActionEndReason.TOOL_NOT_FOUND,
                    StopReason.TOOL_NOT_FOUND, "correct_tool_not_found_for_excavation");
        }
        try {
            handLease = selected.slot() == HandLease.HELD_TOOL_SLOT
                    ? HandLease.heldTool(context.maid())
                    : HandLease.equipFromBackpack(context.maid(), selected.slot());
        } catch (RuntimeException conflict) {
            return failure(context, ActionEndReason.HAND_CONFLICT,
                    StopReason.TARGET_CHANGED, "tool_slot_changed_before_excavation");
        }
        boolean attached = MaidActionStore.getInstance().attachHandLease(
                context.execution().actionId(), context.execution().generation(), handLease);
        if (!attached) {
            handLease.release(context.maid());
            handLease = null;
            return failure(context, ActionEndReason.SUPERSEDED,
                    StopReason.CANCELLED, "action_ended_before_tool_lease_attached");
        }

        stage = Stage.PLANNING;
        report(context, stage, progress(), new JsonObject());
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult beginSegmentPlanning(MaidActionContext context) {
        if (segmentsDug >= length) {
            return success(context, StopReason.COMPLETED, null);
        }
        BlockPos live = context.maid().blockPosition().immutable();
        if (!live.equals(realEnd) || !context.maid().onGround()) {
            return failure(context, ActionEndReason.TARGET_CHANGED,
                    StopReason.TARGET_CHANGED, "maid_left_excavation_segment_origin");
        }

        segmentFrom = live;
        segmentTo = nextPosition(segmentFrom, direction, shape).immutable();
        segmentKind = shape == Shape.STAIRCASE_DOWN
                ? MaidTerrainStep.Kind.DESCEND : MaidTerrainStep.Kind.TRAVERSE;
        MaidActionTickResult preflightFailure = preflightSegment(context);
        if (preflightFailure != null) {
            return preflightFailure;
        }

        MaidTerrainWorldEvaluator evaluator = new MaidTerrainWorldEvaluator(
                context.level(), context.maid(), segmentFrom, 3, 3, true,
                pos -> !HarvestBlocksAction.isAnyOre(context.level().getBlockState(pos)));
        terrainSearch = new MaidTerrainSearch(
                segmentFrom, Set.of(segmentTo), evaluator, MAX_SEARCH_EXPANSIONS,
                EnumSet.of(segmentKind));
        stage = Stage.PATHFINDING;
        JsonObject detail = segmentDetail();
        report(context, stage, progress(), detail);
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult advanceSegmentPlanning(MaidActionContext context) {
        if (terrainSearch == null) {
            return failure(context, ActionEndReason.INTERNAL_ERROR,
                    StopReason.PATH_NOT_FOUND, "excavation_search_missing");
        }
        MaidTerrainSearch.Status status = terrainSearch.advance(SEARCH_BUDGET_PER_TICK);
        expandedNodes += terrainSearch.expandedNodes();
        if (status == MaidTerrainSearch.Status.SEARCHING) {
            JsonObject detail = segmentDetail();
            detail.addProperty("nodes_expanded", terrainSearch.expandedNodes());
            report(context, stage, progress(), detail);
            return MaidActionTickResult.running();
        }
        if (status == MaidTerrainSearch.Status.FAILED) {
            return failure(context, ActionEndReason.PATH_NOT_FOUND,
                    StopReason.PATH_NOT_FOUND, "directed_excavation_step_not_found");
        }

        MaidTerrainPath path = terrainSearch.result().orElse(null);
        if (!isExactDirectedSegment(path)) {
            return failure(context, ActionEndReason.INTERNAL_ERROR,
                    StopReason.PATH_NOT_FOUND, "excavation_search_returned_non_direct_path");
        }
        terrainSearch = null;
        navigator = new MaidTerrainNavigator(path, handLease, MOVEMENT_SPEED, true);
        navigator.start(context);
        stage = Stage.EXCAVATING;
        report(context, stage, progress(), segmentDetail());
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult executeSegment(MaidActionContext context) {
        if (navigator == null) {
            return failure(context, ActionEndReason.INTERNAL_ERROR,
                    StopReason.PATH_NOT_FOUND, "excavation_navigator_missing");
        }
        MaidTerrainNavigator.TickResult tick = navigator.tick(context);
        for (MaidTerrainNavigator.ClearedBlock cleared : navigator.drainClearedBlocks()) {
            if (HarvestBlocksAction.isAnyOre(cleared.state())) {
                recordEncounter(cleared.pos(), cleared.state(), StopReason.ORE_ENCOUNTERED);
                return failure(context, ActionEndReason.INTERNAL_ERROR,
                        StopReason.PATH_NOT_FOUND, "excavation_cleared_ore_invariant_breached");
            }
            clearedBlocks.putIfAbsent(cleared.pos().immutable(), blockId(cleared.state()));
            if (recordAdjacentOre(context, cleared.pos())) {
                return success(context, StopReason.ORE_ENCOUNTERED,
                        "ore_exposed_while_clearing_excavation_segment");
            }
        }
        if (tick.outcome() == MaidTerrainNavigator.Outcome.FAILED) {
            String message = tick.detail().has("message")
                    ? tick.detail().get("message").getAsString()
                    : "excavation_segment_execution_failed";
            if (recordSegmentOre(context)) {
                return success(context, StopReason.ORE_ENCOUNTERED,
                        "ore_appeared_during_excavation_segment");
            }
            StopReason stopReason = liveSegmentStopReason(context, tick.reason());
            return failure(context, tick.reason() == null
                            ? ActionEndReason.INTERNAL_ERROR : tick.reason(),
                    stopReason, message);
        }
        if (tick.outcome() == MaidTerrainNavigator.Outcome.ARRIVED) {
            navigator = null;
            realEnd = context.maid().blockPosition().immutable();
            segmentsDug++;
            if (recordExposedOre(context, realEnd)) {
                return success(context, StopReason.ORE_ENCOUNTERED,
                        "ore_exposed_after_excavation_segment");
            }
            if (segmentsDug >= length) {
                return success(context, StopReason.COMPLETED, null);
            }
            segmentFrom = null;
            segmentTo = null;
            segmentKind = null;
            stage = Stage.PLANNING;
            report(context, stage, progress(), new JsonObject());
            return MaidActionTickResult.running();
        }

        JsonObject detail = tick.detail().deepCopy();
        detail.addProperty("segments_dug", segmentsDug);
        report(context, stage, progress(), detail);
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult preflightSegment(MaidActionContext context) {
        BlockPos supportPos = segmentTo.below();
        List<BlockPos> inspected = new ArrayList<>(clearanceFor(segmentTo, shape));
        inspected.add(supportPos);
        for (BlockPos pos : inspected) {
            if (!isLoadedBuildPosition(context, pos)) {
                return failure(context, ActionEndReason.PATH_NOT_FOUND,
                        StopReason.PATH_NOT_FOUND, "excavation_step_is_not_loaded");
            }
            BlockState state = context.level().getBlockState(pos);
            if (HarvestBlocksAction.isAnyOre(state)) {
                recordEncounter(pos, state, StopReason.ORE_ENCOUNTERED);
                return success(context, StopReason.ORE_ENCOUNTERED,
                        "ore_encountered_before_excavation");
            }
        }

        BlockState support = context.level().getBlockState(supportPos);
        MaidTerrainWorldEvaluator.SupportAssessment supportAssessment =
                MaidTerrainWorldEvaluator.assessStandSupport(
                        context.level(), supportPos, support);
        MaidActionTickResult supportFailure = supportFailure(
                context, supportPos, support, supportAssessment);
        if (supportFailure != null) {
            return supportFailure;
        }

        for (BlockPos pos : clearanceFor(segmentTo, shape)) {
            BlockState state = context.level().getBlockState(pos);
            MaidTerrainWorldEvaluator.ClearanceAssessment assessment =
                    MaidTerrainWorldEvaluator.assessClearance(context.level(), pos, state);
            MaidActionTickResult blocked = clearanceFailure(
                    context, pos, state, assessment);
            if (blocked != null) {
                return blocked;
            }
            if (assessment == MaidTerrainWorldEvaluator.ClearanceAssessment.BREAKABLE
                    && state.requiresCorrectToolForDrops()
                    && !context.maid().getMainHandItem().isCorrectToolForDrops(state)) {
                recordEncounter(pos, state, StopReason.TOOL_NOT_FOUND);
                return failure(context, ActionEndReason.TOOL_NOT_FOUND,
                        StopReason.TOOL_NOT_FOUND,
                        "held_tool_cannot_clear_excavation_block");
            }
        }
        return null;
    }

    private MaidActionTickResult supportFailure(
            MaidActionContext context, BlockPos pos, BlockState state,
            MaidTerrainWorldEvaluator.SupportAssessment assessment) {
        return switch (assessment) {
            case SAFE -> null;
            case WATER_HAZARD -> blockedFailure(context, pos, state,
                    ActionEndReason.PATH_NOT_FOUND, StopReason.WATER_HAZARD,
                    "water_hazard_at_excavation_support");
            case LAVA_HAZARD -> blockedFailure(context, pos, state,
                    ActionEndReason.PATH_NOT_FOUND, StopReason.LAVA_HAZARD,
                    "lava_hazard_at_excavation_support");
            case UNSAFE_SUPPORT -> blockedFailure(context, pos, state,
                    ActionEndReason.PATH_NOT_FOUND, StopReason.UNSAFE_SUPPORT,
                    "unsafe_excavation_support");
            case UNLOADED -> failure(context, ActionEndReason.PATH_NOT_FOUND,
                    StopReason.PATH_NOT_FOUND, "excavation_support_is_not_loaded");
            case TARGET_CHANGED -> failure(context, ActionEndReason.TARGET_CHANGED,
                    StopReason.TARGET_CHANGED, "excavation_support_changed");
        };
    }

    private MaidActionTickResult clearanceFailure(
            MaidActionContext context, BlockPos pos, BlockState state,
            MaidTerrainWorldEvaluator.ClearanceAssessment assessment) {
        return switch (assessment) {
            case CLEAR, BREAKABLE -> null;
            case WATER_HAZARD -> blockedFailure(context, pos, state,
                    ActionEndReason.PATH_NOT_FOUND, StopReason.WATER_HAZARD,
                    "water_would_enter_excavation");
            case LAVA_HAZARD -> blockedFailure(context, pos, state,
                    ActionEndReason.PATH_NOT_FOUND, StopReason.LAVA_HAZARD,
                    "lava_would_enter_excavation");
            case PROTECTED_BLOCK -> blockedFailure(context, pos, state,
                    ActionEndReason.BLOCK_PROTECTED, StopReason.PROTECTED_BLOCK,
                    "protected_block_in_excavation");
            case UNSAFE -> blockedFailure(context, pos, state,
                    ActionEndReason.PATH_NOT_FOUND, StopReason.PATH_NOT_FOUND,
                    "unsafe_block_in_excavation");
            case UNLOADED -> failure(context, ActionEndReason.PATH_NOT_FOUND,
                    StopReason.PATH_NOT_FOUND, "excavation_clearance_is_not_loaded");
            case TARGET_CHANGED -> failure(context, ActionEndReason.TARGET_CHANGED,
                    StopReason.TARGET_CHANGED, "excavation_clearance_changed");
        };
    }

    private MaidActionTickResult blockedFailure(
            MaidActionContext context, BlockPos pos, BlockState state,
            ActionEndReason endReason, StopReason stopReason, String message) {
        recordEncounter(pos, state, stopReason);
        return failure(context, endReason, stopReason, message);
    }

    private boolean recordSegmentOre(MaidActionContext context) {
        if (segmentTo == null) {
            return false;
        }
        List<BlockPos> inspected = new ArrayList<>(clearanceFor(segmentTo, shape));
        inspected.add(segmentTo.below());
        boolean found = false;
        for (BlockPos pos : inspected) {
            if (!isLoadedBuildPosition(context, pos)) {
                continue;
            }
            BlockState state = context.level().getBlockState(pos);
            if (HarvestBlocksAction.isAnyOre(state)) {
                recordEncounter(pos, state, StopReason.ORE_ENCOUNTERED);
                found = true;
            }
        }
        return found;
    }

    private StopReason liveSegmentStopReason(
            MaidActionContext context, ActionEndReason fallback) {
        if (segmentTo == null || fallback != ActionEndReason.PATH_NOT_FOUND) {
            return stopReasonFor(fallback);
        }
        BlockPos supportPos = segmentTo.below();
        if (isLoadedBuildPosition(context, supportPos)) {
            BlockState support = context.level().getBlockState(supportPos);
            MaidTerrainWorldEvaluator.SupportAssessment assessment =
                    MaidTerrainWorldEvaluator.assessStandSupport(
                            context.level(), supportPos, support);
            if (assessment == MaidTerrainWorldEvaluator.SupportAssessment.WATER_HAZARD) {
                return StopReason.WATER_HAZARD;
            }
            if (assessment == MaidTerrainWorldEvaluator.SupportAssessment.LAVA_HAZARD) {
                return StopReason.LAVA_HAZARD;
            }
            if (assessment == MaidTerrainWorldEvaluator.SupportAssessment.UNSAFE_SUPPORT) {
                return StopReason.UNSAFE_SUPPORT;
            }
        }
        for (BlockPos pos : clearanceFor(segmentTo, shape)) {
            if (!isLoadedBuildPosition(context, pos)) {
                continue;
            }
            BlockState state = context.level().getBlockState(pos);
            MaidTerrainWorldEvaluator.ClearanceAssessment assessment =
                    MaidTerrainWorldEvaluator.assessClearance(context.level(), pos, state);
            if (assessment == MaidTerrainWorldEvaluator.ClearanceAssessment.WATER_HAZARD) {
                return StopReason.WATER_HAZARD;
            }
            if (assessment == MaidTerrainWorldEvaluator.ClearanceAssessment.LAVA_HAZARD) {
                return StopReason.LAVA_HAZARD;
            }
            if (assessment == MaidTerrainWorldEvaluator.ClearanceAssessment.PROTECTED_BLOCK) {
                return StopReason.PROTECTED_BLOCK;
            }
            if (assessment == MaidTerrainWorldEvaluator.ClearanceAssessment.BREAKABLE
                    && state.requiresCorrectToolForDrops()
                    && !context.maid().getMainHandItem().isCorrectToolForDrops(state)) {
                return StopReason.TOOL_NOT_FOUND;
            }
        }
        return stopReasonFor(fallback);
    }

    private boolean isExactDirectedSegment(MaidTerrainPath path) {
        if (path == null || path.steps().size() != 1
                || !path.target().equals(segmentTo)) {
            return false;
        }
        MaidTerrainStep step = path.steps().getFirst();
        return step.kind() == segmentKind
                && step.from().equals(segmentFrom)
                && step.to().equals(segmentTo);
    }

    private List<PlannedBlock> collectAnticipatedBreakBlocks(MaidActionContext context) {
        List<PlannedBlock> result = new ArrayList<>();
        LinkedHashSet<BlockPos> seen = new LinkedHashSet<>();
        BlockPos cursor = start;
        boolean stopScanning = false;
        for (int index = 0; index < length; index++) {
            BlockPos destination = nextPosition(cursor, direction, shape).immutable();
            BlockPos supportPos = destination.below();
            if (!isLoadedBuildPosition(context, supportPos)) {
                break;
            }
            BlockState support = context.level().getBlockState(supportPos);
            if (HarvestBlocksAction.isAnyOre(support)
                    || MaidTerrainWorldEvaluator.assessStandSupport(
                    context.level(), supportPos, support)
                    != MaidTerrainWorldEvaluator.SupportAssessment.SAFE) {
                break;
            }
            for (BlockPos pos : clearanceFor(destination, shape)) {
                if (!seen.add(pos)) {
                    continue;
                }
                if (!isLoadedBuildPosition(context, pos)) {
                    stopScanning = true;
                    break;
                }
                BlockState state = context.level().getBlockState(pos);
                if (HarvestBlocksAction.isAnyOre(state)) {
                    stopScanning = true;
                    break;
                }
                MaidTerrainWorldEvaluator.ClearanceAssessment assessment =
                        MaidTerrainWorldEvaluator.assessClearance(
                                context.level(), pos, state);
                if (assessment == MaidTerrainWorldEvaluator.ClearanceAssessment.CLEAR) {
                    continue;
                }
                if (assessment == MaidTerrainWorldEvaluator.ClearanceAssessment.BREAKABLE) {
                    result.add(new PlannedBlock(pos.immutable(), state));
                } else {
                    stopScanning = true;
                    break;
                }
            }
            if (stopScanning) {
                break;
            }
            cursor = destination;
        }
        return List.copyOf(result);
    }

    private static ToolCandidate findBestTool(EntityMaid maid, List<PlannedBlock> blocks) {
        ToolCandidate best = toolCandidate(
                HandLease.HELD_TOOL_SLOT, maid.getMainHandItem(), blocks);
        IItemHandler inventory = maid.getAvailableBackpackInv();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ToolCandidate candidate = toolCandidate(
                    slot, inventory.getStackInSlot(slot), blocks);
            if (candidate != null && (best == null || candidate.score() > best.score())) {
                best = candidate;
            }
        }
        return best;
    }

    private static ToolCandidate toolCandidate(
            int slot, ItemStack stack, List<PlannedBlock> blocks) {
        double score = 0.0D;
        for (PlannedBlock block : blocks) {
            boolean correct = !block.state().requiresCorrectToolForDrops()
                    || stack.isCorrectToolForDrops(block.state());
            if (!correct) {
                return null;
            }
            score += Math.max(1.0F, stack.getDestroySpeed(block.state()));
        }
        return new ToolCandidate(slot, score);
    }

    private boolean recordExposedOre(MaidActionContext context, BlockPos feet) {
        boolean found = false;
        for (BlockPos occupied : List.of(feet, feet.above())) {
            found |= recordAdjacentOre(context, occupied);
        }
        return found;
    }

    private boolean recordAdjacentOre(MaidActionContext context, BlockPos origin) {
        boolean found = false;
        for (Direction face : Direction.values()) {
            BlockPos pos = origin.relative(face).immutable();
            if (!isLoadedBuildPosition(context, pos)) {
                continue;
            }
            BlockState state = context.level().getBlockState(pos);
            if (HarvestBlocksAction.isAnyOre(state)) {
                recordEncounter(pos, state, StopReason.ORE_ENCOUNTERED);
                found = true;
            }
        }
        return found;
    }

    private MaidActionTickResult success(
            MaidActionContext context, StopReason stopReason, String message) {
        realEnd = context.maid().blockPosition().immutable();
        return MaidActionTickResult.succeeded(result(stopReason, realEnd, message));
    }

    private MaidActionTickResult failure(
            MaidActionContext context, ActionEndReason endReason,
            StopReason stopReason, String message) {
        realEnd = context.maid().blockPosition().immutable();
        return MaidActionTickResult.failed(endReason,
                result(stopReason, realEnd, message));
    }

    private JsonObject result(StopReason stopReason, BlockPos end, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("stop_reason", stopReason.wireName);
        result.add("real_end", position(end == null ? BlockPos.ZERO : end));
        result.addProperty("cleared_blocks", clearedBlocks.size());
        JsonArray clearedDetails = new JsonArray();
        for (Map.Entry<BlockPos, String> entry : clearedBlocks.entrySet()) {
            JsonObject block = position(entry.getKey());
            block.addProperty("id", entry.getValue());
            clearedDetails.add(block);
        }
        result.add("cleared_block_details", clearedDetails);
        JsonArray encountered = new JsonArray();
        for (Map.Entry<BlockPos, Encounter> entry : encounteredBlocks.entrySet()) {
            JsonObject block = position(entry.getKey());
            block.addProperty("id", entry.getValue().blockId());
            block.addProperty("reason", entry.getValue().reason().wireName);
            encountered.add(block);
        }
        result.add("encountered_blocks", encountered);
        result.addProperty("segments_dug", segmentsDug);
        result.addProperty("direction", direction.getName());
        result.addProperty("shape", shape.wireName);
        result.addProperty("requested_length", length);
        result.addProperty("planner_expanded_nodes", expandedNodes);
        if (message != null) {
            result.addProperty("message", message);
            result.addProperty("diagnostic_code", message);
        }
        return result;
    }

    private void recordEncounter(BlockPos pos, BlockState state, StopReason reason) {
        encounteredBlocks.putIfAbsent(pos.immutable(),
                new Encounter(blockId(state), reason));
    }

    private void report(MaidActionContext context, Stage nextStage,
                        double progress, JsonObject detail) {
        stage = nextStage;
        JsonObject report = detail == null ? new JsonObject() : detail.deepCopy();
        report.addProperty("direction", direction.getName());
        report.addProperty("shape", shape.wireName);
        report.addProperty("requested_length", length);
        report.addProperty("segments_dug", segmentsDug);
        report.add("real_end", position(realEnd == null ? context.maid().blockPosition() : realEnd));
        context.execution().reportProgress(nextStage.wireName, progress, report);
    }

    private JsonObject segmentDetail() {
        JsonObject detail = new JsonObject();
        if (segmentFrom != null) {
            detail.add("from", position(segmentFrom));
        }
        if (segmentTo != null) {
            detail.add("to", position(segmentTo));
        }
        if (segmentKind != null) {
            detail.addProperty("step_kind", segmentKind.name().toLowerCase(Locale.ROOT));
        }
        return detail;
    }

    private double progress() {
        return Math.min(0.99D, (double) segmentsDug / length);
    }

    private static boolean isLoadedBuildPosition(MaidActionContext context, BlockPos pos) {
        return pos.getY() >= context.level().getMinBuildHeight()
                && pos.getY() < context.level().getMaxBuildHeight()
                && context.level().hasChunkAt(pos);
    }

    private static StopReason stopReasonFor(ActionEndReason reason) {
        if (reason == null) {
            return StopReason.PATH_NOT_FOUND;
        }
        return switch (reason) {
            case COMPLETED -> StopReason.COMPLETED;
            case BLOCK_PROTECTED -> StopReason.PROTECTED_BLOCK;
            case TOOL_NOT_FOUND -> StopReason.TOOL_NOT_FOUND;
            case PATH_NOT_FOUND, VALIDATION_FAILED, INTERNAL_ERROR -> StopReason.PATH_NOT_FOUND;
            case STUCK -> StopReason.STUCK;
            case TARGET_CHANGED, HAND_CONFLICT -> StopReason.TARGET_CHANGED;
            case REQUESTED, SUPERSEDED, TIMEOUT, USER_OVERRIDE, SAFETY_PREEMPTED,
                    ENTITY_UNLOADED, ENTITY_DEAD, SERVER_STATE_LOST -> StopReason.CANCELLED;
        };
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static String requireString(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()
                || !args.getAsJsonPrimitive(name).isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return args.get(name).getAsString().trim();
    }

    private static int requireInt(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()
                || !args.getAsJsonPrimitive(name).isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            int value = args.get(name).getAsInt();
            if (args.get(name).getAsDouble() != value) {
                throw new IllegalArgumentException(name + " must be an integer");
            }
            return value;
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    public enum Shape {
        LEVEL("level"),
        STAIRCASE_DOWN("staircase_down");

        private final String wireName;

        Shape(String wireName) {
            this.wireName = wireName;
        }

        static Shape fromWireName(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            for (Shape shape : values()) {
                if (shape.wireName.equals(normalized)) {
                    return shape;
                }
            }
            throw new IllegalArgumentException("shape must be level or staircase_down");
        }
    }

    private enum Stage {
        VALIDATING("validating"),
        PLANNING("planning"),
        PATHFINDING("pathfinding"),
        EXCAVATING("excavating");

        private final String wireName;

        Stage(String wireName) {
            this.wireName = wireName;
        }
    }

    enum StopReason {
        COMPLETED("completed"),
        WATER_HAZARD("water_hazard"),
        LAVA_HAZARD("lava_hazard"),
        PROTECTED_BLOCK("protected_block"),
        TOOL_NOT_FOUND("tool_not_found"),
        UNSAFE_SUPPORT("unsafe_support"),
        ORE_ENCOUNTERED("ore_encountered"),
        PATH_NOT_FOUND("path_not_found"),
        STUCK("stuck"),
        TARGET_CHANGED("target_changed"),
        CANCELLED("cancelled");

        private final String wireName;

        StopReason(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    private record PlannedBlock(BlockPos pos, BlockState state) {
    }

    private record ToolCandidate(int slot, double score) {
    }

    private record Encounter(String blockId, StopReason reason) {
    }
}
