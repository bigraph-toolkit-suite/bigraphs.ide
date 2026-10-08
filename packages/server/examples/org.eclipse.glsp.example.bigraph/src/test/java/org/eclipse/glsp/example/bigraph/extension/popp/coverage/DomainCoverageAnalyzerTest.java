package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DomainCoverageAnalyzerTest {

    @Test
    void decompositionsDetermineCoverage() {
        POPPModel model = new POPPModel();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(model.getRelations());
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

    @Test
    void followsBackwardCoveragePropagation() {
        POPPModel model = new POPPModel();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(model.getRelations());
        Goal goal = (Goal) model.createNode(NodeKind.GOAL, "goal", 0, 0);
        SuccessCriteria successCriteria = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "successCriteria", 0, 0);
        SuccessProof proof = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF, "proof", 0, 0);
        Solution solution = (Solution) model.createNode(NodeKind.SOLUTION, "solution", 0, 0);

        model.relate(solution, proof);
        model.relate(proof, successCriteria);
        assertTrue(analyzer.isCovered(successCriteria, CoverageMode.VERIFY));
        assertEquals(RelationResult.CREATED, model.relate(goal, successCriteria));
        assertTrue(analyzer.isCovered(goal, CoverageMode.VERIFY));
        CoverageReason.Link reason = assertInstanceOf(CoverageReason.Link.class, analyzer.explain(goal, CoverageMode.VERIFY));
        assertEquals(RelationType.CAUSES, reason.type());
        assertEquals(successCriteria, reason.coveringNode());
    }

    @Test
    void testLinkBasedPropagation(){
        POPPModel model = new POPPModel();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(model.getRelations());
        Problem p = (Problem) model.createNode(NodeKind.PROBLEM, "", 0, 0);
        Goal g = (Goal) model.createNode(NodeKind.GOAL, "", 0, 0);
        SuccessCriteria sc = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "", 0, 0);
        SuccessProof sp = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF, "", 0, 0);
        Solution s = (Solution) model.createNode(NodeKind.SOLUTION, "", 0, 0);

        assertEquals(RelationResult.CREATED, model.relate(p, g));
        assertEquals(RelationResult.CREATED, model.relate(g, sc));
        assertEquals(RelationResult.CREATED, model.relate(sc, sp));
        assertEquals(RelationResult.CREATED, model.relate(sp, s));

        assertEquals(Coverage.COVERED, analyzer.coverage(p, CoverageMode.VERIFY));
        assertEquals(CoverageReason.Link.class, analyzer.explain(p, CoverageMode.VERIFY).getClass());
        assertEquals(CoverageReason.Link.class, analyzer.explain(g, CoverageMode.VERIFY).getClass());
        assertEquals(CoverageReason.Link.class, analyzer.explain(sc, CoverageMode.VERIFY).getClass());
        assertEquals(CoverageReason.Link.class, analyzer.explain(sp, CoverageMode.VERIFY).getClass());
    }

    @Test
    void intrinsicCoverageBasedOnMode(){
        POPPModel model = new POPPModel();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(model.getRelations());
        Solution s = (Solution) model.createNode(NodeKind.SOLUTION, "", 0, 0);

        SuccessCriteria sc = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "", 0, 0);
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

        SuccessProof sp = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF,"", 0, 0);
        model.relate(s, sp);
        model.relate(sp, sc);

        assertEquals(CoverageReason.Link.class, analyzer.explain(sc, CoverageMode.VERIFY).getClass());
        assertTrue(analyzer.isCovered(sc, CoverageMode.VERIFY));
        assertEquals(Coverage.COVERED, analyzer.coverage(sc, CoverageMode.VERIFY));
    }
}