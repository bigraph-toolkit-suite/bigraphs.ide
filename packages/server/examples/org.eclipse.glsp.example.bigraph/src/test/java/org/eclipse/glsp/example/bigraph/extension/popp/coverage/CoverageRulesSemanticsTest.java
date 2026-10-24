package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Tree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The coverage semantics of the rules, as evaluated by the extension's analyzer, on hand-made and generated models. */
class CoverageRulesSemanticsTest {

    /** A POPP model with its synced bigraph (see {@link CoverageTestModels.Synced}) and the analyzer under test. */
    final class SyncedPOPP {
        final POPPModel model;
        final POPPBigraph bigraph;
        final CoverageAnalyzer analyzer;

        SyncedPOPP() {
            CoverageTestModels.Synced synced = new CoverageTestModels.Synced();
            this.model = synced.model;
            this.bigraph = synced.bigraph;
            this.analyzer = createAnalyzer(CoverageInput.of(model, bigraph));
        }
    }

    /** The analyzer under test: the extension's analyzer with its default schedule. */
    protected CoverageAnalyzer createAnalyzer(CoverageInput input) {
        return new RewritingCoverageAnalyzer(input);
    }

    @Test
    void decompositionsDetermineCoverage() {
        SyncedPOPP popp = new SyncedPOPP();
        CoverageAnalyzer analyzer = popp.analyzer;

        SuccessCriteria and = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "and", 0, 0);
        SuccessCriteria first = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "first", 0, 0);
        SuccessCriteria second = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "second", 0, 0);
        SuccessProof proofFirst = (SuccessProof) popp.model.createNode(NodeKind.SUCCESS_PROOF, "first", 0, 0);
        SuccessProof proofSecond = (SuccessProof) popp.model.createNode(NodeKind.SUCCESS_PROOF, "second", 0, 0);
        Solution solution = (Solution) popp.model.createNode(NodeKind.SOLUTION, "solution", 0, 0);

        and.addChild(first);
        and.addChild(second);
        and.setDecompositionType(DecompositionType.AND);

        assertEquals(RelationResult.CREATED, popp.model.relate(proofFirst, solution));
        assertEquals(RelationResult.CREATED, popp.model.relate(proofSecond, solution));

        assertEquals(RelationResult.CREATED, popp.model.relate(first, proofFirst));
        assertTrue(analyzer.isCovered(first, CoverageMode.VERIFY));
        assertFalse(analyzer.isCovered(and, CoverageMode.VERIFY));
        assertEquals(RelationResult.CREATED, popp.model.relate(second, proofSecond));
        assertTrue(analyzer.isCovered(second, CoverageMode.VERIFY));

        assertTrue(analyzer.isCovered(and, CoverageMode.VERIFY));

        assertEquals(RelationResult.REMOVED, popp.model.unrelate(first, proofFirst));
        assertEquals(RelationResult.REMOVED, popp.model.unrelate(second, proofSecond));
        SuccessCriteria or = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "or", 0, 0);
        or.addChild(first);
        or.addChild(second);
        or.setDecompositionType(DecompositionType.OR);
        assertFalse(analyzer.isCovered(or, CoverageMode.VERIFY));
        assertEquals(RelationResult.CREATED, popp.model.relate(first, proofFirst));
        assertTrue(analyzer.isCovered(or, CoverageMode.VERIFY));
        assertEquals(RelationResult.CREATED, popp.model.relate(second, proofSecond));
        assertTrue(analyzer.isCovered(or, CoverageMode.VERIFY));

        CoverageReason.Decomposition reason = assertInstanceOf(CoverageReason.Decomposition.class,
                analyzer.explain(or, CoverageMode.VERIFY));
        assertEquals(2, reason.children().size());
        assertInstanceOf(CoverageReason.Link.class, reason.children().getFirst());
    }

    @Test
    void followsBackwardCoveragePropagation() {
        SyncedPOPP popp = new SyncedPOPP();
        CoverageAnalyzer analyzer = popp.analyzer;
        Goal goal = (Goal) popp.model.createNode(NodeKind.GOAL,"goal", 0, 0);
        SuccessCriteria successCriteria = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "successCriteria", 0, 0);
        SuccessProof proof = (SuccessProof) popp.model.createNode(NodeKind.SUCCESS_PROOF, "proof", 0, 0);
        Solution solution = (Solution) popp.model.createNode(NodeKind.SOLUTION, "solution", 0, 0);

        popp.model.relate(solution, proof);
        popp.model.relate(proof, successCriteria);
        assertTrue(analyzer.isCovered(successCriteria, CoverageMode.VERIFY));
        assertEquals(RelationResult.CREATED, popp.model.relate(goal, successCriteria));
        assertTrue(analyzer.isCovered(goal, CoverageMode.VERIFY));
        CoverageReason.Link reason = assertInstanceOf(CoverageReason.Link.class, analyzer.explain(goal, CoverageMode.VERIFY));
        assertEquals(RelationType.CAUSES, reason.type());
        assertEquals(successCriteria, reason.coveringNode());
    }

    @Test
    void testLinkBasedPropagation(){
        SyncedPOPP popp = new SyncedPOPP();
        CoverageAnalyzer analyzer = popp.analyzer;
        Problem p = (Problem) popp.model.createNode(NodeKind.PROBLEM, "", 0, 0);
        Goal g = (Goal) popp.model.createNode(NodeKind.GOAL, "", 0, 0);
        SuccessCriteria sc = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "", 0, 0);
        SuccessProof sp = (SuccessProof) popp.model.createNode(NodeKind.SUCCESS_PROOF, "", 0, 0);
        Solution s = (Solution) popp.model.createNode(NodeKind.SOLUTION, "", 0, 0);

        assertEquals(RelationResult.CREATED, popp.model.relate(p, g));
        assertEquals(RelationResult.CREATED, popp.model.relate(g, sc));
        assertEquals(RelationResult.CREATED, popp.model.relate(sc, sp));
        assertEquals(RelationResult.CREATED, popp.model.relate(sp, s));

        assertEquals(Coverage.COVERED, analyzer.coverage(p, CoverageMode.VERIFY));
        assertEquals(CoverageReason.Link.class, analyzer.explain(p, CoverageMode.VERIFY).getClass());
        assertEquals(CoverageReason.Link.class, analyzer.explain(g, CoverageMode.VERIFY).getClass());
        assertEquals(CoverageReason.Link.class, analyzer.explain(sc, CoverageMode.VERIFY).getClass());
        assertEquals(CoverageReason.Link.class, analyzer.explain(sp, CoverageMode.VERIFY).getClass());
    }

    @Test
    void intrinsicCoverageBasedOnMode() {
        SyncedPOPP popp = new SyncedPOPP();
        CoverageAnalyzer analyzer = popp.analyzer;
        Solution s = (Solution) popp.model.createNode(NodeKind.SOLUTION, "", 0, 0);

        SuccessCriteria sc = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "", 0, 0);
        assertEquals(new CoverageReason.Intrinsic(sc, Coverage.COVERED), analyzer.explain(sc, CoverageMode.PLANNING));
        assertTrue(analyzer.isCovered(sc, CoverageMode.PLANNING));
        assertEquals(Coverage.COVERED, analyzer.coverage(sc, CoverageMode.PLANNING));
        assertEquals(CoverageReason.NotCovered.class, analyzer.explain(sc, CoverageMode.VERIFY).getClass());
        assertFalse(analyzer.isCovered(sc, CoverageMode.VERIFY));
        assertEquals(Coverage.UNCOVERED, analyzer.coverage(sc, CoverageMode.VERIFY));

        assertEquals(new CoverageReason.Intrinsic(s, Coverage.COVERED), analyzer.explain(s, CoverageMode.PLANNING));
        assertTrue(analyzer.isCovered(s, CoverageMode.PLANNING));
        assertEquals(Coverage.COVERED, analyzer.coverage(s, CoverageMode.PLANNING));
        assertEquals(CoverageReason.Intrinsic.class, analyzer.explain(s, CoverageMode.VERIFY).getClass());
        assertTrue(analyzer.isCovered(s, CoverageMode.VERIFY));
        assertEquals(Coverage.COVERED, analyzer.coverage(s, CoverageMode.VERIFY));

        SuccessProof sp = (SuccessProof) popp.model.createNode(NodeKind.SUCCESS_PROOF, "", 0, 0);
        popp.model.relate(s, sp);
        popp.model.relate(sp, sc);

        assertEquals(CoverageReason.Link.class, analyzer.explain(sc, CoverageMode.VERIFY).getClass());
        assertTrue(analyzer.isCovered(sc, CoverageMode.VERIFY));
        assertEquals(Coverage.COVERED, analyzer.coverage(sc, CoverageMode.VERIFY));
    }

    @Test
    void sessionsOfDifferentModesDoNotShareState() {
        SyncedPOPP popp = new SyncedPOPP();
        SuccessCriteria criteria = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "", 0, 0);

        CoverageSession planning = popp.analyzer.session(CoverageMode.PLANNING);
        CoverageSession verify = popp.analyzer.session(CoverageMode.VERIFY);
        assertEquals(Coverage.COVERED, planning.coverage(criteria));
        assertEquals(Coverage.UNCOVERED, verify.coverage(criteria));
        assertEquals(Coverage.COVERED, planning.coverage(criteria));
    }

    // ---- gaps (same scenarios as DomainCoverageAnalyzerTest)

    @Test
    void gapsSeparateRootCausesFromAffectedNodes() {
        SyncedPOPP popp = new SyncedPOPP();
        CoverageAnalyzer analyzer = popp.analyzer;
        Problem problem = (Problem) popp.model.createNode(NodeKind.PROBLEM, "problem", 0, 0);
        Goal goal = (Goal) popp.model.createNode(NodeKind.GOAL, "goal", 0, 0);
        SuccessCriteria criteria = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "criteria", 0, 0);
        assertEquals(RelationResult.CREATED, popp.model.relate(problem, goal));
        assertEquals(RelationResult.CREATED, popp.model.relate(goal, criteria));

        CoverageGap.CoverageGaps gaps = analyzer.gaps(popp.model, CoverageMode.VERIFY);
        assertNotNull(gaps, "gaps() is not implemented for this analyzer's session");
        assertFalse(gaps.isEmpty());
        assertEquals(List.of(criteria), gaps.rootCauses().stream().map(CoverageGap::node).toList());
        assertFalse(gaps.rootCauses().getFirst().missing().isEmpty());
        assertEquals(Set.of(problem, goal), gaps.affected().stream().map(CoverageGap::node).collect(Collectors.toSet()));
        assertTrue(gaps.affected().stream().allMatch(g -> g.missing().isEmpty()));

        SuccessProof proof = (SuccessProof) popp.model.createNode(NodeKind.SUCCESS_PROOF, "proof", 0, 0);
        Solution solution = (Solution) popp.model.createNode(NodeKind.SOLUTION, "solution", 0, 0);
        assertEquals(RelationResult.CREATED, popp.model.relate(criteria, proof));
        assertEquals(RelationResult.CREATED, popp.model.relate(proof, solution));
        assertTrue(analyzer.gaps(popp.model, CoverageMode.VERIFY).isEmpty());
    }

    @Test
    void anUnprovenDecompositionChildIsTheRootCauseOfItsParent() {
        SyncedPOPP popp = new SyncedPOPP();
        CoverageAnalyzer analyzer = popp.analyzer;
        SuccessCriteria and = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "and", 0, 0);
        SuccessCriteria proven = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "proven", 0, 0);
        SuccessCriteria unproven = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "unproven", 0, 0);
        SuccessProof proof = (SuccessProof) popp.model.createNode(NodeKind.SUCCESS_PROOF, "proof", 0, 0);
        Solution solution = (Solution) popp.model.createNode(NodeKind.SOLUTION, "solution", 0, 0);
        and.addChild(proven);
        and.addChild(unproven);
        and.setDecompositionType(DecompositionType.AND);
        popp.model.relate(proof, solution);
        popp.model.relate(proven, proof);

        CoverageGap.CoverageGaps gaps = analyzer.gaps(popp.model, CoverageMode.VERIFY);
        assertNotNull(gaps, "gaps() is not implemented for this analyzer's session");
        assertEquals(List.of(unproven), gaps.rootCauses().stream().map(CoverageGap::node).toList());
        assertEquals(List.of(and), gaps.affected().stream().map(CoverageGap::node).toList());
        assertEquals(Coverage.PARTIAL, gaps.affected().getFirst().coverage());
    }

    @Test
    void planningModeHasNoGapsForPlannedCriteria() {
        SyncedPOPP popp = new SyncedPOPP();
        popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "planned", 0, 0);

        assertTrue(popp.analyzer.gaps(popp.model, CoverageMode.PLANNING).isEmpty());
        assertFalse(popp.analyzer.gaps(popp.model, CoverageMode.VERIFY).isEmpty());
    }

    // ---- agreement with the domain analyzer, which is the oracle
    //
    // These tests are about correctness of the coverage rules as the extension evaluates them
    // (RewritingCoverageAnalyzer).

    private static void assertAgree(SyncedPOPP popp, CoverageMode mode, String context) {
        CoverageSession expected = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph)).session(mode);
        long start = System.nanoTime();
        CoverageSession actual = popp.analyzer.session(mode);
        long ms = (System.nanoTime() - start) / 1_000_000;
        for (TreeNode<?> node : popp.model.getAllNodes()) {
            assertEquals(expected.coverage(node), actual.coverage(node), "Analyzers disagree on " + node + " (" + context + ")");
        }
        System.out.printf("[rewriting] %-48s %4d popp nodes %6d ms%n", context, popp.model.getAllNodes().size(), ms);
    }

    @ParameterizedTest(name = "depth {0}, all leaves proven = {1}, {2}")
    @CsvSource({
            "1, true, VERIFY", "1, false, VERIFY", "1, true, PLANNING", "1, false, PLANNING",
            "2, true, VERIFY", "2, false, VERIFY", "2, true, PLANNING", "2, false, PLANNING",
            "3, true, VERIFY", "3, false, VERIFY", "3, true, PLANNING", "3, false, PLANNING",
    })
    void agreesWithTheDomainAnalyzerOnBalancedTrees(int depth, boolean proveAll, CoverageMode mode) {
        SyncedPOPP popp = new SyncedPOPP();
        Tree tree = CoverageTestModels.balancedTree(popp.model, depth, proveAll);
        assertAgree(popp, mode, "depth " + depth + ", proveAll " + proveAll + ", " + mode);
        if (proveAll) assertEquals(Coverage.COVERED, popp.analyzer.coverage(tree.root(), mode));
    }

    /** The expensive end: ~3-4 s per session, so it only runs on demand (-DrunSlowCoverageTests=true). */
    @ParameterizedTest(name = "depth 4, all leaves proven = {0}")
    @CsvSource({"true", "false"})
    @EnabledIfSystemProperty(named = "runSlowCoverageTests", matches = "true")
    void agreesWithTheDomainAnalyzerOnTheLargestTrees(boolean proveAll) {
        agreesWithTheDomainAnalyzerOnBalancedTrees(4, proveAll, CoverageMode.VERIFY);
    }

    static java.util.stream.Stream<CoverageMode> modes() {
        return CoverageMode.presets().stream();
    }

    /** Regression for mode handling: which kinds are intrinsically covered must match in every mode. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void agreesWithTheDomainAnalyzerOnEveryKindInIsolation(CoverageMode mode) {
        for (NodeKind kind : NodeKind.values()) {
            SyncedPOPP popp = new SyncedPOPP();
            TreeNode<?> node = popp.model.createNode(kind, "lonely " + kind, 0, 0);
            CoverageAnalyzer domain = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph));

            assertEquals(domain.coverage(node, mode), popp.analyzer.coverage(node, mode), kind + " in " + mode);
            assertEquals(domain.explain(node, mode).getClass(), popp.analyzer.explain(node, mode).getClass(),
                    "reason for " + kind + " in " + mode);
        }
    }

    @Test
    void explanationsExistForEveryNodeOfALargerModel() {
        SyncedPOPP popp = new SyncedPOPP();
        CoverageTestModels.balancedTree(popp.model, 3, true);
        CoverageSession session = popp.analyzer.session(CoverageMode.VERIFY);
        for (TreeNode<?> node : popp.model.getAllNodes()) {
            assertNotNull(session.explain(node), "No explanation for " + node);
        }
    }
}