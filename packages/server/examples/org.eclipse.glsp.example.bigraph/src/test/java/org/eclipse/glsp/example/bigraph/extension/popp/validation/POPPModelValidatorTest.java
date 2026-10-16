package org.eclipse.glsp.example.bigraph.extension.popp.validation;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport;
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
    void removingTheLastChildLeavesNoEmptyDecomposition() {
        CoverageTestModels.Synced popp = new CoverageTestModels.Synced();
        Goal goal = (Goal) popp.model.createNode(NodeKind.GOAL, "goal", 0, 0);
        Goal sub = (Goal) popp.model.createNode(NodeKind.GOAL, "sub", 0, 0);
        goal.addChild(sub);
        goal.removeChild(sub); // the node drops its decomposition with its last child

        assertTrue(issues(validate(popp), ValidationIssue.Code.EMPTY_DECOMPOSITION).isEmpty());
    }

    @Test
    void emptyDecompositionInTheBigraphIsReported() {
        // the editor cannot produce this any more; files written before (or edited by hand) can contain it
        CoverageTestModels.Synced popp = new CoverageTestModels.Synced();
        Goal goal = (Goal) popp.model.createNode(NodeKind.GOAL, "goal", 0, 0);
        PureBigraphMutable g = popp.bigraph.getBigraph();
        g.addNode(node(g, goal.getId()), popp.bigraph.getSignature().getControlByName("AndDecomp"), goal.getId() + "_DECOMP");

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

    @Test
    void anEmptyFileIsValidAndNoPatternIsMatched() {
        ValidationReport report = validate(new CoverageTestModels.Synced());

        assertTrue(report.valid());
        assertTrue(report.issues().isEmpty());
        assertEquals(0, report.patternsMatched(), "the census rules every pattern out");
        assertEquals(POPPValidationPatterns.all().size(), report.patternsSkipped());
    }

    @Test
    void aStubInTheContainerIsMisplaced() {
        CoverageTestModels.Synced popp = new CoverageTestModels.Synced();
        Goal goal = (Goal) popp.model.createNode(NodeKind.GOAL, "goal", 0, 0);
        SuccessCriteria criterion = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "sc", 0, 0);
        popp.model.relate(goal, criterion);
        PureBigraphMutable g = popp.bigraph.getBigraph();
        var stub = g.getNodes().stream().filter(POPPBigraphSignature.CAUSES_OUTGOING::matches).findFirst().orElseThrow();
        g.moveNode(stub, popp.bigraph.findPOPPContainer(popp.bigraph.getDefaultRoot()));

        List<ValidationIssue> misplaced = issues(validate(popp), ValidationIssue.Code.MISPLACED_STUB);

        assertEquals(1, misplaced.size());
        assertEquals(List.of(stub.getName()), misplaced.getFirst().elements());
        assertTrue(misplaced.getFirst().nodeIds().isEmpty(), "the container belongs to no POPP node");
        assertEquals(ValidationIssue.Severity.ERROR, misplaced.getFirst().severity());
    }

    @Test
    void aStubInAChainNodeIsMisplacedAndAttributedToTheDecomposedNode() {
        CoverageTestModels.Synced popp = new CoverageTestModels.Synced();
        Goal parent = (Goal) popp.model.createNode(NodeKind.GOAL, "parent", 0, 0);
        parent.addChild((Goal) popp.model.createNode(NodeKind.GOAL, "child", 0, 0));
        SuccessCriteria criterion = (SuccessCriteria) popp.model.createNode(NodeKind.SUCCESS_CRITERIA, "sc", 0, 0);
        popp.model.relate(parent, criterion);
        PureBigraphMutable g = popp.bigraph.getBigraph();
        var stub = g.getNodes().stream().filter(POPPBigraphSignature.CAUSES_OUTGOING::matches).findFirst().orElseThrow();
        g.moveNode(stub, node(g, parent.getId() + "_DECOMP"));

        List<ValidationIssue> misplaced = issues(validate(popp), ValidationIssue.Code.MISPLACED_STUB);

        assertEquals(1, misplaced.size());
        assertEquals(List.of(parent.getId()), misplaced.getFirst().nodeIds());
        assertTrue(misplaced.getFirst().message().contains("Goal \"parent\""), misplaced.getFirst().message());
    }

    @Test
    void aNodeOfAnotherKindDirectlyInsideIsReported() {
        CoverageTestModels.Synced popp = new CoverageTestModels.Synced();
        Problem problem = (Problem) popp.model.createNode(NodeKind.PROBLEM, "problem", 0, 0);
        Goal goal = (Goal) popp.model.createNode(NodeKind.GOAL, "goal", 0, 0);
        PureBigraphMutable g = popp.bigraph.getBigraph();
        g.moveNode(node(g, goal.getId()), node(g, problem.getId()));

        List<ValidationIssue> direct = issues(validate(popp), ValidationIssue.Code.CHILD_WITHOUT_DECOMPOSITION);

        assertEquals(1, direct.size());
        assertEquals(List.of(problem.getId(), goal.getId()), direct.getFirst().nodeIds());
        assertTrue(direct.getFirst().message().endsWith("(a different kind)"), direct.getFirst().message());
    }

    @Test
    void aControlOutsideTheSignatureIsReportedAndItsSubtreeIsNotCopied() {
        List<BigraphTestSupport.ControlSpec> controls = new java.util.ArrayList<>();
        for (POPPBigraphSignature c : POPPBigraphSignature.values()) {
            controls.add(new BigraphTestSupport.ControlSpec(c.controlName(), c.arity()));
        }
        controls.add(new BigraphTestSupport.ControlSpec("Foreign", 0));
        var signature = BigraphTestSupport.signature(
                controls.toArray(BigraphTestSupport.ControlSpec[]::new));
        PureBigraphMutable g = BigraphTestSupport.emptyBigraph(signature);
        var container = BigraphTestSupport.addNode(
                g, g.getRoots().getFirst(), "POPP", "POPP");
        var foreign = BigraphTestSupport.addNode(g, container, "Foreign", "foreign");
        BigraphTestSupport.addNode(g, foreign, "Goal", "hidden goal");

        POPPModelValidator.Snapshot snapshot = POPPModelValidator.snapshot(g, 7);
        ValidationReport report = validator.validate(snapshot);

        assertEquals(List.of("foreign"), snapshot.unknown());
        assertFalse(snapshot.census().containsKey("Goal"), "nothing below an unknown node is copied");
        List<ValidationIssue> unknown = issues(report, ValidationIssue.Code.UNKNOWN_CONTROL);
        assertEquals(1, unknown.size());
        assertEquals(List.of("foreign"), unknown.getFirst().elements());
        assertFalse(report.valid());
        assertEquals(7, report.modelVersion());
    }

    @Test
    void aNodeWithoutDescriptionIsLabelledByItsShortId() {
        CoverageTestModels.Synced popp = new CoverageTestModels.Synced();
        Goal first = (Goal) popp.model.createNode(NodeKind.GOAL, "", 0, 0);
        popp.model.createNode(NodeKind.GOAL, "second", 0, 0);

        ValidationIssue trees = issues(validate(popp), ValidationIssue.Code.MULTIPLE_TREES).getFirst();

        assertTrue(trees.message().contains("Goal " + first.getId().substring(0, 8)), trees.message());
        assertTrue(trees.message().contains("Goal \"second\""), trees.message());
        assertTrue(trees.message().startsWith("2 separate Goal trees"), trees.message());
    }

    @Test
    void reportsListErrorsBeforeWarningsAndCountBoth() {
        ValidationIssue warning = new ValidationIssue(ValidationIssue.Code.MULTIPLE_TREES,
                ValidationIssue.Severity.WARNING, "w", List.of(), List.of(), "p");
        ValidationIssue error = new ValidationIssue(ValidationIssue.Code.DANGLING_STUB,
                ValidationIssue.Severity.ERROR, "e", List.of(), List.of(), "p");

        ValidationReport report = ValidationReport.of(3, List.of(warning, error), 12, 4, 5);

        assertEquals(List.of(error, warning), report.issues());
        assertEquals(1, report.errors());
        assertEquals(1, report.warnings());
        assertFalse(report.valid());
        assertTrue(ValidationReport.of(3, List.of(warning), 0, 0, 0).valid(), "warnings alone keep it valid");
    }

    @Test
    void everyCodeHasTheDocumentedSeverity() {
        for (ValidationIssue.Code code : ValidationIssue.Code.values()) {
            boolean warning = code == ValidationIssue.Code.EMPTY_DECOMPOSITION || code == ValidationIssue.Code.MULTIPLE_TREES;
            assertEquals(warning ? ValidationIssue.Severity.WARNING : ValidationIssue.Severity.ERROR, code.severity(),
                    code.name());
        }
    }

    private static BigraphEntity.NodeEntity<DynamicControl> node(PureBigraphMutable g, String name) {
        return g.getNodes().stream().filter(n -> n.getName().equals(name)).findFirst().orElseThrow();
    }
}
