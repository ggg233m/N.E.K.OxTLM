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
import com.neko_tlm_bridge.tlm.agent.world.MiningReturnRoutePlanner;
import com.neko_tlm_bridge.tlm.agent.world.MiningWorldModelSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Returns a maid to a concrete position without teleporting.  A recorded,
 * loop-erased mining breadcrumb route is replayed first; each edge is
 * revalidated against the live world and may repair two-block clearance,
 * supports, and seal water with real inventory materials.  Player collision
 * safety is enforced inside {@link MaidTerrainNavigator} and the builder.
 */
public final class ReturnToPositionAction implements MaidAction {
    private static final int SEARCH_BUDGET_PER_TICK = 192;
    private static final int MAX_SEARCH_EXPANSIONS = 16_384;
    private static final int MAX_HORIZONTAL_SEARCH_RADIUS = 96;
    private static final int MAX_VERTICAL_SEARCH_RADIUS = 192;
    private static final int MAX_REPLANS = 3;
    private static final int SURFACE_INITIAL_SAMPLE_RADIUS = 16;
    private static final int SURFACE_MAX_SAMPLE_RADIUS = 32;
    private static final int SURFACE_SAMPLE_STEP = 2;
    private static final int SURFACE_DEPRESSION_DEPTH = 6;
    private static final int SURFACE_OUTLIER_HEIGHT = 12;

    private BlockPos target;
    private final boolean inferHorizontalTarget;
    private final Destination destination;
    private boolean targetResolved;
    private boolean surfaceHeightPending;
    private String surfaceStrategy = "pending";
    private int surfaceSampleCount;
    private int surfaceReferenceY;
    private String surfaceFailureReason = "surface_platform_not_found";
    private final double speed;
    private final double stopDistance;
    private final UUID requestedOperationId;
    private final RoutePolicy routePolicy;
    private final PlacementPolicy placementPolicy;
    private final int maxPlacements;

    private final List<BlockPos> waypoints = new ArrayList<>();
    private Stage stage = Stage.VALIDATING;
    private UUID operationId;
    private String routeSource = "terrain_replan";
    private int waypointIndex;
    private int activeLegWaypointCount = 1;
    private int replans;
    private int expandedNodes;
    private int clearedBlocks;
    private int placementsUsed;
    private int bridgeSupportsPlaced;
    private int waterSealsPlaced;
    private int playerWaitTicks;
    private final Set<BlockPos> rejectedPlacementTargets = new HashSet<>();
    private ActionEndReason lastReplanReason;
    private String lastReplanMessage;
    private JsonObject lastExecutionFailure;
    private HandLease handLease;
    private MaidTerrainSearch terrainSearch;
    private MaidTerrainNavigator navigator;
    private boolean started;

    public ReturnToPositionAction(BlockPos target, double speed, double stopDistance,
                                  UUID operationId, RoutePolicy routePolicy,
                                  PlacementPolicy placementPolicy, int maxPlacements) {
        this(target, false, speed, stopDistance, operationId, routePolicy,
                placementPolicy, maxPlacements, Destination.EXPLICIT);
    }

    private ReturnToPositionAction(
            BlockPos target, boolean inferHorizontalTarget,
            double speed, double stopDistance, UUID operationId,
            RoutePolicy routePolicy, PlacementPolicy placementPolicy,
            int maxPlacements, Destination destination) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.inferHorizontalTarget = inferHorizontalTarget;
        this.destination = Objects.requireNonNull(destination, "destination");
        this.targetResolved = destination == Destination.EXPLICIT;
        this.speed = clamp(speed, 0.4D, 1.0D);
        this.stopDistance = clamp(stopDistance, 1.0D, 4.0D);
        this.requestedOperationId = operationId;
        this.routePolicy = Objects.requireNonNull(routePolicy, "routePolicy");
        this.placementPolicy = Objects.requireNonNull(placementPolicy, "placementPolicy");
        if (maxPlacements < 0 || maxPlacements > 4096) {
            throw new IllegalArgumentException("max_placements must be between 0 and 4096");
        }
        this.maxPlacements = maxPlacements;
    }

    public static ReturnToPositionAction fromArgs(JsonObject args) {
        Objects.requireNonNull(args, "args");
        Set<String> allowed = Set.of("destination", "target", "speed", "stop_distance", "operation_id",
                "route_policy", "placement_policy", "max_placements");
        for (String name : args.keySet()) {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unsupported return_to_position field: " + name);
            }
        }
        boolean hasDestination = args.has("destination");
        boolean hasTarget = args.has("target");
        if (hasDestination == hasTarget) {
            throw new IllegalArgumentException(
                    "return_to_position requires exactly one of destination or target");
        }
        Destination destination = hasDestination
                ? Destination.fromWireName(requireString(args, "destination"))
                : Destination.EXPLICIT;
        JsonObject target = hasTarget ? requireObject(args, "target") : null;
        boolean hasX = target != null && target.has("x");
        boolean hasZ = target != null && target.has("z");
        if (hasTarget && hasX != hasZ) {
            throw new IllegalArgumentException(
                    "target.x and target.z must either both be present or both be omitted");
        }
        int y = hasTarget ? requireCoordinate(target, "y") : 0;
        BlockPos position = hasTarget
                ? new BlockPos(hasX ? requireCoordinate(target, "x") : 0,
                y, hasZ ? requireCoordinate(target, "z") : 0)
                : BlockPos.ZERO;
        double speed = optionalDouble(args, "speed", 0.7D);
        double stopDistance = optionalDouble(args, "stop_distance", 1.5D);
        requireRange(speed, "speed", 0.4D, 1.0D);
        requireRange(stopDistance, "stop_distance", 1.0D, 4.0D);
        UUID operationId = null;
        if (args.has("operation_id")) {
            try {
                operationId = UUID.fromString(requireString(args, "operation_id"));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("operation_id must be a UUID", invalid);
            }
        }
        RoutePolicy routePolicy = RoutePolicy.fromWireName(
                optionalString(args, "route_policy", "recorded_tunnels_first"));
        PlacementPolicy placementPolicy = PlacementPolicy.fromWireName(
                optionalString(args, "placement_policy", "safe_support_and_water_seal"));
        int maxPlacements = optionalInt(args, "max_placements", 0);
        return new ReturnToPositionAction(position, hasTarget && !hasX,
                speed, stopDistance, operationId, routePolicy,
                placementPolicy, maxPlacements, destination);
    }

    @Override
    public MaidActionKind kind() {
        return MaidActionKind.RETURN_TO_POSITION;
    }

    @Override
    public Set<MaidActionResource> resources() {
        return placementPolicy == PlacementPolicy.DISABLED
                ? Set.of(MaidActionResource.MOVE, MaidActionResource.HAND,
                MaidActionResource.BREAK)
                : Set.of(MaidActionResource.MOVE, MaidActionResource.HAND,
                MaidActionResource.BREAK, MaidActionResource.PLACE);
    }

    @Override
    public void start(MaidActionContext context) {
        started = true;
        if (destination == Destination.EXPLICIT && inferHorizontalTarget) {
            BlockPos live = context.maid().blockPosition();
            target = new BlockPos(live.getX(), target.getY(), live.getZ());
        }
        report(context, Stage.VALIDATING, detail("validating_return_target"));
    }

    @Override
    public MaidActionTickResult tick(MaidActionContext context) {
        if (!started) {
            start(context);
        }
        return switch (stage) {
            case VALIDATING -> validateAndPrepare(context);
            case PLANNING -> planNextLeg(context);
            case PATHFINDING -> advanceSearch(context);
            case RETURNING -> advanceNavigator(context);
            case ARRIVED -> MaidActionTickResult.succeeded(result(context, "completed"));
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
            context.maid().getNavigation().stop();
        }
    }

    @Override
    public JsonObject terminationResult(MaidActionContext context, ActionEndReason reason) {
        String message = reason == null ? "internal_error"
                : reason.name().toLowerCase(Locale.ROOT);
        return result(context, message);
    }

    private MaidActionTickResult validateAndPrepare(MaidActionContext context) {
        Optional<MiningWorldModelSavedData.OperationSnapshot> snapshot =
                findMiningOperation(context);
        if (shouldReplayRecordedRoute(context)) {
            snapshot.filter(value -> value.routeBreadcrumbs().size() >= 2)
                    .ifPresent(value -> prepareRecordedRoute(
                            context.maid().blockPosition(), value));
        }
        MaidActionTickResult targetFailure = resolveSemanticTarget(context, snapshot);
        if (targetFailure != null) {
            return targetFailure;
        }

        // A recorded return may legitimately end in a chunk that is not yet
        // loaded at action start.  The maid/player can bring successive route
        // chunks into range while walking; direct replanning still refuses an
        // unloaded target and never force-loads it.
        if (!routeSource.startsWith("recorded")
                && !context.level().hasChunkAt(target)) {
            return fail(context, ActionEndReason.PATH_NOT_FOUND, "target_chunk_not_loaded");
        }
        if (!attachBestAvailableTool(context)) {
            return fail(context, ActionEndReason.HAND_CONFLICT,
                    "return_tool_lease_could_not_be_attached");
        }

        if (waypoints.isEmpty() || !waypoints.getLast().equals(target)) {
            waypoints.add(target);
        }
        stage = Stage.PLANNING;
        report(context, stage, detail(routeSource));
        return MaidActionTickResult.running();
    }

    private boolean shouldReplayRecordedRoute(MaidActionContext context) {
        if (routePolicy != RoutePolicy.RECORDED_TUNNELS_FIRST) {
            return false;
        }
        if (destination != Destination.PLAYER) {
            return true;
        }
        LivingEntity owner = context.maid().getOwner();
        return owner == null || owner.level() != context.level()
                || context.maid().distanceTo(owner) > 12.0F;
    }

    private Optional<MiningWorldModelSavedData.OperationSnapshot> findMiningOperation(
            MaidActionContext context) {
        MiningWorldModelSavedData model = MiningWorldModelSavedData.get(context.level());
        if (requestedOperationId != null) {
            return model.operation(requestedOperationId)
                    .filter(value -> value.maidId().equals(context.maid().getUUID()))
                    .filter(value -> value.originPos() != null);
        }
        return model.operations().stream()
                .filter(value -> value.maidId().equals(context.maid().getUUID()))
                .filter(value -> value.originPos() != null)
                .max(java.util.Comparator
                        .comparingLong(MiningWorldModelSavedData.OperationSnapshot::updatedGameTime)
                        .thenComparing(value -> value.operationId().toString()));
    }

    private MaidActionTickResult resolveSemanticTarget(
            MaidActionContext context,
            Optional<MiningWorldModelSavedData.OperationSnapshot> snapshot) {
        if (destination == Destination.EXPLICIT) {
            targetResolved = true;
            return null;
        }
        if (destination == Destination.PLAYER) {
            LivingEntity owner = context.maid().getOwner();
            if (owner == null || owner.level() != context.level()) {
                return fail(context, ActionEndReason.VALIDATION_FAILED,
                        "owner_not_available_in_maid_dimension");
            }
            target = owner.blockPosition().immutable();
            targetResolved = true;
            return null;
        }
        if (snapshot.isPresent()) {
            MiningWorldModelSavedData.OperationSnapshot operation = snapshot.orElseThrow();
            BlockPos origin = operation.originPos().immutable();
            if (destination == Destination.SURFACE
                    && context.level().hasChunkAt(origin)) {
                Optional<SurfaceResolution> resolution = surfaceTarget(context, origin);
                if (resolution.isEmpty()) {
                    markSurfaceResolutionFailed();
                    return fail(context, ActionEndReason.PATH_NOT_FOUND,
                            surfaceFailureReason);
                }
                applySurfaceResolution(resolution.orElseThrow());
            } else {
                target = origin;
                surfaceHeightPending = destination == Destination.SURFACE;
            }
            operationId = operation.operationId();
            targetResolved = true;
            return null;
        }
        if (destination == Destination.MINE_ENTRY) {
            return fail(context, ActionEndReason.VALIDATION_FAILED,
                    "mining_entry_not_recorded");
        }

        BlockPos live = context.maid().blockPosition();
        if (!context.level().hasChunkAt(live)) {
            return fail(context, ActionEndReason.PATH_NOT_FOUND,
                    "surface_column_not_loaded");
        }
        Optional<SurfaceResolution> resolution = surfaceTarget(context, live);
        if (resolution.isEmpty()) {
            markSurfaceResolutionFailed();
            return fail(context, ActionEndReason.PATH_NOT_FOUND,
                    surfaceFailureReason);
        }
        applySurfaceResolution(resolution.orElseThrow());
        targetResolved = true;
        return null;
    }

    private Optional<SurfaceResolution> surfaceTarget(
            MaidActionContext context, BlockPos horizontalAnchor) {
        if (!context.level().dimension().equals(Level.OVERWORLD)) {
            surfaceFailureReason = "surface_destination_not_supported_in_dimension";
            return Optional.empty();
        }
        surfaceFailureReason = "surface_platform_not_found";
        List<BlockPos> candidates = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        Set<Long> sampledColumns = new HashSet<>();
        collectSurfaceCandidates(context, horizontalAnchor,
                SURFACE_INITIAL_SAMPLE_RADIUS, sampledColumns, candidates, heights);
        if (candidates.isEmpty()) {
            collectSurfaceCandidates(context, horizontalAnchor,
                    SURFACE_MAX_SAMPLE_RADIUS, sampledColumns, candidates, heights);
        } else {
            int initialReference = surfaceReferenceHeight(heights);
            BlockPos initialNearest = nearestSurfaceCandidate(
                    horizontalAnchor, candidates);
            if (initialNearest.getY() < initialReference - SURFACE_DEPRESSION_DEPTH
                    || initialNearest.getY() < context.level().getSeaLevel() - 8) {
                collectSurfaceCandidates(context, horizontalAnchor,
                        SURFACE_MAX_SAMPLE_RADIUS, sampledColumns, candidates, heights);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        int referenceY = surfaceReferenceHeight(heights);
        BlockPos nearest = nearestSurfaceCandidate(horizontalAnchor, candidates);
        LivingEntity owner = context.maid().getOwner();
        if (owner != null && owner.level() == context.level() && owner.onGround()) {
            BlockPos ownerPos = owner.blockPosition().immutable();
            int ownerSurfaceY = context.level().getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    ownerPos.getX(), ownerPos.getZ());
            if (horizontalDistance(horizontalAnchor, ownerPos)
                    <= SURFACE_MAX_SAMPLE_RADIUS
                    && isStandableSurface(context, ownerPos)
                    && Math.abs(ownerPos.getY() - ownerSurfaceY) <= 3
                    && ownerPos.getY() >= referenceY - 2) {
                return Optional.of(new SurfaceResolution(ownerPos,
                        "nearby_grounded_owner", candidates.size(), referenceY));
            }
        }

        BlockPos selected = selectSurfaceCandidate(
                horizontalAnchor, candidates, referenceY);
        boolean escapedDepression = nearest.getY()
                < referenceY - SURFACE_DEPRESSION_DEPTH;
        return Optional.of(new SurfaceResolution(selected,
                escapedDepression ? "local_safe_platform" : "nearest_safe_surface",
                candidates.size(), referenceY));
    }

    private void collectSurfaceCandidates(
            MaidActionContext context, BlockPos horizontalAnchor, int radius,
            Set<Long> sampledColumns, List<BlockPos> candidates,
            List<Integer> heights) {
        for (int dx = -radius; dx <= radius; dx += SURFACE_SAMPLE_STEP) {
            for (int dz = -radius; dz <= radius; dz += SURFACE_SAMPLE_STEP) {
                int x = horizontalAnchor.getX() + dx;
                int z = horizontalAnchor.getZ() + dz;
                BlockPos columnProbe = new BlockPos(x, horizontalAnchor.getY(), z);
                if (!sampledColumns.add(columnProbe.asLong())) {
                    continue;
                }
                if (!context.level().hasChunkAt(columnProbe)) {
                    continue;
                }
                int y = context.level().getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos standing = new BlockPos(x, y, z);
                if (!isStandableSurface(context, standing)) {
                    continue;
                }
                candidates.add(standing);
                heights.add(y);
            }
        }
    }

    private static boolean isStandableSurface(
            MaidActionContext context, BlockPos standing) {
        if (standing.getY() <= context.level().getMinBuildHeight()
                || standing.getY() >= context.level().getMaxBuildHeight() - 1
                || !context.level().hasChunkAt(standing)) {
            return false;
        }
        BlockState feet = context.level().getBlockState(standing);
        BlockState head = context.level().getBlockState(standing.above());
        BlockPos supportPos = standing.below();
        BlockState support = context.level().getBlockState(supportPos);
        return feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && feet.getCollisionShape(context.level(), standing).isEmpty()
                && head.getCollisionShape(context.level(), standing.above()).isEmpty()
                && MaidTerrainWorldEvaluator.isSafeStandSupport(
                context.level(), supportPos, support);
    }

    static int surfaceReferenceHeight(List<Integer> heights) {
        if (heights == null || heights.isEmpty()) {
            throw new IllegalArgumentException("surface heights must not be empty");
        }
        List<Integer> sorted = new ArrayList<>(heights);
        sorted.sort(Integer::compareTo);
        int index = (int) Math.floor(0.75D * (sorted.size() - 1));
        return sorted.get(index);
    }

    static BlockPos selectSurfaceCandidate(
            BlockPos anchor, List<BlockPos> candidates, int referenceY) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("surface candidates must not be empty");
        }
        BlockPos nearest = nearestSurfaceCandidate(anchor, candidates);
        if (nearest.getY() >= referenceY - SURFACE_DEPRESSION_DEPTH) {
            return nearest;
        }
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (BlockPos candidate : candidates) {
            if (candidate.getY() < referenceY - 2
                    || candidate.getY() > referenceY + SURFACE_OUTLIER_HEIGHT) {
                continue;
            }
            double score = horizontalDistance(anchor, candidate)
                    + 4.0D * Math.abs(candidate.getY() - referenceY);
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best == null ? nearest : best;
    }

    private static BlockPos nearestSurfaceCandidate(
            BlockPos anchor, List<BlockPos> candidates) {
        return candidates.stream().min(java.util.Comparator
                .comparingInt((BlockPos position) -> horizontalDistance(anchor, position))
                .thenComparingInt(position -> Math.abs(position.getY() - anchor.getY()))
                .thenComparingLong(BlockPos::asLong)).orElseThrow();
    }

    private void applySurfaceResolution(SurfaceResolution resolution) {
        target = resolution.target();
        surfaceStrategy = resolution.strategy();
        surfaceSampleCount = resolution.sampleCount();
        surfaceReferenceY = resolution.referenceY();
    }

    private void markSurfaceResolutionFailed() {
        targetResolved = false;
        surfaceStrategy = surfaceFailureReason.equals(
                "surface_destination_not_supported_in_dimension")
                ? "unsupported_dimension" : "no_safe_platform";
        surfaceSampleCount = 0;
        surfaceReferenceY = 0;
    }

    private void prepareRecordedRoute(
            BlockPos current, MiningWorldModelSavedData.OperationSnapshot snapshot) {
        Optional<MiningReturnRoutePlanner.ReturnRoute> planned =
                MiningReturnRoutePlanner.planToEntry(snapshot, current, 12.0D);
        if (planned.isEmpty()) {
            return;
        }
        for (BlockPos position : planned.orElseThrow().waypoints()) {
            BlockPos waypoint = position.immutable();
            if (waypoints.isEmpty() || !waypoints.getLast().equals(waypoint)) {
                waypoints.add(waypoint);
            }
        }
        if (inferHorizontalTarget) {
            BlockPos entry = planned.orElseThrow().entry();
            target = new BlockPos(entry.getX(), target.getY(), entry.getZ());
        }
        operationId = snapshot.operationId();
        routeSource = "recorded_tunnel_breadcrumbs";
    }

    private MaidActionTickResult planNextLeg(MaidActionContext context) {
        BlockPos live = context.maid().blockPosition().immutable();
        while (waypointIndex < waypoints.size()
                && distance(live, waypoints.get(waypointIndex))
                <= (waypointIndex == waypoints.size() - 1 ? stopDistance : 0.25D)) {
            waypointIndex++;
        }
        if (waypointIndex >= waypoints.size()) {
            if (surfaceHeightPending) {
                surfaceHeightPending = false;
                Optional<SurfaceResolution> resolution = surfaceTarget(context, target);
                if (resolution.isEmpty()) {
                    markSurfaceResolutionFailed();
                    return fail(context, ActionEndReason.PATH_NOT_FOUND,
                            surfaceFailureReason);
                }
                applySurfaceResolution(resolution.orElseThrow());
                BlockPos resolvedSurface = target;
                if (distance(live, resolvedSurface) > stopDistance) {
                    waypoints.add(resolvedSurface);
                    report(context, Stage.PLANNING,
                            detail("surface_height_resolved"));
                    return MaidActionTickResult.running();
                }
            }
            stage = Stage.ARRIVED;
            report(context, stage, detail("return_complete"));
            return MaidActionTickResult.succeeded(result(context, "completed"));
        }

        BlockPos waypoint = waypoints.get(waypointIndex);
        MaidTerrainPath exact = exactWaypointBatch(context, live);
        if (exact != null) {
            beginNavigation(context, exact, activeLegWaypointCount);
            return MaidActionTickResult.running();
        }

        int horizontal = Math.max(8, Math.min(MAX_HORIZONTAL_SEARCH_RADIUS,
                horizontalDistance(live, waypoint) + 6));
        int vertical = Math.max(8, Math.min(MAX_VERTICAL_SEARCH_RADIUS,
                Math.abs(live.getY() - waypoint.getY()) + 6));
        if (horizontalDistance(live, waypoint) > MAX_HORIZONTAL_SEARCH_RADIUS
                || Math.abs(live.getY() - waypoint.getY()) > MAX_VERTICAL_SEARCH_RADIUS) {
            return fail(context, ActionEndReason.PATH_NOT_FOUND,
                    "return_waypoint_outside_safe_planning_window");
        }
        MaidTerrainWorldEvaluator evaluator = evaluator(context, live, horizontal, vertical);
        terrainSearch = new MaidTerrainSearch(live, Set.of(waypoint), evaluator,
                MAX_SEARCH_EXPANSIONS,
                EnumSet.of(MaidTerrainStep.Kind.TRAVERSE,
                        MaidTerrainStep.Kind.ASCEND, MaidTerrainStep.Kind.DESCEND));
        stage = Stage.PATHFINDING;
        report(context, stage, detail("planning_return_leg"));
        return MaidActionTickResult.running();
    }

    private MaidActionTickResult advanceSearch(MaidActionContext context) {
        if (terrainSearch == null) {
            return fail(context, ActionEndReason.INTERNAL_ERROR,
                    "return_search_runtime_missing");
        }
        MaidTerrainSearch.Status status = terrainSearch.advance(SEARCH_BUDGET_PER_TICK);
        if (status == MaidTerrainSearch.Status.SEARCHING) {
            JsonObject detail = detail("planning_return_leg");
            detail.addProperty("leg_expanded_nodes", terrainSearch.expandedNodes());
            report(context, stage, detail);
            return MaidActionTickResult.running();
        }
        expandedNodes += terrainSearch.expandedNodes();
        if (status == MaidTerrainSearch.Status.FAILED) {
            terrainSearch = null;
            ActionEndReason reason = lastReplanReason == null
                    ? ActionEndReason.PATH_NOT_FOUND : lastReplanReason;
            String message = !rejectedPlacementTargets.isEmpty()
                    ? "no_return_route_around_rejected_placement"
                    : lastReplanMessage == null
                    ? "return_route_not_found" : lastReplanMessage;
            return fail(context, reason, message);
        }
        MaidTerrainPath path = terrainSearch.result().orElse(null);
        terrainSearch = null;
        if (path == null || path.steps().isEmpty()) {
            return fail(context, ActionEndReason.PATH_NOT_FOUND,
                    "return_search_produced_empty_path");
        }
        if (!routeSource.startsWith("recorded")) {
            routeSource = "terrain_replan";
        }
        beginNavigation(context, path, 1);
        return MaidActionTickResult.running();
    }

    private void beginNavigation(
            MaidActionContext context, MaidTerrainPath path, int completedWaypoints) {
        activeLegWaypointCount = Math.max(1, completedWaypoints);
        navigator = new MaidTerrainNavigator(path, handLease, speed, true,
                placementPolicy != PlacementPolicy.DISABLED, remainingPlacementBudget());
        navigator.start(context);
        stage = Stage.RETURNING;
        report(context, stage, detail("following_return_route"));
    }

    private MaidActionTickResult advanceNavigator(MaidActionContext context) {
        if (navigator == null) {
            return fail(context, ActionEndReason.INTERNAL_ERROR,
                    "return_navigator_runtime_missing");
        }
        MaidTerrainNavigator.TickResult tick = navigator.tick(context);
        clearedBlocks += navigator.drainClearedBlocks().size();
        for (MaidTerrainNavigator.PlacedBlock placed : navigator.drainPlacedBlocks()) {
            placementsUsed++;
            if (placed.purpose() == MaidTerrainBuilder.Purpose.BRIDGE_SUPPORT) {
                bridgeSupportsPlaced++;
            } else if (placed.purpose() == MaidTerrainBuilder.Purpose.SEAL_FLUID) {
                waterSealsPlaced++;
            }
        }
        if (tick.detail().has("player_wait_ticks")) {
            playerWaitTicks = Math.max(playerWaitTicks,
                    tick.detail().get("player_wait_ticks").getAsInt());
        }
        if (tick.outcome() == MaidTerrainNavigator.Outcome.ARRIVED) {
            navigator = null;
            waypointIndex += activeLegWaypointCount;
            activeLegWaypointCount = 1;
            replans = 0;
            lastReplanReason = null;
            lastReplanMessage = null;
            lastExecutionFailure = null;
            stage = Stage.PLANNING;
            report(context, stage, detail("return_leg_complete"));
            return MaidActionTickResult.running();
        }
        if (tick.outcome() == MaidTerrainNavigator.Outcome.FAILED) {
            ActionEndReason reason = tick.reason() == null
                    ? ActionEndReason.INTERNAL_ERROR : tick.reason();
            String message = tick.detail().has("message")
                    ? tick.detail().get("message").getAsString()
                    : "return_navigation_failed";
            lastReplanReason = reason;
            lastReplanMessage = message;
            lastExecutionFailure = tick.detail().deepCopy();
            Optional<BlockPos> rejectedTarget = rejectedPlacementTarget(
                    reason, tick.detail());
            boolean learnedNewExclusion = rejectedTarget
                    .map(rejectedPlacementTargets::add)
                    .orElse(true);
            navigator = null;
            if (tick.replanRecommended() && learnedNewExclusion
                    && replans < MAX_REPLANS) {
                replans++;
                stage = Stage.PLANNING;
                report(context, stage, detail("repairing_return_route"));
                return MaidActionTickResult.running();
            }
            return fail(context, reason, message);
        }
        JsonObject detail = tick.detail().deepCopy();
        addCommonDetail(context, detail);
        report(context, stage, detail);
        return MaidActionTickResult.running();
    }

    private MaidTerrainPath exactWaypointBatch(
            MaidActionContext context, BlockPos from) {
        List<MaidTerrainStep> steps = new ArrayList<>();
        BlockPos cursor = from;
        double totalCost = 0.0D;
        int completedWaypoints = 0;
        int limit = Math.min(waypoints.size(), waypointIndex + 16);
        for (int index = waypointIndex; index < limit; index++) {
            MaidTerrainStep step = exactAdjacentStep(
                    context, cursor, waypoints.get(index));
            if (step == null) {
                break;
            }
            steps.add(step);
            totalCost += step.cost();
            cursor = step.to();
            completedWaypoints++;
        }
        if (steps.isEmpty()) {
            activeLegWaypointCount = 1;
            return null;
        }
        activeLegWaypointCount = completedWaypoints;
        return new MaidTerrainPath(steps, cursor, totalCost, 0);
    }

    private MaidTerrainStep exactAdjacentStep(
            MaidActionContext context, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) + Math.abs(dz) != 1 || Math.abs(dy) > 1) {
            return null;
        }
        MaidTerrainStep.Kind kind = dy == 0 ? MaidTerrainStep.Kind.TRAVERSE
                : dy > 0 ? MaidTerrainStep.Kind.ASCEND : MaidTerrainStep.Kind.DESCEND;
        List<BlockPos> clearance = switch (kind) {
            case TRAVERSE -> List.of(to, to.above());
            case ASCEND -> List.of(from.above(2), to, to.above());
            case DESCEND -> List.of(to, to.above(), to.above(2));
            case DIG_DOWN -> throw new IllegalStateException("return route never uses DIG_DOWN");
        };
        MaidTerrainWorldEvaluator evaluator = evaluator(context, from, 4, 4);
        double supportCost = evaluator.supportCost(to.below());
        if (!Double.isFinite(supportCost)) {
            return null;
        }
        List<BlockPos> toBreak = new ArrayList<>();
        double cost = 1.0D + supportCost;
        for (BlockPos cell : clearance) {
            double clearCost = evaluator.clearCost(cell);
            if (!Double.isFinite(clearCost)) {
                return null;
            }
            if (clearCost > 0.0D) {
                toBreak.add(cell.immutable());
                cost += clearCost;
            }
        }
        return new MaidTerrainStep(kind, from, to,
                clearance, toBreak, Math.max(0.01D, cost));
    }

    private MaidTerrainWorldEvaluator evaluator(
            MaidActionContext context, BlockPos origin, int horizontal, int vertical) {
        boolean construction = placementPolicy != PlacementPolicy.DISABLED;
        return new MaidTerrainWorldEvaluator(context.level(), context.maid(), origin,
                horizontal, vertical, true, ignored -> true,
                position -> construction && remainingPlacementBudget() > 0
                        && !rejectedPlacementTargets.contains(position));
    }

    private boolean attachBestAvailableTool(MaidActionContext context) {
        int slot = bestStoneToolSlot(context.maid());
        try {
            handLease = slot == HandLease.HELD_TOOL_SLOT
                    ? HandLease.heldTool(context.maid())
                    : HandLease.equipFromBackpack(context.maid(), slot);
        } catch (RuntimeException conflict) {
            return false;
        }
        if (!MaidActionStore.getInstance().attachHandLease(
                context.execution().actionId(), context.execution().generation(), handLease)) {
            handLease.release(context.maid());
            handLease = null;
            return false;
        }
        return true;
    }

    private static int bestStoneToolSlot(EntityMaid maid) {
        ItemStack held = maid.getMainHandItem();
        int bestSlot = HandLease.HELD_TOOL_SLOT;
        float bestSpeed = held.isCorrectToolForDrops(Blocks.STONE.defaultBlockState())
                ? held.getDestroySpeed(Blocks.STONE.defaultBlockState()) : -1.0F;
        IItemHandler inventory = maid.getAvailableBackpackInv();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isCorrectToolForDrops(Blocks.STONE.defaultBlockState())) {
                continue;
            }
            float speed = stack.getDestroySpeed(Blocks.STONE.defaultBlockState());
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private int remainingPlacementBudget() {
        if (placementPolicy == PlacementPolicy.DISABLED) {
            return 0;
        }
        return maxPlacements == 0 ? Integer.MAX_VALUE
                : Math.max(0, maxPlacements - placementsUsed);
    }

    private MaidActionTickResult fail(
            MaidActionContext context, ActionEndReason reason, String message) {
        JsonObject result = result(context, message);
        result.addProperty("blocked_reason", message);
        result.addProperty("decision_required", true);
        if (lastExecutionFailure != null) {
            result.add("execution_failure", lastExecutionFailure.deepCopy());
        }
        return MaidActionTickResult.failed(reason, result);
    }

    private JsonObject result(MaidActionContext context, String message) {
        JsonObject result = detail(message);
        BlockPos live = context == null || context.maid() == null
                ? BlockPos.ZERO : context.maid().blockPosition();
        result.add("real_end", position(live));
        if (targetResolved) {
            result.addProperty("distance_remaining", distance(live, target));
            result.addProperty("arrived", distance(live, target) <= stopDistance);
        } else {
            result.addProperty("arrived", false);
        }
        result.addProperty("player_route_preserved", true);
        return result;
    }

    private JsonObject detail(String message) {
        JsonObject detail = new JsonObject();
        detail.addProperty("destination", destination.wireName);
        detail.addProperty("surface_height_pending", surfaceHeightPending);
        if (destination == Destination.SURFACE) {
            detail.addProperty("surface_strategy", surfaceStrategy);
            if (surfaceSampleCount > 0) {
                detail.addProperty("surface_sample_count", surfaceSampleCount);
                detail.addProperty("surface_reference_y", surfaceReferenceY);
            }
        }
        if (targetResolved) {
            detail.add("target", position(target));
        }
        detail.addProperty("target_horizontal_source",
                destination != Destination.EXPLICIT
                        ? destination.wireName
                        : inferHorizontalTarget
                        ? (operationId == null ? "maid_current_position" : "mining_entry")
                        : "explicit");
        if (operationId != null) {
            detail.addProperty("operation_id", operationId.toString());
        }
        detail.addProperty("route_source", routeSource);
        detail.addProperty("waypoints_total", waypoints.size());
        detail.addProperty("waypoints_completed", waypointIndex);
        detail.addProperty("cleared_blocks", clearedBlocks);
        detail.addProperty("placements_used", placementsUsed);
        detail.addProperty("bridge_supports_placed", bridgeSupportsPlaced);
        detail.addProperty("water_seals_placed", waterSealsPlaced);
        detail.addProperty("player_wait_ticks", playerWaitTicks);
        detail.addProperty("planner_expanded_nodes", expandedNodes);
        detail.addProperty("terrain_replans", replans);
        detail.addProperty("rejected_placement_targets",
                rejectedPlacementTargets.size());
        detail.addProperty("message", message);
        return detail;
    }

    /**
     * Extracts a placement coordinate only for a placement transaction that
     * the server or a protection hook rejected. Other construction failures
     * (missing material, range, player occupancy, changed terrain) must not
     * poison future route searches.
     */
    static Optional<BlockPos> rejectedPlacementTarget(
            ActionEndReason reason, JsonObject detail) {
        if (reason != ActionEndReason.BLOCK_PROTECTED || detail == null) {
            return Optional.empty();
        }
        try {
            if (!detail.has("placement_status")
                    || !"PLACE_REJECTED".equals(
                    detail.get("placement_status").getAsString())
                    || !detail.has("placement_x")
                    || !detail.has("placement_y")
                    || !detail.has("placement_z")) {
                return Optional.empty();
            }
            return Optional.of(new BlockPos(
                    detail.get("placement_x").getAsInt(),
                    detail.get("placement_y").getAsInt(),
                    detail.get("placement_z").getAsInt()));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private void addCommonDetail(MaidActionContext context, JsonObject detail) {
        JsonObject common = detail("following_return_route");
        for (String key : common.keySet()) {
            detail.add(key, common.get(key));
        }
        detail.add("real_end", position(context.maid().blockPosition()));
    }

    private void report(MaidActionContext context, Stage next, JsonObject detail) {
        stage = next;
        double progress = waypoints.isEmpty() ? 0.0D
                : Math.min(0.99D, waypointIndex / (double) waypoints.size());
        if (next == Stage.ARRIVED) {
            progress = 1.0D;
        }
        context.execution().reportProgress(next.wireName, progress, detail);
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static long squaredDistance(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dy = (long) first.getY() - second.getY();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distance(BlockPos first, BlockPos second) {
        return Math.sqrt(squaredDistance(first, second));
    }

    private static int horizontalDistance(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX())
                + Math.abs(first.getZ() - second.getZ());
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

    private static String optionalString(JsonObject parent, String name, String fallback) {
        return parent.has(name) ? requireString(parent, name) : fallback;
    }

    private static int requireCoordinate(JsonObject parent, String name) {
        if (!parent.has(name) || !parent.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        int value;
        try {
            value = parent.get(name).getAsInt();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
        if (value < -30_000_000 || value > 30_000_000) {
            throw new IllegalArgumentException(name + " must be between -30000000 and 30000000");
        }
        return value;
    }

    private static int optionalInt(JsonObject parent, String name, int fallback) {
        if (!parent.has(name)) {
            return fallback;
        }
        try {
            return parent.get(name).getAsInt();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }

    private static double optionalDouble(JsonObject parent, String name, double fallback) {
        if (!parent.has(name)) {
            return fallback;
        }
        try {
            double value = parent.get(name).getAsDouble();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " must be a number", invalid);
        }
    }

    private static void requireRange(double value, String name, double min, double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    enum RoutePolicy {
        RECORDED_TUNNELS_FIRST("recorded_tunnels_first"),
        SAFE_SHORTEST("safe_shortest");

        private final String wireName;

        RoutePolicy(String wireName) {
            this.wireName = wireName;
        }

        static RoutePolicy fromWireName(String value) {
            for (RoutePolicy policy : values()) {
                if (policy.wireName.equals(value.toLowerCase(Locale.ROOT))) {
                    return policy;
                }
            }
            throw new IllegalArgumentException(
                    "route_policy must be recorded_tunnels_first or safe_shortest");
        }
    }

    enum PlacementPolicy {
        DISABLED("disabled"),
        SAFE_SUPPORT_AND_WATER_SEAL("safe_support_and_water_seal");

        private final String wireName;

        PlacementPolicy(String wireName) {
            this.wireName = wireName;
        }

        static PlacementPolicy fromWireName(String value) {
            for (PlacementPolicy policy : values()) {
                if (policy.wireName.equals(value.toLowerCase(Locale.ROOT))) {
                    return policy;
                }
            }
            throw new IllegalArgumentException(
                    "placement_policy must be disabled or safe_support_and_water_seal");
        }
    }

    enum Destination {
        EXPLICIT("explicit"),
        SURFACE("surface"),
        MINE_ENTRY("mine_entry"),
        PLAYER("player");

        private final String wireName;

        Destination(String wireName) {
            this.wireName = wireName;
        }

        static Destination fromWireName(String value) {
            for (Destination destination : values()) {
                if (destination != EXPLICIT
                        && destination.wireName.equals(
                        value.toLowerCase(Locale.ROOT))) {
                    return destination;
                }
            }
            throw new IllegalArgumentException(
                    "destination must be surface, mine_entry or player");
        }
    }

    private enum Stage {
        VALIDATING("validating"),
        PLANNING("planning_return"),
        PATHFINDING("pathfinding"),
        RETURNING("returning"),
        ARRIVED("arrived");

        private final String wireName;

        Stage(String wireName) {
            this.wireName = wireName;
        }
    }

    private record SurfaceResolution(
            BlockPos target, String strategy, int sampleCount, int referenceY) {
    }
}
