package org.eclipse.glsp.example.bigraph.extension.popp.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageAnalyzer;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageReason;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.DomainCoverageAnalyzer;
import org.junit.jupiter.api.Test;

class DomainCoverageAnalyzerTest {

    @Test
    void decompositionsDetermineCoverage() {
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(graph);
        SuccessCriteria and = new SuccessCriteria("and", 0, 0);
        SuccessCriteria first = new SuccessCriteria("first", 0, 0);
        SuccessCriteria second = new SuccessCriteria("second", 0, 0);
        SuccessProof proofFirst = new SuccessProof("first", 0, 0);
        SuccessProof proofSecond = new SuccessProof("second", 0, 0);

        and.addChild(first);
        and.addChild(second);
        and.setDecompositionType(DecompositionType.AND);

        assertEquals(RelationResult.CREATED, graph.relate(first, proofFirst));
        assertFalse(analyzer.isCovered(and));
        assertEquals(RelationResult.CREATED, graph.relate(second, proofSecond));
        assertTrue(analyzer.isCovered(first));
        assertTrue(analyzer.isCovered(second));

        assertTrue(analyzer.isCovered(and));

        assertEquals(RelationResult.REMOVED, graph.unrelate(first, proofFirst));
        assertEquals(RelationResult.REMOVED, graph.unrelate(second, proofSecond));
        SuccessCriteria or = new SuccessCriteria("or", 0, 0);
        or.addChild(first);
        or.addChild(second);
        or.setDecompositionType(DecompositionType.OR);
        assertFalse(analyzer.isCovered(or));
        assertEquals(RelationResult.CREATED, graph.relate(first, proofFirst));
        assertTrue(analyzer.isCovered(or));
        assertEquals(RelationResult.CREATED, graph.relate(second, proofSecond));
        assertTrue(analyzer.isCovered(or));

        CoverageReason.Decomposition reason = assertInstanceOf(CoverageReason.Decomposition.class,
                analyzer.explain(or));
        assertEquals(2, reason.children().size());
        assertInstanceOf(CoverageReason.Link.class, reason.children().getFirst());
    }


    @Test
    void followsOnlyCoveragePropagatingRelations() {
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(graph);
        SuccessProof proof = new SuccessProof("proof", 0, 0);
        SuccessCriteria criterion = new SuccessCriteria("criterion", 0, 0);

        assertTrue(proof.isIntrinsicallyCovered());
        assertEquals(RelationResult.CREATED, graph.relate(proof, criterion));
        assertTrue(analyzer.isCovered(criterion));
        CoverageReason.Link link = assertInstanceOf(CoverageReason.Link.class, analyzer.explain(criterion));
        assertEquals(RelationType.VALIDATES, link.type());
        assertEquals(proof, link.coveringNode());
    }

    @Test
    void followsBackwardCoveragePropagation() {
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(graph);
        Goal goal = new Goal("goal", 0, 0);
        SuccessCriteria successCriteria = new SuccessCriteria("successCriteria", 0, 0);
        SuccessProof proof = new SuccessProof("proof", 0, 0);

        graph.relate(proof, successCriteria);
        assertTrue(analyzer.isCovered(successCriteria));
        assertEquals(RelationResult.CREATED, graph.relate(goal, successCriteria));
        assertTrue(analyzer.isCovered(goal));
        CoverageReason.Link reason = assertInstanceOf(CoverageReason.Link.class, analyzer.explain(goal));
        assertEquals(RelationType.CAUSES, reason.type());
        assertEquals(successCriteria, reason.coveringNode());
    }

    @Test
    void detectsCyclicCoverageDependencies() {
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(graph);
        Problem a = new Problem("a", 0, 0);
        Problem b = new Problem("b", 0, 0);

        a.setDecompositionType(DecompositionType.AND);
        b.setDecompositionType(DecompositionType.AND);
        a.children.add(b);
        b.parent = a;
        b.children.add(a);
        a.parent = b;

        assertThrows(IllegalStateException.class, () -> analyzer.isCovered(a));
    }
}