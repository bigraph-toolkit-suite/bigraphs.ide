package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.simulation.matching.AbstractBigraphMatcher;
import org.bigraphs.framework.simulation.matching.pure.PureReactiveSystem;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageRewriteRules;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPSpecificEventListener;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;
import static org.junit.jupiter.api.Assertions.*;

class RewritingCoverageAnalyzerTest {

    public class POPPBigraphForwarder extends POPPSpecificEventListener {
        private final POPPBigraph poppBigraph;

        public POPPBigraphForwarder(POPPBigraph poppBigraph) { this.poppBigraph = poppBigraph; }

        public POPPBigraph getPoppBigraph() { return poppBigraph; }

        @Override protected void onNodeCreated(POPPEvent.NodeCreated e) { poppBigraph.addTreeNode(e.node()); }
        @Override protected void onNodeDescriptionChanged(POPPEvent.NodeDescriptionChanged e) {
            poppBigraph.updateDescription(e.node().getId(), e.node().getDescription());
        }
        @Override protected void onNodeMoved(POPPEvent.NodeMoved e) {
            poppBigraph.updatePosition(e.node().getId(), e.node().getX(), e.node().getY());
        }
        @Override protected void onNodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {
            poppBigraph.rebuildDecomposition(e.node());
        }
        @Override protected void onNodeRemoved(POPPEvent.NodeRemoved e) { poppBigraph.deleteTreeNode(e.node().getId()); }
        @Override protected void onNodeChangedParent(POPPEvent.NodeChangedParent e) {
            poppBigraph.reparentTreeNode(e.node(), e.oldParent(), e.newParent());
        }
        @Override protected void onRelationCreated(POPPEvent.RelationCreated e) { poppBigraph.createRelation(e.relation()); }
        @Override protected void onRelationRemoved(POPPEvent.RelationRemoved e) { poppBigraph.removeRelation(e.relation()); }
    }

    final class SyncedPOPP {
        final POPPModel model = new POPPModel();
        final POPPBigraph bigraph;

        SyncedPOPP() {
            DynamicSignature sig = POPPBigraphSignature.create();
            PureBigraphMutable mutable = pureBuilder(sig).createMutable();
            this.bigraph = new POPPBigraph(mutable, sig);
            model.addListener(new POPPBigraphForwarder(bigraph));
        }
    }

    @Test
    void decompositionsDetermineCoverage() {
        SyncedPOPP popp = new SyncedPOPP();
        CoverageAnalyzer analyzer = new RewritingCoverageAnalyzer(() -> popp.bigraph, popp.model.getRelations());

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
        CoverageAnalyzer analyzer = new RewritingCoverageAnalyzer(() -> popp.bigraph, popp.model.getRelations());
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
        CoverageAnalyzer analyzer = new RewritingCoverageAnalyzer(() -> popp.bigraph, popp.model.getRelations());
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
        CoverageAnalyzer analyzer = new RewritingCoverageAnalyzer(() -> popp.bigraph, popp.model.getRelations());
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
}