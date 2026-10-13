package org.eclipse.glsp.example.bigraph.extension.popp.validation;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class POPPModelValidatorTest {

    private final POPPModelValidator validator = new POPPModelValidator();

    private ValidationReport validate(CoverageTestModels.Synced popp) {
        return validator.validate(POPPModelValidator.snapshot(popp.bigraph.getBigraph(), popp.model.getVersion()));
    }

    private static List<ValidationIssue> issues(ValidationReport report, ValidationIssue.Code code) {
        return report.issues().stream().filter(i -> i.code() == code).toList();
    }

    @Test
    void modelBuiltThroughTheEditorHasNoErrors() {
        CoverageTestModels.Synced popp = new CoverageTestModels.Synced();
        CoverageTestModels.balancedTree(popp.model, 2, true);
        Goal goal = (Goal) popp.model.createNode(NodeKind.GOAL, "goal", 0, 0);
        Problem problem = (Problem) popp.model.createNode(NodeKind.PROBLEM, "problem", 0, 0);
        Problem sub = (Problem) popp.model.createNode(NodeKind.PROBLEM, "sub", 0, 0);
        assertEquals(DecompositionResult.ATTACHED, popp.model.decompose(problem, sub));
        assertEquals(RelationResult.CREATED, popp.model.relate(problem, goal));

        ValidationReport report = validate(popp);

        assertTrue(report.valid(), () -> report.issues().toString());
        // the balanced tree has one proof per leaf, each its own root: the only finding is the warning about that
        assertEquals(report.issues(), issues(report, ValidationIssue.Code.MULTIPLE_TREES));
        assertEquals(1, report.warnings());
        assertEquals(4, report.issues().getFirst().nodeIds().size());
    }

    @Test
    void separateRootsOfOneKindAreOneWarningListingAllOfThem() {
        CoverageTestModels.Synced popp = new CoverageTestModels.Synced();
        TreeNode<?> a = popp.model.createNode(NodeKind.PROBLEM, "a", 0, 0);
        TreeNode<?> b = popp.model.createNode(NodeKind.PROBLEM, "b", 0, 0);
        TreeNode<?> c = popp.model.createNode(NodeKind.PROBLEM, "c", 0, 0);
        popp.model.createNode(NodeKind.GOAL, "single goal", 0, 0);

        List<ValidationIssue> trees = issues(validate(popp), ValidationIssue.Code.MULTIPLE_TREES);

        assertEquals(1, trees.size());
        assertEquals(java.util.Set.of(a.getId(), b.getId(), c.getId()), java.util.Set.copyOf(trees.getFirst().nodeIds()));
        assertEquals(ValidationIssue.Severity.WARNING, trees.getFirst().severity());
    }

    @Test
    void decompositionWhoseLastChildWasRemovedIsReported() {
        CoverageTestModels.Synced popp = new CoverageTestModels.Synced();
        Goal goal = (Goal) popp.model.createNode(NodeKind.GOAL, "goal", 0, 0);
        Goal sub = (Goal) popp.model.createNode(NodeKind.GOAL, "sub", 0, 0);
        goal.addChild(sub);
        goal.removeChild(sub);

        List<ValidationIssue> empty = issues(validate(popp), ValidationIssue.Code.EMPTY_DECOMPOSITION);

        assertEquals(1, empty.size());
        assertEquals(List.of(goal.getId()), empty.getFirst().nodeIds());
    }

    @Test
    void corruptedBigraphIsReportedPerDefect() {
        CoverageTestModels.Synced popp = new CoverageTestModels.Synced();
        Problem problem = (Problem) popp.model.createNode(NodeKind.PROBLEM, "problem", 0, 0);
        Goal goal = (Goal) popp.model.createNode(NodeKind.GOAL, "goal", 0, 0);
        Problem child = (Problem) popp.model.createNode(NodeKind.PROBLEM, "child", 0, 0);
        popp.model.relate(problem, goal);
        PureBigraphMutable g = popp.bigraph.getBigraph();
        var container = popp.bigraph.findPOPPContainer(popp.bigraph.getDefaultRoot());

        // 1) the goal-side stub of the relation is lost
        var goalStub = g.getNodes().stream()
                .filter(n -> POPPBigraphSignature.INVERTS_INCOMING.matches(n) && g.getParent(n) == node(g, goal.getId()))
                .findFirst().orElseThrow();
        g.removeNode(goalStub);
        // 2) a chain node directly in the container
        g.addNode(container, popp.bigraph.getSignature().getControlByName("AndDecomp"), "stray-chain");
        // 3) a child placed directly in its parent, without the chain
        g.moveNode(node(g, child.getId()), node(g, problem.getId()));

        ValidationReport report = validate(popp);

        assertFalse(report.valid());
        List<ValidationIssue> dangling = issues(report, ValidationIssue.Code.DANGLING_STUB);
        assertEquals(1, dangling.size(), report.issues()::toString);
        assertEquals(List.of(problem.getId()), dangling.getFirst().nodeIds());
        List<ValidationIssue> stray = issues(report, ValidationIssue.Code.CHAIN_OUTSIDE_DECOMPOSITION);
        assertEquals(1, stray.size());
        assertEquals(List.of("stray-chain"), stray.getFirst().elements());
        List<ValidationIssue> direct = issues(report, ValidationIssue.Code.CHILD_WITHOUT_DECOMPOSITION);
        assertEquals(1, direct.size());
        assertEquals(List.of(problem.getId(), child.getId()), direct.getFirst().nodeIds());
        assertEquals(3, report.errors(), report.issues()::toString);
    }

    @Test
    void timingAtTheBenchmarkSizes() {
        for (int depth = 1; depth <= 4; depth++) {
            CoverageTestModels.Synced popp = new CoverageTestModels.Synced();
            CoverageTestModels.balancedTree(popp.model, depth, true);
            validate(popp); // warm-up
            long t0 = System.nanoTime();
            ValidationReport report = validate(popp);
            System.out.printf("validation depth %d (%d nodes): %.1f ms, %d patterns matched, %d skipped, %d issues%n",
                    depth, popp.model.getAllNodes().size(), (System.nanoTime() - t0) / 1e6,
                    report.patternsMatched(), report.patternsSkipped(), report.issues().size());
            assertTrue(report.valid());
        }
    }

    private static BigraphEntity.NodeEntity<DynamicControl> node(PureBigraphMutable g, String name) {
        return g.getNodes().stream().filter(n -> n.getName().equals(name)).findFirst().orElseThrow();
    }
}
