package com.neko_tlm_bridge.tlm.agent.action;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Chooses one mining route from already-observed route facts.
 *
 * <p>This class deliberately has no Minecraft or Gson dependencies. The live
 * action remains responsible for discovering candidates and executing the
 * selected candidate; this planner only compares their expected cost. All
 * inputs and outputs are immutable so a decision can also be attached to an
 * action progress report without consulting the world again.</p>
 */
public final class MiningPlanner {
    private static final String POLICY_NAME = "weighted_route_cost_v1";

    private final Weights weights;

    public MiningPlanner() {
        this(Weights.defaults());
    }

    public MiningPlanner(Weights weights) {
        this.weights = Objects.requireNonNull(weights, "weights");
    }

    public Weights weights() {
        return weights;
    }

    /**
     * Ranks the supplied candidates and selects the lowest total score.
     *
     * <p>The list order is used only as the last tie breaker for completely
     * identical candidates. Every meaningful tie is resolved from immutable
     * candidate facts, keeping the choice stable when discovery order changes.</p>
     */
    public Decision plan(List<Candidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            return new Decision(Optional.empty(), new Diagnostics(
                    Status.NO_CANDIDATES,
                    "no_candidates",
                    POLICY_NAME,
                    weights,
                    List.of()));
        }

        List<IndexedScore> scores = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = Objects.requireNonNull(candidates.get(index),
                    "candidates[" + index + "]");
            scores.add(new IndexedScore(index, candidate, score(candidate)));
        }
        scores.sort(scoreComparator());

        List<CandidateScore> ranking = new ArrayList<>(scores.size());
        for (int index = 0; index < scores.size(); index++) {
            IndexedScore scored = scores.get(index);
            ranking.add(new CandidateScore(
                    index + 1,
                    scored.candidate(),
                    scored.breakdown(),
                    index == 0));
        }
        List<CandidateScore> frozenRanking = List.copyOf(ranking);
        Candidate selected = frozenRanking.getFirst().candidate();
        return new Decision(Optional.of(selected), new Diagnostics(
                Status.SELECTED,
                "minimum_total_score",
                POLICY_NAME,
                weights,
                frozenRanking));
    }

    /** Calculates the score components without performing a selection. */
    public ScoreBreakdown score(Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");

        double estimatedTime = weights.baseStepTime()
                + candidate.breakCost() * weights.breakTimeMultiplier()
                + candidate.supportCost() * weights.supportTimeMultiplier()
                + candidate.constructionCost()
                * weights.constructionTimeMultiplier();
        double riskPenalty = candidate.risk() * weights.riskMultiplier();
        double materialPenalty = (candidate.supportCost()
                + candidate.constructionCost()) * weights.materialMultiplier();
        double preferenceAdjustment = 0.0D;
        if (candidate.towardTargetLayer()) {
            preferenceAdjustment -= weights.targetLayerReward();
        }
        if (candidate.naturalPassage()) {
            preferenceAdjustment -= weights.naturalPassageReward();
        }
        if (candidate.recentlyVisited()) {
            preferenceAdjustment += weights.recentVisitPenalty();
        }
        double totalScore = estimatedTime + riskPenalty
                + materialPenalty + preferenceAdjustment;
        return new ScoreBreakdown(estimatedTime, riskPenalty,
                materialPenalty, preferenceAdjustment, totalScore);
    }

    private static Comparator<IndexedScore> scoreComparator() {
        return Comparator
                .comparingDouble((IndexedScore score) ->
                        score.breakdown().totalScore())
                .thenComparingDouble(score -> score.breakdown().riskPenalty())
                .thenComparingDouble(score -> score.breakdown().estimatedTime())
                .thenComparingDouble(score -> score.breakdown().materialPenalty())
                .thenComparingDouble(score ->
                        score.breakdown().preferenceAdjustment())
                .thenComparing(score -> score.candidate().id())
                .thenComparing(score -> score.candidate().direction())
                .thenComparing(score -> score.candidate().shape())
                .thenComparingDouble(score -> score.candidate().breakCost())
                .thenComparingDouble(score -> score.candidate().supportCost())
                .thenComparingDouble(score ->
                        score.candidate().constructionCost())
                .thenComparingDouble(score -> score.candidate().risk())
                .thenComparing(score -> score.candidate().towardTargetLayer())
                .thenComparing(score -> score.candidate().naturalPassage())
                .thenComparing(score -> score.candidate().recentlyVisited())
                .thenComparingInt(IndexedScore::inputIndex);
    }

    /** Immutable facts about one adjacent move or short route option. */
    public record Candidate(
            String id,
            String direction,
            String shape,
            double breakCost,
            double supportCost,
            double constructionCost,
            double risk,
            boolean towardTargetLayer,
            boolean naturalPassage,
            boolean recentlyVisited
    ) {
        public Candidate {
            id = requireText(id, "id");
            direction = normalizeText(direction, "direction");
            shape = normalizeText(shape, "shape");
            requireNonNegativeFinite(breakCost, "breakCost");
            requireNonNegativeFinite(supportCost, "supportCost");
            requireNonNegativeFinite(constructionCost, "constructionCost");
            requireNonNegativeFinite(risk, "risk");
        }

        public static Builder builder(String id, String direction, String shape) {
            return new Builder(id, direction, shape);
        }

        /** Mutable assembly helper; {@link #build()} always returns a snapshot. */
        public static final class Builder {
            private final String id;
            private final String direction;
            private final String shape;
            private double breakCost;
            private double supportCost;
            private double constructionCost;
            private double risk;
            private boolean towardTargetLayer;
            private boolean naturalPassage;
            private boolean recentlyVisited;

            private Builder(String id, String direction, String shape) {
                this.id = id;
                this.direction = direction;
                this.shape = shape;
            }

            public Builder breakCost(double value) {
                breakCost = value;
                return this;
            }

            public Builder supportCost(double value) {
                supportCost = value;
                return this;
            }

            public Builder constructionCost(double value) {
                constructionCost = value;
                return this;
            }

            public Builder risk(double value) {
                risk = value;
                return this;
            }

            public Builder towardTargetLayer(boolean value) {
                towardTargetLayer = value;
                return this;
            }

            public Builder naturalPassage(boolean value) {
                naturalPassage = value;
                return this;
            }

            public Builder recentlyVisited(boolean value) {
                recentlyVisited = value;
                return this;
            }

            public Candidate build() {
                return new Candidate(id, direction, shape, breakCost,
                        supportCost, constructionCost, risk,
                        towardTargetLayer, naturalPassage, recentlyVisited);
            }
        }
    }

    /** Tunable policy constants. Costs and rewards must all be finite and non-negative. */
    public record Weights(
            double baseStepTime,
            double breakTimeMultiplier,
            double supportTimeMultiplier,
            double constructionTimeMultiplier,
            double riskMultiplier,
            double materialMultiplier,
            double targetLayerReward,
            double naturalPassageReward,
            double recentVisitPenalty
    ) {
        public Weights {
            requireNonNegativeFinite(baseStepTime, "baseStepTime");
            requireNonNegativeFinite(breakTimeMultiplier,
                    "breakTimeMultiplier");
            requireNonNegativeFinite(supportTimeMultiplier,
                    "supportTimeMultiplier");
            requireNonNegativeFinite(constructionTimeMultiplier,
                    "constructionTimeMultiplier");
            requireNonNegativeFinite(riskMultiplier, "riskMultiplier");
            requireNonNegativeFinite(materialMultiplier, "materialMultiplier");
            requireNonNegativeFinite(targetLayerReward, "targetLayerReward");
            requireNonNegativeFinite(naturalPassageReward,
                    "naturalPassageReward");
            requireNonNegativeFinite(recentVisitPenalty, "recentVisitPenalty");
        }

        public static Weights defaults() {
            return new Weights(
                    1.0D,
                    1.0D,
                    1.0D,
                    1.0D,
                    12.0D,
                    2.0D,
                    3.0D,
                    2.0D,
                    8.0D);
        }
    }

    /** Weighted score components retained for progress and terminal diagnostics. */
    public record ScoreBreakdown(
            double estimatedTime,
            double riskPenalty,
            double materialPenalty,
            double preferenceAdjustment,
            double totalScore
    ) {
    }

    /** One ranked diagnostic row. Rank is one-based. */
    public record CandidateScore(
            int rank,
            Candidate candidate,
            ScoreBreakdown score,
            boolean selected
    ) {
        public CandidateScore {
            if (rank < 1) {
                throw new IllegalArgumentException("rank must be positive");
            }
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(score, "score");
        }
    }

    /** Complete deterministic explanation of a selection attempt. */
    public record Diagnostics(
            Status status,
            String reason,
            String policy,
            Weights weights,
            List<CandidateScore> ranking
    ) {
        public Diagnostics {
            Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            policy = requireText(policy, "policy");
            Objects.requireNonNull(weights, "weights");
            ranking = List.copyOf(Objects.requireNonNull(ranking, "ranking"));
        }
    }

    /** The selected route plus the diagnostics used to reach that decision. */
    public record Decision(
            Optional<Candidate> selected,
            Diagnostics diagnostics
    ) {
        public Decision {
            Objects.requireNonNull(selected, "selected");
            Objects.requireNonNull(diagnostics, "diagnostics");
        }

        public Optional<CandidateScore> selectedScore() {
            return diagnostics.ranking().stream()
                    .filter(CandidateScore::selected)
                    .findFirst();
        }
    }

    public enum Status {
        SELECTED,
        NO_CANDIDATES
    }

    private record IndexedScore(
            int inputIndex,
            Candidate candidate,
            ScoreBreakdown breakdown
    ) {
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }

    private static String normalizeText(String value, String name) {
        return requireText(value, name).toLowerCase(Locale.ROOT);
    }

    private static void requireNonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-negative");
        }
    }
}
