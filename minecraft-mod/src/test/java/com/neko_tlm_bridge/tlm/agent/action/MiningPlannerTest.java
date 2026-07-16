package com.neko_tlm_bridge.tlm.agent.action;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningPlannerTest {
    private static final double DELTA = 0.000_001D;

    @Test
    void safeNaturalPassageBeatsSlowerExcavation() {
        MiningPlanner planner = new MiningPlanner();
        MiningPlanner.Candidate excavation = candidate(
                "excavate_north", "north", "staircase_down")
                .breakCost(4.0D)
                .towardTargetLayer(true)
                .build();
        MiningPlanner.Candidate passage = candidate(
                "cave_east", "east", "natural_passage")
                .naturalPassage(true)
                .build();

        MiningPlanner.Decision decision = planner.plan(
                List.of(excavation, passage));

        assertEquals("cave_east", decision.selected().orElseThrow().id());
        assertEquals(MiningPlanner.Status.SELECTED,
                decision.diagnostics().status());
        assertEquals("minimum_total_score", decision.diagnostics().reason());
        assertEquals(List.of("cave_east", "excavate_north"),
                decision.diagnostics().ranking().stream()
                        .map(row -> row.candidate().id())
                        .toList());
        assertTrue(decision.diagnostics().ranking().getFirst().selected());
        assertFalse(decision.diagnostics().ranking().getLast().selected());
    }

    @Test
    void targetProgressAndRecentVisitInfluenceOtherwiseEqualRoutes() {
        MiningPlanner planner = new MiningPlanner();
        MiningPlanner.Candidate flat = candidate(
                "flat", "north", "natural_passage")
                .naturalPassage(true)
                .build();
        MiningPlanner.Candidate descending = candidate(
                "descending", "east", "natural_passage")
                .naturalPassage(true)
                .towardTargetLayer(true)
                .build();

        assertEquals("descending", planner.plan(List.of(flat, descending))
                .selected().orElseThrow().id());

        MiningPlanner.Candidate visitedDescending = candidate(
                "visited_descending", "east", "natural_passage")
                .naturalPassage(true)
                .towardTargetLayer(true)
                .recentlyVisited(true)
                .build();
        assertEquals("flat", planner.plan(List.of(flat, visitedDescending))
                .selected().orElseThrow().id());
    }

    @Test
    void riskCanMakeShortNaturalRouteWorseThanSafeExcavation() {
        MiningPlanner planner = new MiningPlanner();
        MiningPlanner.Candidate riskyCave = candidate(
                "risky_cave", "north", "natural_passage")
                .naturalPassage(true)
                .risk(0.75D)
                .build();
        MiningPlanner.Candidate safeTunnel = candidate(
                "safe_tunnel", "west", "level")
                .breakCost(3.0D)
                .build();

        MiningPlanner.Decision decision = planner.plan(
                List.of(riskyCave, safeTunnel));

        assertEquals("safe_tunnel", decision.selected().orElseThrow().id());
        MiningPlanner.ScoreBreakdown riskyScore = decision.diagnostics()
                .ranking().getLast().score();
        assertEquals(9.0D, riskyScore.riskPenalty(), DELTA);
    }

    @Test
    void scoreExposesTimeRiskMaterialAndPreferenceComponents() {
        MiningPlanner planner = new MiningPlanner();
        MiningPlanner.Candidate candidate = candidate(
                "bridge_down", "south", "staircase_down")
                .breakCost(2.0D)
                .supportCost(1.0D)
                .constructionCost(2.0D)
                .risk(0.25D)
                .towardTargetLayer(true)
                .build();

        MiningPlanner.ScoreBreakdown score = planner.score(candidate);

        assertEquals(6.0D, score.estimatedTime(), DELTA);
        assertEquals(3.0D, score.riskPenalty(), DELTA);
        assertEquals(6.0D, score.materialPenalty(), DELTA);
        assertEquals(-3.0D, score.preferenceAdjustment(), DELTA);
        assertEquals(12.0D, score.totalScore(), DELTA);
    }

    @Test
    void meaningfulTiesDoNotDependOnDiscoveryOrder() {
        MiningPlanner planner = new MiningPlanner();
        MiningPlanner.Candidate west = candidate(
                "b_west", "west", "level")
                .breakCost(1.0D)
                .build();
        MiningPlanner.Candidate east = candidate(
                "a_east", "east", "level")
                .breakCost(1.0D)
                .build();

        assertEquals("a_east", planner.plan(List.of(west, east))
                .selected().orElseThrow().id());
        assertEquals("a_east", planner.plan(List.of(east, west))
                .selected().orElseThrow().id());
    }

    @Test
    void emptyInputReturnsStructuredNoCandidateDiagnostic() {
        MiningPlanner.Decision decision = new MiningPlanner().plan(List.of());

        assertTrue(decision.selected().isEmpty());
        assertTrue(decision.selectedScore().isEmpty());
        assertEquals(MiningPlanner.Status.NO_CANDIDATES,
                decision.diagnostics().status());
        assertEquals("no_candidates", decision.diagnostics().reason());
        assertEquals("weighted_route_cost_v1",
                decision.diagnostics().policy());
        assertTrue(decision.diagnostics().ranking().isEmpty());
    }

    @Test
    void rejectsInvalidFactsAndWeightsAtTheBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> candidate("invalid", "north", "level")
                        .breakCost(-1.0D)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> candidate("invalid", "north", "level")
                        .risk(Double.NaN)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> new MiningPlanner.Weights(
                        1.0D, 1.0D, 1.0D, 1.0D,
                        -1.0D, 1.0D, 1.0D, 1.0D, 1.0D));
        assertThrows(NullPointerException.class,
                () -> new MiningPlanner().plan(null));
        assertThrows(NullPointerException.class,
                () -> new MiningPlanner().plan(java.util.Arrays.asList(
                        candidate("valid", "north", "level").build(), null)));
    }

    @Test
    void builderNormalizesWireLikeDirectionAndShape() {
        MiningPlanner.Candidate candidate = candidate(
                " step-1 ", " NORTH ", " Staircase_Down ").build();

        assertEquals("step-1", candidate.id());
        assertEquals("north", candidate.direction());
        assertEquals("staircase_down", candidate.shape());
    }

    private static MiningPlanner.Candidate.Builder candidate(
            String id, String direction, String shape) {
        return MiningPlanner.Candidate.builder(id, direction, shape);
    }
}
