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
        Problem and = new Problem("and", 0, 0);
        Problem first = new Problem("first", 0, 0);
        Problem second = new Problem("second", 0, 0);
        and.addChild(first);
        and.addChild(second);
        and.setDecompositionType(DecompositionType.AND);

        first.setExplicitlyCovered(true);
        assertFalse(analyzer.isCovered(and));
        second.setExplicitlyCovered(true);
        assertTrue(analyzer.isCovered(and));

        first.setExplicitlyCovered(false);
        second.setExplicitlyCovered(false);
        Problem or = new Problem("or", 0, 0);
        or.addChild(first);
        or.addChild(second);
        or.setDecompositionType(DecompositionType.OR);
        assertFalse(analyzer.isCovered(or));
        first.setExplicitlyCovered(true);
        assertTrue(analyzer.isCovered(or));
        second.setExplicitlyCovered(true);
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
        SuccessProof proof = new SuccessProof("proof", 0, 0);
        SuccessCriteria criterion = new SuccessCriteria("criterion", 0, 0);
        Solution solution = new Solution("solution", 0, 0);
        Goal goal = new Goal("goal", 0, 0);

        assertTrue(proof.isExplicitlyCovered());
        assertEquals(RelationResult.CREATED, graph.relate(proof, criterion));
        assertTrue(analyzer.isCovered(criterion));
        CoverageReason.Link link = assertInstanceOf(CoverageReason.Link.class, analyzer.explain(criterion));
        assertEquals(RelationType.VALIDATES, link.type());
        assertEquals(proof, link.coveringNode());

        solution.setExplicitlyCovered(true);
        assertEquals(RelationResult.CREATED, graph.relate(solution, goal));
        assertFalse(analyzer.isCovered(goal));
        assertInstanceOf(CoverageReason.NotCovered.class, analyzer.explain(goal));
    }

    @Test
    void followsBackwardCoveragePropagation() {
        RelationGraph graph = new RelationGraph();
        CoverageAnalyzer analyzer = new CoverageAnalyzer(graph);
        Goal goal = new Goal("goal", 0, 0);
        SuccessCriteria successCriteria = new SuccessCriteria("successCriteria", 0, 0);

        successCriteria.setExplicitlyCovered(true);
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
        Solution solution = new Solution("solution", 0, 0);
        Goal goal = new Goal("goal", 0, 0);
        SuccessProof proof = new SuccessProof("proof", 0, 0);

        solution.setExplicitlyCovered(true);
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