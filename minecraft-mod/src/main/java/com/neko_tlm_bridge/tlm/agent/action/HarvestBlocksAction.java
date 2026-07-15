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
import net.minecraft.world.level.pathfinder.Path;
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
import java.util.function.Predicate;

/** Searches, approaches and harvests a bounded number of blocks. */
public final class HarvestBlocksAction implements MaidAction {
    private static final int MAX_SEARCH_CANDIDATES = 16;
    private static final double APPROACH_DISTANCE = 1.0D;
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
    private NavigateAction navigation;
    private HandLease handLease;
    private double breakingProgress;
    private int harvested;
    private boolean started;

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
            case SELECTING_TOOL -> selectTool(context);
            case PATHFINDING, APPROACHING -> approach(context);
            case BREAKING -> breakBlock(context);
            case VERIFYING -> verifyAndContinue(context);
        };
    }

    @Override
    public void stop(MaidActionContext context, ActionEndReason reason) {
        clearBreakingAnimation(context);
        if (navigation != null) {
            navigation.stop(context, reason);
            navigation = null;
        }
    }

    public HandLease handLease() {
        return handLease;
    }

    private MaidActionTickResult search(MaidActionContext context) {
        report(context, Stage.SEARCHING, overallProgress(), null);
        candidates.clear();
        if (explicitTarget != null) {
            if (!context.level().hasChunkAt(explicitTarget)) {
                JsonObject result = positionDetail(explicitTarget);
                result.addProperty("message", "target_chunk_not_loaded");
                result.addProperty("retry_hint",
                        "For a nearby resource request, retry with a block/tag selector instead of this target_pos");
                return MaidActionTickResult.failed(ActionEndReason.VALIDATION_FAILED, result);
            }
            if (context.level().getBlockState(explicitTarget).isAir()) {
                return failure(ActionEndReason.TARGET_CHANGED, "target_is_air");
            }
            candidates.add(explicitTarget);
        } else {
            BlockPos origin = searchOrigin;
            BlockPos.betweenClosedStream(origin.offset(-searchRadius, -searchRadius, -searchRadius),
                            origin.offset(searchRadius, searchRadius, searchRadius))
                    // The vanilla iterator reuses a MutableBlockPos. Freeze it
                    // before any stateful stream operation (especially sort),
                    // otherwise every retained candidate becomes the final
                    // corner visited by the iterator.
                    .map(BlockPos::immutable)
                    .filter(pos -> pos.distSqr(origin) <= (double) searchRadius * searchRadius)
                    .filter(context.level()::hasChunkAt)
                    .filter(pos -> !rejectedCandidates.contains(pos))
                    .filter(pos -> selector.test(context.level().getBlockState(pos)))
                    // Prefer exposed blocks. Without this cheap geometry filter,
                    // the nearest 16 stone candidates are usually buried below
                    // the maid and can never have a valid approach position.
                    .filter(pos -> hasSafeAdjacentStandPosition(context, pos))
                    // Prefer targets that can be worked from the same level
                    // (or one block below, for a wall face). Surface blocks
                    // that require standing above them are kept as a fallback
                    // so a generic "stone" selector does not spend its first
                    // 16 candidates trying to dig the surrounding floor.
                    .sorted(Comparator
                            .comparingInt((BlockPos pos) -> approachHeightRank(context, pos))
                            .thenComparingDouble(origin::distSqr))
                    .limit(MAX_SEARCH_CANDIDATES)
                    .forEach(candidates::add);
        }

        if (candidates.isEmpty()) {
            if (harvested > 0) {
                return success(context);
            }
            return failure(ActionEndReason.TARGET_CHANGED, "no_matching_block_found");
        }
        return chooseNextCandidate(context);
    }

    private MaidActionTickResult chooseNextCandidate(MaidActionContext context) {
        while (!candidates.isEmpty()) {
            BlockPos candidate = candidates.removeFirst();
            BlockState candidateState = context.level().getBlockState(candidate);
            BlockPos standPos = findReachableStandPosition(context, candidate);
            if (!candidateState.isAir() && matchesTarget(candidateState) && standPos != null) {
                currentTarget = candidate;
                currentStandPos = standPos;
                expectedState = candidateState;
                report(context, Stage.SELECTING_TOOL, overallProgress(), currentTarget);
                return MaidActionTickResult.running();
            }
            rejectedCandidates.add(candidate.immutable());
        }
        return explicitTarget == null
                ? failure(ActionEndReason.PATH_NOT_FOUND, "no_reachable_matching_block_found")
                : failure(ActionEndReason.TARGET_CHANGED, "target_changed_before_harvest");
    }

    private MaidActionTickResult selectTool(MaidActionContext context) {
        if (!context.level().getBlockState(currentTarget).equals(expectedState)) {
            return onCandidateFailure(context, ActionEndReason.TARGET_CHANGED, "target_changed_before_tool_selection");
        }

        if (handLease == null) {
            ToolCandidate selected = findBestTool(context.maid(), expectedState);
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
        } else {
            ItemStack held = context.maid().getMainHandItem();
            boolean correct = !expectedState.requiresCorrectToolForDrops() || held.isCorrectToolForDrops(expectedState);
            if (toolPolicy == ToolPolicy.REQUIRE_CORRECT && !correct) {
                return failure(ActionEndReason.TOOL_NOT_FOUND, "equipped_tool_is_wrong_for_next_block");
            }
        }

        navigation = new NavigateAction(currentStandPos, speed, APPROACH_DISTANCE);
        navigation.start(context);
        report(context, Stage.PATHFINDING, overallProgress(), currentTarget);
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult approach(MaidActionContext context) {
        if (!context.level().getBlockState(currentTarget).equals(expectedState)) {
            return onCandidateFailure(context, ActionEndReason.TARGET_CHANGED, "target_changed_while_approaching");
        }
        if (handLease.validate(context.maid()) != HandLease.LeaseHealth.HEALTHY) {
            return failure(ActionEndReason.HAND_CONFLICT, "held_tool_changed_while_approaching");
        }

        MaidActionTickResult result = navigation.tick(context);
        if (result.outcome() == MaidActionTickResult.Outcome.FAILED) {
            navigation.stop(context, result.reason());
            navigation = null;
            return onCandidateFailure(context, result.reason(), "candidate_not_reachable");
        }
        if (result.outcome() == MaidActionTickResult.Outcome.SUCCEEDED) {
            navigation = null;
            breakingProgress = 0.0D;
            report(context, Stage.BREAKING, overallProgress(), currentTarget);
        } else {
            stage = Stage.APPROACHING;
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
        currentTarget = null;
        currentStandPos = null;
        expectedState = null;
        if (harvested >= maxBlocks || explicitTarget != null) {
            return success(context);
        }
        stage = Stage.SEARCHING;
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult onCandidateFailure(MaidActionContext context, ActionEndReason reason, String message) {
        clearBreakingAnimation(context);
        if (navigation != null) {
            navigation.stop(context, reason);
            navigation = null;
        }
        if (currentTarget != null) {
            rejectedCandidates.add(currentTarget.immutable());
        }
        currentTarget = null;
        currentStandPos = null;
        expectedState = null;
        if (!candidates.isEmpty()) {
            return chooseNextCandidate(context);
        }
        if (explicitTarget == null && harvested > 0) {
            return success(context);
        }
        return failure(reason, message);
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

    private MaidActionTickResult success(MaidActionContext context) {
        report(context, Stage.VERIFYING, 1.0D, null);
        JsonObject result = new JsonObject();
        result.addProperty("harvested", harvested);
        result.addProperty("selector", selectorDescription);
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

    private static MaidActionTickResult failure(ActionEndReason reason, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("message", message);
        return MaidActionTickResult.failed(reason, result);
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

    private BlockPos findReachableStandPosition(MaidActionContext context, BlockPos target) {
        // Never select the maid's support block: breaking it can cause an
        // immediate fall even when the path itself is valid.
        if (target.equals(context.maid().getOnPos())
                || target.equals(context.maid().blockPosition().below())) {
            return null;
        }
        List<BlockPos> positions = standPositionCandidates(target);
        positions.sort(Comparator.comparingDouble(context.maid().blockPosition()::distSqr));
        for (BlockPos standPos : positions) {
            if (!isSafeStandPosition(context, standPos)) {
                continue;
            }
            if (context.maid().position().distanceToSqr(Vec3.atBottomCenterOf(standPos))
                    <= APPROACH_DISTANCE * APPROACH_DISTANCE) {
                return standPos;
            }
            Path path = context.maid().getNavigation().createPath(standPos, 0);
            if (path != null && path.getNodeCount() > 0 && path.canReach()) {
                return standPos;
            }
        }
        return null;
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

    private static int approachHeightRank(MaidActionContext context, BlockPos target) {
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            BlockPos adjacent = target.relative(direction);
            // Same-level and wall-face targets are safer than floor targets.
            if (isSafeStandPosition(context, adjacent)
                    || isSafeStandPosition(context, adjacent.below())) {
                return 0;
            }
        }
        return 1;
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
