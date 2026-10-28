package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Tree;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DomainCoverageAnalyzerTest {

    // ---- behaviour

    @Test
    void decompositionsDetermineCoverage() {
        POPPModel model = new POPPModel();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(CoverageInput.of(model, null));
        SuccessCriteria and = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "and", 0, 0);
        SuccessCriteria first = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "first", 0, 0);
        SuccessCriteria second = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "second", 0, 0);
        SuccessProof proofFirst = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF, "first", 0, 0);
        SuccessProof proofSecond = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF, "second", 0, 0);
        Solution solution = (Solution) model.createNode(NodeKind.SOLUTION, "solution", 0, 0);

        and.addChild(first);
        and.addChild(second);
        and.setDecompositionType(DecompositionType.AND);

        assertEquals(RelationResult.CREATED, model.relate(proofFirst, solution));
        assertEquals(RelationResult.CREATED, model.relate(proofSecond, solution));

        assertEquals(RelationResult.CREATED, model.relate(first, proofFirst));
        assertFalse(analyzer.isCovered(and, CoverageMode.VERIFY));
        assertEquals(RelationResult.CREATED, model.relate(second, proofSecond));
        assertTrue(analyzer.isCovered(first, CoverageMode.VERIFY));
        assertTrue(analyzer.isCovered(second, CoverageMode.VERIFY));

        assertTrue(analyzer.isCovered(and, CoverageMode.VERIFY));

        assertEquals(RelationResult.REMOVED, model.unrelate(first, proofFirst));
        assertEquals(RelationResult.REMOVED, model.unrelate(second, proofSecond));
        SuccessCriteria or = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "or", 0, 0);
        or.addChild(first);
        or.addChild(second);
        or.setDecompositionType(DecompositionType.OR);
        assertFalse(analyzer.isCovered(or, CoverageMode.VERIFY));
        assertEquals(RelationResult.CREATED, model.relate(first, proofFirst));
        assertTrue(analyzer.isCovered(or, CoverageMode.VERIFY));
        assertEquals(RelationResult.CREATED, model.relate(second, proofSecond));
        assertTrue(analyzer.isCovered(or, CoverageMode.VERIFY));

        CoverageReason.Decomposition reason = assertInstanceOf(CoverageReason.Decomposition.class,
                analyzer.explain(or, CoverageMode.VERIFY));
        assertEquals(2, reason.children().size());
        assertInstanceOf(CoverageReason.Link.class, reason.children().getFirst());
    }

    // ---- planning mode (regression: the mode decides which kinds are intrinsically covered)

    @Test
    void planningModeCoversPlannedKindsAndVerifyModeDoesNot() {
        POPPModel model = new POPPModel();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(CoverageInput.of(model, null));
        Solution solution = (Solution) model.createNode(NodeKind.SOLUTION, "", 0, 0);
        SuccessCriteria criteria = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "", 0, 0);

        // a planned success criteria counts as covered while planning ...
        assertEquals(new CoverageReason.Intrinsic(criteria, Coverage.COVERED), analyzer.explain(criteria, CoverageMode.PLANNING));
        assertTrue(analyzer.isCovered(criteria, CoverageMode.PLANNING));
        assertEquals(Coverage.COVERED, analyzer.coverage(criteria, CoverageMode.PLANNING));
        // ... but needs evidence when verifying
        assertInstanceOf(CoverageReason.NotCovered.class, analyzer.explain(criteria, CoverageMode.VERIFY));
        assertFalse(analyzer.isCovered(criteria, CoverageMode.VERIFY));
        assertEquals(Coverage.UNCOVERED, analyzer.coverage(criteria, CoverageMode.VERIFY));

        // a solution is evidence in both modes
        assertEquals(new CoverageReason.Intrinsic(solution, Coverage.COVERED), analyzer.explain(solution, CoverageMode.PLANNING));
        assertInstanceOf(CoverageReason.Intrinsic.class, analyzer.explain(solution, CoverageMode.VERIFY));
        assertEquals(Coverage.COVERED, analyzer.coverage(solution, CoverageMode.PLANNING));
        assertEquals(Coverage.COVERED, analyzer.coverage(solution, CoverageMode.VERIFY));

        // evidence turns the verified criteria covered, through a link
        SuccessProof proof = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF, "", 0, 0);
        model.relate(solution, proof);
        model.relate(proof, criteria);
        assertInstanceOf(CoverageReason.Link.class, analyzer.explain(criteria, CoverageMode.VERIFY));
        assertEquals(Coverage.COVERED, analyzer.coverage(criteria, CoverageMode.VERIFY));
    }

    @Test
    void sessionsOfDifferentModesDoNotShareState() {
        POPPModel model = new POPPModel();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(CoverageInput.of(model, null));
        SuccessCriteria criteria = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "", 0, 0);

        CoverageSession planning = analyzer.session(CoverageMode.PLANNING);
        CoverageSession verify = analyzer.session(CoverageMode.VERIFY);
        assertEquals(Coverage.COVERED, planning.coverage(criteria));
        assertEquals(Coverage.UNCOVERED, verify.coverage(criteria));
        assertEquals(Coverage.COVERED, planning.coverage(criteria)); // asking VERIFY in between changed nothing
    }

    // ---- gaps

    @Test
    void gapsSeparateRootCausesFromAffectedNodes() {
        POPPModel model = new POPPModel();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(CoverageInput.of(model, null));
        Problem problem = (Problem) model.createNode(NodeKind.PROBLEM, "problem", 0, 0);
        Goal goal = (Goal) model.createNode(NodeKind.GOAL, "goal", 0, 0);
        SuccessCriteria criteria = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "criteria", 0, 0);
        assertEquals(RelationResult.CREATED, model.relate(problem, goal));
        assertEquals(RelationResult.CREATED, model.relate(goal, criteria));

        // nothing proves the criteria: it is the actionable gap, the goal and the problem only wait for it
        CoverageGap.CoverageGaps gaps = analyzer.gaps(model, CoverageMode.VERIFY);
        assertFalse(gaps.isEmpty());
        assertEquals(java.util.List.of(criteria), gaps.rootCauses().stream().map(CoverageGap::node).toList());
        assertFalse(gaps.rootCauses().getFirst().missing().isEmpty());
        assertEquals(java.util.Set.of(problem, goal), gaps.affected().stream().map(CoverageGap::node).collect(java.util.stream.Collectors.toSet()));
        assertTrue(gaps.affected().stream().allMatch(g -> g.missing().isEmpty()));

        // evidence closes every gap
        SuccessProof proof = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF, "proof", 0, 0);
        Solution solution = (Solution) model.createNode(NodeKind.SOLUTION, "solution", 0, 0);
        assertEquals(RelationResult.CREATED, model.relate(criteria, proof));
        assertEquals(RelationResult.CREATED, model.relate(proof, solution));
        assertTrue(analyzer.gaps(model, CoverageMode.VERIFY).isEmpty());
    }

    @Test
    void anUnprovenDecompositionChildIsTheRootCauseOfItsParent() {
        POPPModel model = new POPPModel();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(CoverageInput.of(model, null));
        SuccessCriteria and = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "and", 0, 0);
        SuccessCriteria proven = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "proven", 0, 0);
        SuccessCriteria unproven = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "unproven", 0, 0);
        SuccessProof proof = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF, "proof", 0, 0);
        Solution solution = (Solution) model.createNode(NodeKind.SOLUTION, "solution", 0, 0);
        and.addChild(proven);
        and.addChild(unproven);
        and.setDecompositionType(DecompositionType.AND);
        model.relate(proof, solution);
        model.relate(proven, proof);

        CoverageGap.CoverageGaps gaps = analyzer.gaps(model, CoverageMode.VERIFY);
        assertEquals(java.util.List.of(unproven), gaps.rootCauses().stream().map(CoverageGap::node).toList());
        assertEquals(java.util.List.of(and), gaps.affected().stream().map(CoverageGap::node).toList());
        assertEquals(Coverage.PARTIAL, gaps.affected().getFirst().coverage());
    }

    @Test
    void planningModeHasNoGapsForPlannedCriteria() {
        POPPModel model = new POPPModel();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(CoverageInput.of(model, null));
        model.createNode(NodeKind.SUCCESS_CRITERIA, "planned", 0, 0);

        assertTrue(analyzer.gaps(model, CoverageMode.PLANNING).isEmpty());
        assertFalse(analyzer.gaps(model, CoverageMode.VERIFY).isEmpty());
    }

    // ---- responsiveness
    //
    // Requirement: every user-visible action, including inspecting coverage, answers in under 500 ms. The GLSP
    // handlers, serialization and the browser take part of that, so each action must cost only a fraction here.

    private static final double REQUIREMENT_MS = 500;
    private static final double BUDGET_MS = REQUIREMENT_MS / 5;
    private static final int SAMPLES = 5;

    /** Success criteria trees of 16, 64, 256 and 1024 leaves, i.e. up to about 3000 POPP nodes. */
    @ParameterizedTest(name = "depth {0}")
    @ValueSource(ints = {4, 6, 8, 10})
    void everyActionStaysWellBelowTheResponsivenessRequirement(int depth) {
        POPPModel model = new POPPModel();
        Tree tree = CoverageTestModels.balancedTree(model, depth, true);
        // as wired in POPPExtensionState, with the domain analyzer: this test is about the domain analyzer's timings
        CoverageInput input = CoverageInput.of(model, null);
        CoverageAnalyzer analyzer = new CachingCoverageAnalyzer(input, new DomainCoverageAnalyzer(input));
        System.out.printf("[responsiveness] depth=%d leaves=%d popp nodes=%d (budget %.0f ms per action)%n",
                depth, tree.leaves().size(), model.getAllNodes().size(), BUDGET_MS);

        stage(depth, "inspect root coverage", () ->
                assertEquals(Coverage.COVERED, analyzer.coverage(tree.root(), CoverageMode.VERIFY)));

        stage(depth, "inspect root coverage (planning)", () ->
                assertEquals(Coverage.COVERED, analyzer.coverage(tree.root(), CoverageMode.PLANNING)));

        stage(depth, "inspect every node", () -> {
            CoverageSession session = analyzer.session(CoverageMode.VERIFY);
            for (TreeNode<?> node : model.getAllNodes()) session.coverage(node);
        });

        stage(depth, "explain root", () ->
                assertInstanceOf(CoverageReason.Decomposition.class, analyzer.explain(tree.root(), CoverageMode.VERIFY)));

        stage(depth, "coverage report", () ->
                assertNotNull(CoverageReportBuilder.build(model, analyzer, CoverageMode.VERIFY)));

        SuccessCriteria leaf = tree.leaves().getFirst();
        SuccessProof proof = tree.proofs().getFirst();
        stage(depth, "edit relation, inspect root, undo", () -> {
            assertEquals(RelationResult.REMOVED, model.unrelate(leaf, proof));
            analyzer.coverage(tree.root(), CoverageMode.VERIFY);
            assertEquals(RelationResult.CREATED, model.relate(leaf, proof));
        });

        stage(depth, "create node, relate, inspect, delete", () -> {
            SuccessProof extra = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF, "extra", 0, 0);
            model.relate(extra, tree.solution());
            analyzer.coverage(extra, CoverageMode.VERIFY);
            model.deleteNode(extra.getId());
        });
    }

    /** One warm-up run (JIT), then the median of {@link #SAMPLES}. Fails when the median exceeds the budget. */
    private static void stage(int depth, String name, Runnable action) {
        action.run();
        double[] ms = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            action.run();
            ms[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(ms);
        double median = ms[SAMPLES / 2];
        System.out.printf("[responsiveness]   depth=%d %-40s %8.2f ms%n", depth, name, median);
        assertTrue(median < BUDGET_MS,
                name + " at depth " + depth + " took " + median + " ms, budget " + BUDGET_MS + " ms");
    }

    /** The analyzer as POPPExtensionState wires it: late-bound input, cached sessions. */
    @Test
    void cachedAnalyzerFollowsEditsAndAReloadedModel() {
        POPPModel[] current = {new POPPModel()};
        CoverageInput input = CoverageInput.lazy(() -> current[0], () -> null);
        CoverageAnalyzer analyzer = new CachingCoverageAnalyzer(input, new DomainCoverageAnalyzer(input));

        POPPModel model = current[0];
        TreeNode<?> solution = model.createNode(NodeKind.SOLUTION, "solution", 0, 0);
        TreeNode<?> proof = model.createNode(NodeKind.SUCCESS_PROOF, "proof", 0, 0);
        TreeNode<?> criterion = model.createNode(NodeKind.SUCCESS_CRITERIA, "criterion", 0, 0);
        model.relate(solution, proof);
        model.relate(proof, criterion);
        assertTrue(analyzer.isCovered(criterion, CoverageMode.VERIFY));

        model.unrelate(proof, criterion); // an edit bumps the version: the cached session must not be reused
        assertFalse(analyzer.isCovered(criterion, CoverageMode.VERIFY));

        // a reload replaces the model object; the analyzer is not rebuilt and must read the new model
        POPPModel reloaded = new POPPModel();
        TreeNode<?> s2 = reloaded.createNode(NodeKind.SOLUTION, "solution", 0, 0);
        TreeNode<?> p2 = reloaded.createNode(NodeKind.SUCCESS_PROOF, "proof", 0, 0);
        TreeNode<?> c2 = reloaded.createNode(NodeKind.SUCCESS_CRITERIA, "criterion", 0, 0);
        reloaded.relate(s2, p2);
        reloaded.relate(p2, c2);
        current[0] = reloaded;
        assertTrue(analyzer.isCovered(c2, CoverageMode.VERIFY));
    }
}
