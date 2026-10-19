package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageReportBuilder.CoverageReport;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageReportBuilder.RootStatus;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageReportBuilder.TreeMetrics;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.Test;

class CoverageReportBuilderTest {
    private final POPPModel model = new POPPModel();
    private final CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(CoverageInput.of(model, null));

    private TreeNode<?> node(NodeKind kind, String description) {
        return model.createNode(kind, description, 0, 0);
    }

    private static Map<String, String> coverageById(CoverageReport report) {
        return report.roots().stream().collect(Collectors.toMap(RootStatus::id, RootStatus::coverage));
    }

    @Test
    void anEmptyModelHasAnEmptyReport() {
        CoverageReport report = CoverageReportBuilder.build(model, analyzer, CoverageMode.PLANNING);
        assertEquals("PLANNING", report.mode());
        assertTrue(report.roots().isEmpty());
        assertTrue(report.trees().isEmpty());
    }

    @Test
    void rootsOfCoverableKindsAreListedWithTheirCoverage() {
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");
        TreeNode<?> problem = node(NodeKind.PROBLEM, "problem");
        model.relate(goal, criterion);

        CoverageReport report = CoverageReportBuilder.build(model, analyzer, CoverageMode.PLANNING);

        assertEquals(Map.of(goal.getId(), "COVERED", criterion.getId(), "COVERED", problem.getId(), "UNCOVERED"),
                coverageById(report));
        RootStatus goalStatus = report.roots().stream().filter(r -> r.id().equals(goal.getId())).findFirst().orElseThrow();
        assertEquals("GOAL", goalStatus.kind());
        assertEquals("goal", goalStatus.description());
    }

    @Test
    void consequencesAndSolutionsAreNotListedButCounted() {
        node(NodeKind.CONSEQUENCE, "consequence");
        node(NodeKind.SOLUTION, "solution");

        CoverageReport report = CoverageReportBuilder.build(model, analyzer, CoverageMode.PLANNING);

        assertTrue(report.roots().isEmpty(), "neither kind can be covered through a relation");
        assertEquals(Map.of("CONSEQUENCE", new TreeMetrics(1, 1, 1, 1), "SOLUTION", new TreeMetrics(1, 1, 1, 1)),
                report.trees());
    }

    @Test
    void onlyRootsAreListed() {
        TreeNode<?> root = node(NodeKind.GOAL, "root");
        model.decompose(root, node(NodeKind.GOAL, "child"));

        CoverageReport report = CoverageReportBuilder.build(model, analyzer, CoverageMode.PLANNING);

        assertEquals(1, report.roots().size());
        assertEquals(root.getId(), report.roots().getFirst().id());
    }

    @Test
    void theModeDecidesWhetherCriteriaNeedProofs() {
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");

        assertEquals("COVERED", coverageById(CoverageReportBuilder.build(model, analyzer, CoverageMode.PLANNING))
                .get(criterion.getId()));
        CoverageReport verify = CoverageReportBuilder.build(model, analyzer, CoverageMode.VERIFY);
        assertEquals("VERIFY", verify.mode());
        assertEquals("UNCOVERED", coverageById(verify).get(criterion.getId()));

        TreeNode<?> proof = node(NodeKind.SUCCESS_PROOF, "proof");
        model.relate(proof, criterion);
        model.relate(node(NodeKind.SOLUTION, "solution"), proof);
        assertEquals("COVERED", coverageById(CoverageReportBuilder.build(model, analyzer, CoverageMode.VERIFY))
                .get(criterion.getId()));
    }

    @Test
    void treeMetricsCountRootsNodesAndTheHighestAndWidestTree() {
        // tree 1: root -> (a -> (a1, a2, a3), b): 6 nodes, height 3, 4 leaves
        TreeNode<?> root = node(NodeKind.GOAL, "root");
        TreeNode<?> a = node(NodeKind.GOAL, "a");
        model.decompose(root, a);
        model.decompose(root, node(NodeKind.GOAL, "b"));
        for (int i = 1; i <= 3; i++) model.decompose(a, node(NodeKind.GOAL, "a" + i));
        // tree 2: a single node
        node(NodeKind.GOAL, "other");

        TreeMetrics goals = CoverageReportBuilder.build(model, analyzer, CoverageMode.PLANNING).trees().get("GOAL");

        assertEquals(new TreeMetrics(2, 7, 3, 4), goals);
    }

    @Test
    void kindsWithoutNodesAreAbsent() {
        node(NodeKind.GOAL, "goal");
        assertEquals(java.util.Set.of("GOAL"),
                CoverageReportBuilder.build(model, analyzer, CoverageMode.PLANNING).trees().keySet());
    }

    /** P = AND(p1, p2); g1 inverts p1 and causes c1, which proof r1 validates; g2 inverts p2 and has no criterion. */
    private TreeNode<?> objectivesModel() {
        TreeNode<?> p = node(NodeKind.PROBLEM, "P");
        TreeNode<?> p1 = node(NodeKind.PROBLEM, "p1");
        TreeNode<?> p2 = node(NodeKind.PROBLEM, "p2");
        model.decompose(p, p1);
        model.decompose(p, p2);
        TreeNode<?> g1 = node(NodeKind.GOAL, "g1");
        TreeNode<?> g2 = node(NodeKind.GOAL, "g2");
        model.relate(p1, g1);
        model.relate(p2, g2);
        TreeNode<?> c1 = node(NodeKind.SUCCESS_CRITERIA, "c1");
        model.relate(g1, c1);
        model.relate(node(NodeKind.SUCCESS_PROOF, "r1"), c1);
        return p;
    }

    private static Map<String, Integer> counts(int uncovered, int partial, int covered) {
        return Map.of("UNCOVERED", uncovered, "PARTIAL", partial, "COVERED", covered);
    }

    private Map<String, CoverageReportBuilder.ModeSummary> summaries(CoverageMode selected) {
        return CoverageReportBuilder.build(model, analyzer, selected).modes().stream()
                .collect(Collectors.toMap(CoverageReportBuilder.ModeSummary::mode, m -> m));
    }

    @Test
    void everyModeIsSummarisedWhicheverIsSelected() {
        objectivesModel();
        for (CoverageMode selected : CoverageMode.presets()) {
            Map<String, CoverageReportBuilder.ModeSummary> byMode = summaries(selected);
            assertEquals(CoverageMode.presets().stream().map(CoverageMode::name).toList(),
                    CoverageReportBuilder.build(model, analyzer, selected).modes().stream()
                            .map(CoverageReportBuilder.ModeSummary::mode).toList(), "in preset order");

            var byGoals = byMode.get("PROBLEMS_BY_GOALS");
            assertEquals("PROBLEM", byGoals.kind());
            assertEquals("COVERED", byGoals.coverage());
            assertEquals(counts(0, 0, 3), byGoals.counts());

            var byProofs = byMode.get("PROBLEMS_BY_PROOFS");
            assertEquals("PARTIAL", byProofs.coverage(), "p2's goal has no criterion");
            assertEquals(counts(1, 1, 1), byProofs.counts());

            var goals = byMode.get("GOALS_BY_CRITERIA");
            assertEquals("GOAL", goals.kind());
            assertEquals("UNCOVERED", goals.coverage(), "two goal roots: the lower one counts");
            assertEquals(counts(1, 0, 1), goals.counts());

            // the whole-model modes: every coverable tree (problems, goals, criteria, proofs)
            var verify = byMode.get("VERIFY");
            assertNull(verify.kind());
            assertEquals("UNCOVERED", verify.coverage(), "no solution produces the proof");
            assertEquals(counts(7, 0, 0), verify.counts());
            var planning = byMode.get("PLANNING");
            assertEquals("UNCOVERED", planning.coverage(), "the proof is not produced, g2 has no criterion");
            assertEquals(counts(3, 1, 3), planning.counts());
        }
    }

    @Test
    void withoutATreeOfTheKindTheSummaryHasNoCoverage() {
        node(NodeKind.GOAL, "goal");
        var byGoals = summaries(CoverageMode.PLANNING).get("PROBLEMS_BY_GOALS");
        assertEquals("PROBLEM", byGoals.kind());
        assertNull(byGoals.coverage());
        assertEquals(counts(0, 0, 0), byGoals.counts());
    }

    @Test
    void aQuestionModeOnlyListsTheRootsItIsAbout() {
        TreeNode<?> p = objectivesModel();
        assertEquals(Map.of(p.getId(), "COVERED"),
                coverageById(CoverageReportBuilder.build(model, analyzer, CoverageMode.PROBLEMS_BY_GOALS)));
        assertEquals(Set.of("PROBLEM", "GOAL", "SUCCESS_CRITERIA"),
                CoverageReportBuilder.build(model, analyzer, CoverageMode.PROBLEMS_BY_PROOFS).roots().stream()
                        .map(RootStatus::kind).collect(Collectors.toSet()));
    }
}
