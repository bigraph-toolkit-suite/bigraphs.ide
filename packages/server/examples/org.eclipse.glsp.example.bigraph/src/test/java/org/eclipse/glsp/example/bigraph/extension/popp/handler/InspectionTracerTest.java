package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageAnalyzer;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageInput;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageMode;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.DomainCoverageAnalyzer;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.InspectionResult;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelFactory;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.Test;

class InspectionTracerTest {
    private final POPPModel model = new POPPModel();
    private final CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(CoverageInput.of(model, null));
    private final POPPGModelFactory ids = new POPPGModelFactory();
    private final InspectionTracer tracer = new InspectionTracer(ids);

    private TreeNode<?> node(NodeKind kind, String description) {
        return model.createNode(kind, description, 0, 0);
    }

    private Relation relation(TreeNode<?> a, TreeNode<?> b) {
        assertTrue(model.relate(a, b).isSuccess());
        return model.getRelations().all().stream()
                .filter(r -> Set.of(r.source(), r.target()).equals(Set.of(a, b))).findFirst().orElseThrow();
    }

    @Test
    void anIntrinsicallyCoveredNodeIsItsOwnEvidence() {
        TreeNode<?> solution = node(NodeKind.SOLUTION, "solution");

        InspectionResult result = tracer.trace(analyzer, solution, CoverageMode.VERIFY);

        assertEquals(solution.getId(), result.nodeId());
        assertEquals("solution", result.label());
        assertEquals("COVERED", result.coverage());
        assertEquals("VERIFY", result.mode());
        assertEquals(List.of(solution.getId()), result.pathNodeIds());
        assertTrue(result.pathEdgeIds().isEmpty());
        assertTrue(result.gaps().isEmpty());
    }

    @Test
    void aLinkChainIsFollowedToItsEnd() {
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");
        TreeNode<?> proof = node(NodeKind.SUCCESS_PROOF, "proof");
        TreeNode<?> solution = node(NodeKind.SOLUTION, "solution");
        Relation validates = relation(proof, criterion);
        Relation produces = relation(solution, proof);

        InspectionResult result = tracer.trace(analyzer, criterion, CoverageMode.VERIFY);

        assertEquals("COVERED", result.coverage());
        assertEquals(List.of(criterion.getId(), proof.getId(), solution.getId()), result.pathNodeIds());
        assertEquals(List.of(ids.toRelationEdgeId(validates), ids.toRelationEdgeId(produces)), result.pathEdgeIds());
        assertEquals(Map.of(criterion.getId(), "COVERED", proof.getId(), "COVERED", solution.getId(), "COVERED"),
                result.nodeCoverage());
    }

    @Test
    void aCoveredOrDecompositionShowsOnlyTheContributingChild() {
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");
        TreeNode<?> covered = node(NodeKind.GOAL, "covered");
        TreeNode<?> open = node(NodeKind.GOAL, "open");
        model.decompose(goal, covered);
        model.decompose(goal, open);
        goal.setDecompositionType(DecompositionType.OR);
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");
        relation(covered, criterion);

        InspectionResult result = tracer.trace(analyzer, goal, CoverageMode.PLANNING);

        assertEquals("COVERED", result.coverage());
        assertEquals(List.of(goal.getId(), covered.getId(), criterion.getId()), result.pathNodeIds());
        assertTrue(result.pathEdgeIds().contains(ids.toDecompositionEdgeId(goal, covered)));
        assertFalse(result.pathEdgeIds().contains(ids.toDecompositionEdgeId(goal, open)));
    }

    @Test
    void anUncoveredAndDecompositionListsEveryChildAndTheGapsBelow() {
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");
        TreeNode<?> proven = node(NodeKind.SUCCESS_CRITERIA, "proven");
        TreeNode<?> unproven = node(NodeKind.SUCCESS_CRITERIA, "unproven");
        model.decompose(criterion, proven);
        model.decompose(criterion, unproven);
        TreeNode<?> proof = node(NodeKind.SUCCESS_PROOF, "proof");
        relation(proof, proven);
        relation(node(NodeKind.SOLUTION, "solution"), proof);

        InspectionResult result = tracer.trace(analyzer, criterion, CoverageMode.VERIFY);

        assertEquals("PARTIAL", result.coverage());
        assertTrue(result.pathEdgeIds().containsAll(List.of(
                ids.toDecompositionEdgeId(criterion, proven), ids.toDecompositionEdgeId(criterion, unproven))));
        assertEquals("UNCOVERED", result.nodeCoverage().get(unproven.getId()));
        assertEquals(List.of(new InspectionResult.GapInfo(unproven.getId(),
                "SUCCESS_CRITERIA needs a success proof (validates)")), result.gaps());
    }

    @Test
    void sharedEvidenceIsListedOnceButEveryEdgeToItIsKept() {
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");
        TreeNode<?> left = node(NodeKind.GOAL, "left");
        TreeNode<?> right = node(NodeKind.GOAL, "right");
        model.decompose(goal, left);
        model.decompose(goal, right);
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "shared criterion");
        Relation fromLeft = relation(left, criterion);
        Relation fromRight = relation(right, criterion);

        InspectionResult result = tracer.trace(analyzer, goal, CoverageMode.PLANNING);

        assertEquals(1, result.pathNodeIds().stream().filter(criterion.getId()::equals).count());
        assertTrue(result.pathEdgeIds().containsAll(
                List.of(ids.toRelationEdgeId(fromLeft), ids.toRelationEdgeId(fromRight))));
        assertEquals(4, result.pathNodeIds().size());
    }

    @Test
    void aNodeWithNothingToFollowReportsItsRemedies() {
        TreeNode<?> problem = node(NodeKind.PROBLEM, "problem");

        InspectionResult result = tracer.trace(analyzer, problem, CoverageMode.PLANNING);

        assertEquals("UNCOVERED", result.coverage());
        assertEquals(List.of(problem.getId()), result.pathNodeIds());
        assertEquals(Set.of("PROBLEM needs a consequence (causes)", "PROBLEM needs a goal (inverts)"),
                Set.copyOf(result.gaps().stream().map(InspectionResult.GapInfo::message).toList()));
    }

    @Test
    void aConsequenceHasNoRemedies() {
        InspectionResult result = tracer.trace(analyzer, node(NodeKind.CONSEQUENCE, "c"), CoverageMode.PLANNING);
        assertEquals("UNCOVERED", result.coverage());
        assertTrue(result.gaps().isEmpty());
    }
}
