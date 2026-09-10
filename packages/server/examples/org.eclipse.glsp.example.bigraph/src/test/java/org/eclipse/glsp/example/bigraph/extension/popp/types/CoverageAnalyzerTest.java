package org.eclipse.glsp.example.bigraph.extension.popp.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoverageAnalyzerTest {

    @Test
    void explicitCoverageAndDecompositionsDetermineCoverage() {
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new CoverageAnalyzer(graph);
        Problem and = new Problem("and");
        Problem first = new Problem("first");
        Problem second = new Problem("second");
        and.addChild(first);
        and.addChild(second);
        and.setDecompositionType(DecompositionType.AND);

        first.setCovered(true);
        assertFalse(analyzer.isCovered(and));
        second.setCovered(true);
        assertTrue(analyzer.isCovered(and));

        first.setCovered(false);
        second.setCovered(false);
        Problem or = new Problem("or");
        or.addChild(first);
        or.addChild(second);
        or.setDecompositionType(DecompositionType.OR);
        assertFalse(analyzer.isCovered(or));
        first.setCovered(true);
        assertTrue(analyzer.isCovered(or));
        second.setCovered(true);
        assertTrue(analyzer.isCovered(or));

        CoverageReason.Decomposition reason = assertInstanceOf(CoverageReason.Decomposition.class,
                analyzer.explain(or));
        assertEquals(2, reason.children().size());
        assertInstanceOf(CoverageReason.Explicit.class, reason.children().get(0));
    }

    @Test
    void followsOnlyCoveragePropagatingRelations() {
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new CoverageAnalyzer(graph);
        SuccessProof proof = new SuccessProof("proof");
        SuccessCriteria criterion = new SuccessCriteria("criterion");
        Solution solution = new Solution("solution");
        Goal goal = new Goal("goal");

        assertTrue(proof.isExplicitlyCovered());
        assertEquals(RelationResult.CREATED, graph.relate(proof, criterion));
        assertTrue(analyzer.isCovered(criterion));
        CoverageReason.Link link = assertInstanceOf(CoverageReason.Link.class, analyzer.explain(criterion));
        assertEquals(RelationType.VALIDATES, link.type());
        assertEquals(proof, link.coveringNode());

        solution.setCovered(true);
        assertEquals(RelationResult.CREATED, graph.relate(solution, goal));
        assertFalse(analyzer.isCovered(goal));
        assertInstanceOf(CoverageReason.NotCovered.class, analyzer.explain(goal));
    }

    @Test
    void followsBackwardCoveragePropagation() {
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new CoverageAnalyzer(graph);
        Goal goal = new Goal("goal");
        SuccessCriteria successCriteria = new SuccessCriteria("successCriteria");

        successCriteria.setCovered(true);
        assertEquals(RelationResult.CREATED, graph.relate(goal, successCriteria));
        assertTrue(analyzer.isCovered(goal));
        CoverageReason.Link reason = assertInstanceOf(CoverageReason.Link.class, analyzer.explain(goal));
        assertEquals(RelationType.CAUSES, reason.type());
        assertEquals(successCriteria, reason.coveringNode());
    }

    @Test
    void ignoresProvenanceOnlyRelationsForCoverage() {
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new CoverageAnalyzer(graph);
        Solution solution = new Solution("solution");
        Goal goal = new Goal("goal");
        SuccessProof proof = new SuccessProof("proof");

        solution.setCovered(true);
        assertEquals(RelationResult.CREATED, graph.relate(solution, goal));
        assertFalse(analyzer.isCovered(goal));
        assertInstanceOf(CoverageReason.NotCovered.class, analyzer.explain(goal));

        assertEquals(RelationResult.CREATED, graph.relate(solution, proof));
        assertFalse(analyzer.isCovered(goal));
    }

    @Test
    void detectsCyclicCoverageDependencies() {
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new CoverageAnalyzer(graph);
        Problem a = new Problem("a");
        Problem b = new Problem("b");

        a.setDecompositionType(DecompositionType.AND);
        b.setDecompositionType(DecompositionType.AND);
        a.children.add(b);
        b.parent = a;
        b.children.add(a);
        a.parent = b;

        assertThrows(IllegalStateException.class, () -> analyzer.isCovered(a));
    }
}