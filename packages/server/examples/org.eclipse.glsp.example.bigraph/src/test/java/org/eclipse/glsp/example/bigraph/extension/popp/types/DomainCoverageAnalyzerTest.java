package org.eclipse.glsp.example.bigraph.extension.popp.types;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
        Solution solution = new Solution("solution", 0, 0);

        and.addChild(first);
        and.addChild(second);
        and.setDecompositionType(DecompositionType.AND);

        assertEquals(RelationResult.CREATED, graph.relate(proofFirst, solution));
        assertEquals(RelationResult.CREATED, graph.relate(proofSecond, solution));

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
    void followsBackwardCoveragePropagation() {
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(graph);
        Goal goal = new Goal("goal", 0, 0);
        SuccessCriteria successCriteria = new SuccessCriteria("successCriteria", 0, 0);
        SuccessProof proof = new SuccessProof("proof", 0, 0);
        Solution solution = new Solution("solution", 0, 0);

        graph.relate(solution, proof);
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

    @Test
    void intrinsicCoverageBasedOnMode(){
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new DomainCoverageAnalyzer(graph);
        Solution s = new Solution("", 0, 0);

        SuccessCriteria sc = new SuccessCriteria("", 0, 0);
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

        SuccessProof sp = new SuccessProof("", 0, 0);
        graph.relate(s, sp);
        graph.relate(sp, sc);

        assertEquals(CoverageReason.Link.class, analyzer.explain(sc, CoverageMode.VERIFY).getClass());
        assertTrue(analyzer.isCovered(sc, CoverageMode.VERIFY));
        assertEquals(Coverage.COVERED, analyzer.coverage(sc, CoverageMode.VERIFY));
    }
}