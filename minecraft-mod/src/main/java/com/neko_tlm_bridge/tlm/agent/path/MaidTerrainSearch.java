package com.neko_tlm_bridge.tlm.agent.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Incremental, deterministic weighted A* over a small set of maid terrain moves.
 * The evaluator is consulted only by {@link #advance(int)}, allowing a caller to
 * split a live-world search across server ticks.
 */
public final class MaidTerrainSearch {
    private static final double HEURISTIC_WEIGHT = 1.25D;
    private static final double EPSILON = 1.0E-9D;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private static final Comparator<SearchNode> NODE_ORDER = Comparator
            .comparingDouble(SearchNode::weightedScore)
            .thenComparingDouble(SearchNode::heuristic)
            .thenComparingDouble(SearchNode::cost)
            .thenComparingInt(node -> node.pos().getY())
            .thenComparingInt(node -> node.pos().getX())
            .thenComparingInt(node -> node.pos().getZ())
            .thenComparingLong(SearchNode::sequence);

    private final BlockPos start;
    private final List<BlockPos> goals;
    private final Set<Long> goalKeys;
    private final MaidTerrainNodeEvaluator evaluator;
    private final int maxExpanded;
    private final Set<MaidTerrainStep.Kind> allowedKinds;
    private final PriorityQueue<SearchNode> open = new PriorityQueue<>(NODE_ORDER);
    private final Map<Long, Double> bestCosts = new HashMap<>();
    private final Set<Long> closed = new HashSet<>();

    private Status status = Status.SEARCHING;
    private MaidTerrainPath result;
    private int expandedNodes;
    private long nextSequence;

    public MaidTerrainSearch(BlockPos start, Set<BlockPos> goals,
                             MaidTerrainNodeEvaluator evaluator, int maxExpanded) {
        this(start, goals, evaluator, maxExpanded,
                EnumSet.allOf(MaidTerrainStep.Kind.class));
    }

    public MaidTerrainSearch(BlockPos start, Set<BlockPos> goals,
                             MaidTerrainNodeEvaluator evaluator, int maxExpanded,
                             Set<MaidTerrainStep.Kind> allowedKinds) {
        this.start = Objects.requireNonNull(start, "start").immutable();
        Objects.requireNonNull(goals, "goals");
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("goals must not be empty");
        }
        this.goals = goals.stream()
                .map(pos -> Objects.requireNonNull(pos, "goal").immutable())
                .distinct()
                .sorted(Comparator.<BlockPos>comparingInt(pos -> pos.getY())
                        .thenComparingInt(pos -> pos.getX())
                        .thenComparingInt(pos -> pos.getZ()))
                .toList();
        this.goalKeys = new HashSet<>(this.goals.size());
        for (BlockPos goal : this.goals) {
            goalKeys.add(goal.asLong());
        }
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        if (maxExpanded <= 0) {
            throw new IllegalArgumentException("maxExpanded must be positive");
        }
        this.maxExpanded = maxExpanded;
        Objects.requireNonNull(allowedKinds, "allowedKinds");
        if (allowedKinds.isEmpty()) {
            throw new IllegalArgumentException("allowedKinds must not be empty");
        }
        this.allowedKinds = Set.copyOf(allowedKinds);

        double heuristic = heuristic(this.start);
        SearchNode root = new SearchNode(this.start, 0.0D, heuristic,
                HEURISTIC_WEIGHT * heuristic, null, null, nextSequence++);
        open.add(root);
        bestCosts.put(this.start.asLong(), 0.0D);
    }

    /** Advances the search by at most {@code budget} expanded nodes. */
    public Status advance(int budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("budget must be positive");
        }
        if (status != Status.SEARCHING) {
            return status;
        }

        int expandedThisCall = 0;
        while (expandedThisCall < budget) {
            SearchNode current = pollBestNode();
            if (current == null) {
                status = Status.FAILED;
                return status;
            }
            if (goalKeys.contains(current.pos().asLong())) {
                result = buildPath(current);
                status = Status.FOUND;
                return status;
            }
            // Goal nodes do not consume the expansion budget. In particular,
            // maxExpanded=1 must still find a goal generated while expanding
            // the start node on the following advance call.
            if (expandedNodes >= maxExpanded) {
                status = Status.FAILED;
                return status;
            }

            closed.add(current.pos().asLong());
            expandedNodes++;
            expandedThisCall++;
            expand(current);
        }

        if (open.isEmpty()) {
            status = Status.FAILED;
        }
        return status;
    }

    public Status status() {
        return status;
    }

    public Optional<MaidTerrainPath> result() {
        return Optional.ofNullable(result);
    }

    public int expandedNodes() {
        return expandedNodes;
    }

    private SearchNode pollBestNode() {
        while (!open.isEmpty()) {
            SearchNode node = open.poll();
            long key = node.pos().asLong();
            Double best = bestCosts.get(key);
            if (best != null && node.cost() <= best + EPSILON && !closed.contains(key)) {
                return node;
            }
        }
        return null;
    }

    private void expand(SearchNode from) {
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            BlockPos horizontal = from.pos().relative(direction);
            if (allowedKinds.contains(MaidTerrainStep.Kind.TRAVERSE)) {
                offerStep(from, MaidTerrainStep.Kind.TRAVERSE, horizontal,
                        1.0D, horizontal, horizontal.above());
            }

            BlockPos up = horizontal.above();
            // A one-block jump sweeps the maid's head through the cell two
            // blocks above the source before her feet settle at {@code up}.
            // Checking only the destination's two cells would accept a low
            // ceiling over the take-off edge and the executor would collide.
            if (allowedKinds.contains(MaidTerrainStep.Kind.ASCEND)) {
                offerStep(from, MaidTerrainStep.Kind.ASCEND, up,
                        1.45D, from.pos().above(2), up, up.above());
            }

            BlockPos down = horizontal.below();
            // While stepping down, the maid enters the destination column
            // before her feet have fully dropped. Reserve the third cell for
            // that diagonal body sweep as well as the final two-cell stance.
            if (allowedKinds.contains(MaidTerrainStep.Kind.DESCEND)) {
                offerStep(from, MaidTerrainStep.Kind.DESCEND, down,
                        1.20D, down, down.above(), down.above(2));
            }
        }

        if (allowedKinds.contains(MaidTerrainStep.Kind.DIG_DOWN)) {
            BlockPos below = from.pos().below();
            offerStep(from, MaidTerrainStep.Kind.DIG_DOWN, below,
                    1.35D, below, below.above());
        }
    }

    private void offerStep(SearchNode from, MaidTerrainStep.Kind kind, BlockPos destination,
                           double movementCost, BlockPos... clearanceCells) {
        if (!evaluator.withinBounds(destination) || !evaluator.isLoaded(destination)
                || !evaluator.withinBounds(destination.below())
                || !evaluator.isLoaded(destination.below())
                || !evaluator.canStandOn(destination.below())) {
            return;
        }

        List<BlockPos> clearance = new ArrayList<>(clearanceCells.length);
        List<BlockPos> toBreak = new ArrayList<>(clearanceCells.length);
        double breakCost = 0.0D;
        for (BlockPos cell : clearanceCells) {
            BlockPos immutableCell = cell.immutable();
            if (clearance.contains(immutableCell)) {
                continue;
            }
            clearance.add(immutableCell);
            if (!evaluator.withinBounds(immutableCell) || !evaluator.isLoaded(immutableCell)) {
                return;
            }
            double cost = evaluator.clearCost(immutableCell);
            if (!Double.isFinite(cost) || cost < 0.0D) {
                return;
            }
            if (cost > 0.0D) {
                toBreak.add(immutableCell);
                breakCost += cost;
            }
        }

        double newCost = from.cost() + movementCost + breakCost;
        long key = destination.asLong();
        Double previous = bestCosts.get(key);
        if (previous != null && newCost >= previous - EPSILON) {
            return;
        }
        bestCosts.put(key, newCost);
        closed.remove(key);

        double heuristic = heuristic(destination);
        MaidTerrainStep step = new MaidTerrainStep(kind, from.pos(), destination,
                clearance, toBreak, movementCost + breakCost);
        open.add(new SearchNode(destination.immutable(), newCost, heuristic,
                newCost + HEURISTIC_WEIGHT * heuristic, from, step, nextSequence++));
    }

    private double heuristic(BlockPos pos) {
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos goal : goals) {
            double dx = goal.getX() - pos.getX();
            double dy = goal.getY() - pos.getY();
            double dz = goal.getZ() - pos.getZ();
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    private MaidTerrainPath buildPath(SearchNode target) {
        List<MaidTerrainStep> reversed = new ArrayList<>();
        for (SearchNode cursor = target; cursor != null && cursor.step() != null; cursor = cursor.parent()) {
            reversed.add(cursor.step());
        }
        List<MaidTerrainStep> steps = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            steps.add(reversed.get(i));
        }
        return new MaidTerrainPath(steps, target.pos(), target.cost(), expandedNodes);
    }

    public enum Status {
        SEARCHING,
        FOUND,
        FAILED
    }

    private record SearchNode(
            BlockPos pos,
            double cost,
            double heuristic,
            double weightedScore,
            SearchNode parent,
            MaidTerrainStep step,
            long sequence
    ) {
    }
}
