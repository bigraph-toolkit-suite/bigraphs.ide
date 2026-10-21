package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests of the encoding {@link POPPBigraph} keeps in sync with a {@link POPPModel}. The model is used only to create
 * domain nodes and relations; its events are deliberately not forwarded, every test drives the bigraph by hand so it
 * sees exactly one change at a time.
 */
class POPPBigraphTest {

    /** Everything an observer is told, in order. */
    private static final class Recorder implements POPPBigraphObserver {
        final List<BigraphEntity.NodeEntity<DynamicControl>> added = new ArrayList<>();
        final List<BigraphEntity.NodeEntity<DynamicControl>> removed = new ArrayList<>();
        final List<BigraphEntity.NodeEntity<DynamicControl>> reparented = new ArrayList<>();
        final List<double[]> positions = new ArrayList<>();
        final List<List<? extends BigraphEntity<?>>> relationsAdded = new ArrayList<>();
        final List<List<? extends BigraphEntity<?>>> relationsRemoving = new ArrayList<>();
        /** For each onRelationRemoving: were all stubs still attached to a parent at that moment? */
        final List<Boolean> stubsAttachedWhenRemoving = new ArrayList<>();
        private final POPPBigraph bigraph;

        Recorder(POPPBigraph bigraph) { this.bigraph = bigraph; }

        @Override public void onNodeAdded(BigraphEntity.NodeEntity<DynamicControl> node, BigraphEntity<?> parent) { added.add(node); }
        @Override public void onNodeRemoved(BigraphEntity.NodeEntity<DynamicControl> node) { removed.add(node); }
        @Override public void onNodeReparented(BigraphEntity.NodeEntity<DynamicControl> child, BigraphEntity.NodeEntity<DynamicControl> newParent) { reparented.add(child); }
        @Override public void onPositionChanged(BigraphEntity.NodeEntity<DynamicControl> node, double x, double y) { positions.add(new double[]{x, y}); }
        @Override public void onRelationAdded(BigraphEntity.Edge edge, List<? extends BigraphEntity<?>> stubs) { relationsAdded.add(stubs); }
        @Override public void onRelationRemoving(BigraphEntity.Edge edge, List<? extends BigraphEntity<?>> stubs) {
            relationsRemoving.add(stubs);
            stubsAttachedWhenRemoving.add(stubs.stream().allMatch(s -> bigraph.getBigraph().getParent(s) != null));
        }
    }

    private DynamicSignature signature;
    private POPPModel model;
    private POPPBigraph popp;

    @BeforeEach
    void setUp() {
        signature = POPPBigraphSignature.create();
        PureBigraphMutable mutable = pureBuilder(signature).createMutable();
        popp = new POPPBigraph(mutable, signature);
        model = new POPPModel();
    }

    private int nodeCount() { return popp.getBigraph().getNodes().size(); }

    private int edgeCount() { return popp.getBigraph().getEdges().size(); }

    private SuccessCriteria criteria(String name) {
        return (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, name, 10, 20);
    }

    private BigraphEntity.NodeEntity<DynamicControl> entity(TreeNode<?> node) {
        return popp.getById(node.getId()).orElseThrow();
    }

    private Relation relation(TreeNode<?> source, TreeNode<?> target) {
        for (Relation r : model.getRelations().all()) {
            // the model stores the canonical direction, whichever order the nodes were related in
            if ((r.source() == source && r.target() == target) || (r.source() == target && r.target() == source)) return r;
        }
        throw new AssertionError("No relation " + source + " -> " + target);
    }

    /** A proof produced by a solution, and a criteria proven by the proof; all added to the bigraph. */
    private record Chain(Solution solution, SuccessProof proof, SuccessCriteria criteria) {}

    private Chain chain() {
        Solution solution = (Solution) model.createNode(NodeKind.SOLUTION, "solution", 0, 0);
        SuccessProof proof = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF, "proof", 0, 0);
        SuccessCriteria criteria = criteria("criteria");
        popp.addTreeNode(solution);
        popp.addTreeNode(proof);
        popp.addTreeNode(criteria);
        assertEquals(RelationResult.CREATED, model.relate(proof, solution));
        assertEquals(RelationResult.CREATED, model.relate(criteria, proof));
        return new Chain(solution, proof, criteria);
    }

    // ---- tree nodes -------------------------------------------------------------------------------------------

    @Test
    void addTreeNodeRegistersTheNodeWithItsKindAsControl() {
        SuccessCriteria node = criteria("a criteria");
        var entity = popp.addTreeNode(node);

        assertSame(entity, popp.getById(node.getId()).orElseThrow());
        assertEquals(POPPBigraphSignature.SUCCESS_CRITERIA, POPPBigraphSignature.forNode(entity));
        assertEquals(NodeKind.SUCCESS_CRITERIA, popp.kindOf(entity).orElseThrow());
        assertTrue(popp.isDomainNode(entity));
        assertFalse(popp.isChainNode(entity));
        assertTrue(popp.getBigraph().getChildrenOf(entity).isEmpty(), "a domain node carries no marker children");
    }

    /**
     * Description and layout are written with {@code entity.getAttributes().put(...)}. This test documents whether
     * that survives: if {@code getAttributes()} hands out a fresh map on every call, the writes are lost and the
     * bigraph cannot restore a node's description or position from itself.
     */
    @Test
    void descriptionAndLayoutAreStoredOnTheEntity() {
        var entity = popp.addTreeNode(criteria("a criteria"));
        assertEquals("a criteria", entity.getAttributes().get("description"));
        assertEquals(10.0, ((Number) entity.getAttributes().get("layout.x")).doubleValue());
        assertEquals(20.0, ((Number) entity.getAttributes().get("layout.y")).doubleValue());

        popp.updatePosition(entity.getName(), 3.5, 4.5);
        popp.updateDescription(entity.getName(), "new");
        assertEquals("new", entity.getAttributes().get("description"));
        assertEquals(3.5, ((Number) entity.getAttributes().get("layout.x")).doubleValue());
        assertEquals(4.5, ((Number) entity.getAttributes().get("layout.y")).doubleValue());
    }

    @Test
    void treeNodesLiveInOnePOPPContainerUnderTheDefaultRoot() {
        var a = popp.addTreeNode(criteria("a"));
        var b = popp.addTreeNode(criteria("b"));

        var container = popp.findPOPPContainer(popp.getDefaultRoot());
        assertNotNull(container);
        assertSame(container, popp.getBigraph().getParent(a));
        assertSame(container, popp.getBigraph().getParent(b));
    }

    @Test
    void everyKindGetsItsOwnControl() {
        for (NodeKind kind : NodeKind.values()) {
            TreeNode<?> node = model.createNode(kind, "n " + kind, 0, 0);
            var entity = popp.addTreeNode(node);
            assertEquals(POPPBigraphSignature.getByNodeKind(kind), POPPBigraphSignature.forNode(entity), kind.toString());
            assertEquals(kind, popp.kindOf(entity).orElseThrow(), kind.toString());
        }
    }

    @Test
    void deleteTreeNodeRemovesTheNode() {
        popp.addTreeNode(criteria("warm up: creates the container"));
        int before = nodeCount();

        SuccessCriteria node = criteria("a");
        popp.addTreeNode(node);
        assertTrue(nodeCount() > before);

        popp.deleteTreeNode(node.getId());

        assertTrue(popp.getById(node.getId()).isEmpty());
        assertEquals(before, nodeCount(), "the node must be gone");
    }

    @Test
    void observersAreToldAboutEveryAddedAndRemovedNode() {
        Recorder recorder = new Recorder(popp);
        popp.addObserver(recorder);
        SuccessCriteria node = criteria("a");
        popp.addTreeNode(node);
        // container (announced idempotently) and the node
        assertTrue(recorder.added.size() >= 2, "added: " + recorder.added.size());
        assertTrue(recorder.added.contains(entity(node)));

        recorder.removed.clear();
        popp.deleteTreeNode(node.getId());
        assertEquals(1, recorder.removed.size(), "only the node itself");
    }

    @Test
    void chainNodesUseTheDecompositionControlAndTheFirstOneHangsUnderTheDecomposedNode() {
        SuccessCriteria parent = parentWithChildren(DecompositionType.OR, 3);
        var first = popp.getById(parent.getId() + "_DECOMP").orElseThrow();
        var second = popp.getById(parent.getId() + "_DECOMP_1").orElseThrow();

        assertEquals(POPPBigraphSignature.SUCCESS_CRITERIA_OR, POPPBigraphSignature.forNode(first));
        assertEquals(POPPBigraphSignature.SUCCESS_CRITERIA_OR, POPPBigraphSignature.forNode(second));
        assertTrue(popp.isChainNode(first));
        assertEquals(DecompositionType.OR, popp.decompositionTypeOf(first));
        assertSame(entity(parent), popp.getBigraph().getParent(first));
        assertSame(first, popp.getBigraph().getParent(second));
    }

    @Test
    void removedObserversAreNoLongerNotified() {
        Recorder recorder = new Recorder(popp);
        popp.addObserver(recorder);
        popp.removeObserver(recorder);
        popp.addTreeNode(criteria("a"));
        assertTrue(recorder.added.isEmpty());
    }

    @Test
    void updatePositionNotifiesObservers() {
        Recorder recorder = new Recorder(popp);
        SuccessCriteria node = criteria("old");
        popp.addTreeNode(node);
        popp.addObserver(recorder);

        popp.updatePosition(node.getId(), 3.5, 4.5);

        assertEquals(1, recorder.positions.size());
        assertArrayEquals(new double[]{3.5, 4.5}, recorder.positions.getFirst());
    }

    @Test
    void mutatingAnUnknownNodeFails() {
        assertThrows(IllegalStateException.class, () -> popp.updatePosition("nope", 1, 1));
        assertThrows(IllegalStateException.class, () -> popp.updateDescription("nope", "x"));
    }

    @Test
    void deletingAnUnknownNodeIsHarmless() {
        int before = nodeCount();
        popp.deleteTreeNode("nope");
        assertEquals(before, nodeCount());
    }

    // ---- hydrate ----------------------------------------------------------------------------------------------

    @Test
    void hydrateRebuildsTheCacheFromAnExistingBigraph() {
        Chain c = chain();
        popp.createRelation(relation(c.criteria(), c.proof()));

        POPPBigraph reloaded = new POPPBigraph(popp.getBigraph(), signature);

        assertTrue(reloaded.getById(c.solution().getId()).isPresent());
        assertTrue(reloaded.getById(c.proof().getId()).isPresent());
        assertTrue(reloaded.getById(c.criteria().getId()).isPresent());
        assertSame(entity(c.criteria()), reloaded.getById(c.criteria().getId()).orElseThrow());
        assertTrue(reloaded.findRelationEdge(relation(c.criteria(), c.proof())).isPresent());
    }

    @Test
    void hydrateKeepsDecompositionChainsRemovable() {
        SuccessCriteria parent = criteria("parent");
        SuccessCriteria a = criteria("a"), b = criteria("b"), d = criteria("d");
        for (var n : List.of(a, b, d)) popp.addTreeNode(n);
        popp.addTreeNode(parent);
        parent.addChild(a); parent.addChild(b); parent.addChild(d);
        parent.setDecompositionType(DecompositionType.AND);
        popp.rebuildDecomposition(parent);

        POPPBigraph reloaded = new POPPBigraph(popp.getBigraph(), signature);
        int before = nodeCount();
        reloaded.deleteTreeNode(parent.getId());

        assertEquals(before - 3, nodeCount(), "the parent and its two chain nodes must go");
        assertTrue(reloaded.getById(parent.getId() + "_DECOMP").isEmpty());
    }

    // ---- decomposition ----------------------------------------------------------------------------------------

    private Set<String> decodedChildIds(SuccessCriteria parent) {
        return popp.decodeDecomposition(entity(parent)).children().stream()
                .map(BigraphEntity.NodeEntity::getName).collect(Collectors.toSet());
    }

    private SuccessCriteria parentWithChildren(DecompositionType type, int children) {
        List<SuccessCriteria> kids = new ArrayList<>();
        for (int i = 0; i < children; i++) {
            SuccessCriteria kid = criteria("kid " + i);
            popp.addTreeNode(kid);
            kids.add(kid);
        }
        SuccessCriteria parent = criteria("parent");
        popp.addTreeNode(parent);
        kids.forEach(parent::addChild);
        parent.setDecompositionType(type);
        popp.rebuildDecomposition(parent);
        return parent;
    }

    @Test
    void aNodeWithoutChildrenAndWithoutTypeHasNoChain() {
        SuccessCriteria parent = parentWithChildren(DecompositionType.NONE, 0);
        var decoded = popp.decodeDecomposition(entity(parent));

        assertEquals(DecompositionType.NONE, decoded.type());
        assertTrue(decoded.children().isEmpty());
        assertTrue(popp.getById(parent.getId() + "_DECOMP").isEmpty());
    }

    @Test
    void decompositionRoundTripsTypeAndChildrenForAllChildCounts() {
        for (DecompositionType type : new DecompositionType[]{DecompositionType.AND, DecompositionType.OR}) {
            for (int count = 1; count <= 4; count++) { // a leaf has no decomposition (see TreeNode)
                setUp();
                SuccessCriteria parent = parentWithChildren(type, count);
                var decoded = popp.decodeDecomposition(entity(parent));

                assertEquals(type, decoded.type(), type + " with " + count + " children");
                assertEquals(parent.getChildren().stream().map(TreeNode::getId).collect(Collectors.toSet()),
                        decodedChildIds(parent), type + " with " + count + " children");
            }
        }
    }

    @Test
    void aDecompositionOfNChildrenUsesAChainOfNMinusOneNodes() {
        SuccessCriteria parent = parentWithChildren(DecompositionType.AND, 4);
        assertTrue(popp.getById(parent.getId() + "_DECOMP").isPresent());
        assertTrue(popp.getById(parent.getId() + "_DECOMP_1").isPresent());
        assertTrue(popp.getById(parent.getId() + "_DECOMP_2").isPresent());
        assertTrue(popp.getById(parent.getId() + "_DECOMP_3").isEmpty());
    }

    @Test
    void changingTheTypeReplacesTheChain() {
        SuccessCriteria parent = parentWithChildren(DecompositionType.AND, 3);
        parent.setDecompositionType(DecompositionType.OR);
        popp.rebuildDecomposition(parent);

        assertEquals(DecompositionType.OR, popp.decodeDecomposition(entity(parent)).type());
        assertEquals(3, decodedChildIds(parent).size());
    }

    @Test
    void rebuildingTheSameDecompositionRepeatedlyDoesNotGrowTheBigraph() {
        SuccessCriteria parent = parentWithChildren(DecompositionType.OR, 3);
        int before = nodeCount();
        for (int i = 0; i < 50; i++) popp.rebuildDecomposition(parent);
        assertEquals(before, nodeCount());
    }

    @Test
    void rebuildCanExcludeAndIncludeAChild() {
        SuccessCriteria parent = parentWithChildren(DecompositionType.AND, 3);
        String excluded = parent.getChildren().getFirst().getId();

        popp.rebuildDecomposition(parent, excluded);

        assertEquals(2, decodedChildIds(parent).size());
        assertFalse(decodedChildIds(parent).contains(excluded));
    }

    @Test
    void deletingTheParentOfADecompositionRemovesItsChain() {
        popp.addTreeNode(criteria("creates the container"));
        int before = nodeCount();
        SuccessCriteria parent = parentWithChildren(DecompositionType.AND, 3);
        List<String> kids = parent.getChildren().stream().map(TreeNode::getId).toList();

        popp.deleteTreeNode(parent.getId());

        assertTrue(popp.getById(parent.getId() + "_DECOMP").isEmpty());
        assertTrue(popp.getById(parent.getId() + "_DECOMP_1").isEmpty());
        // the three children survive, everything of the parent and its chain is gone
        for (String kid : kids) assertTrue(popp.getById(kid).isPresent());
        assertEquals(before + 3, nodeCount(), "only the three surviving children remain");
    }

    @Test
    void reparentingMovesTheNodeUnderItsNewParent() {
        SuccessCriteria child = criteria("child");
        SuccessCriteria parent = criteria("parent");
        popp.addTreeNode(child);
        popp.addTreeNode(parent);
        parent.addChild(child);

        popp.reparentTreeNode(child, null, parent);

        assertEquals(Set.of(child.getId()), decodedChildIds(parent));
    }

    // ---- relations --------------------------------------------------------------------------------------------

    @Test
    void createRelationEncodesAnEdgeAndTwoStubs() {
        Chain c = chain();
        int nodes = nodeCount(), edges = edgeCount();

        Relation relation = relation(c.criteria(), c.proof());
        var edge = popp.createRelation(relation);

        assertEquals(nodes + 2, nodeCount());
        assertEquals(edges + 1, edgeCount());
        assertEquals(edge, popp.findRelationEdge(relation).orElseThrow());
        var endpoints = popp.decodeRelation(edge).orElseThrow();
        assertSame(entity(relation.source()), endpoints.source());
        assertSame(entity(relation.target()), endpoints.target());
    }

    @Test
    void stubsHangUnderTheirEndpointAndAreForgottenWithTheRelation() {
        Chain c = chain();
        Recorder recorder = new Recorder(popp);
        popp.addObserver(recorder);
        Relation relation = relation(c.criteria(), c.proof());
        popp.createRelation(relation);

        assertEquals(1, recorder.relationsAdded.size());
        var stubs = recorder.relationsAdded.getFirst();
        assertEquals(2, stubs.size());
        Set<Object> parents = stubs.stream().map(s -> popp.getBigraph().getParent(s)).collect(Collectors.toSet());
        assertEquals(Set.of(entity(relation.source()), entity(relation.target())), parents);
        List<String> stubIds = stubs.stream().map(s -> ((BigraphEntity.NodeEntity<?>) s).getName()).toList();
        stubIds.forEach(id -> assertTrue(popp.getById(id).isPresent(), "stub is cached while the relation exists"));

        popp.removeRelation(relation);

        stubIds.forEach(id -> assertTrue(popp.getById(id).isEmpty(), "a removed stub must not stay in the cache"));
    }

    @Test
    void removeRelationRemovesTheEdgeAndBothStubs() {
        Chain c = chain();
        int nodes = nodeCount(), edges = edgeCount();
        Relation relation = relation(c.criteria(), c.proof());
        popp.createRelation(relation);

        popp.removeRelation(relation);

        assertEquals(nodes, nodeCount(), "both stub nodes must be gone");
        assertEquals(edges, edgeCount());
        assertTrue(popp.findRelationEdge(relation).isEmpty());
    }

    @Test
    void removeRelationTellsObserversWithNodesThatAreStillAttached() {
        Chain c = chain();
        Relation relation = relation(c.criteria(), c.proof());
        popp.createRelation(relation);
        Recorder recorder = new Recorder(popp);
        popp.addObserver(recorder);

        popp.removeRelation(relation);

        assertEquals(1, recorder.relationsRemoving.size());
        var stubs = recorder.relationsRemoving.getFirst();
        assertEquals(2, stubs.size());
        assertTrue(stubs.stream().allMatch(s -> s instanceof BigraphEntity.NodeEntity<?>),
                "observers get the stub nodes, not the link's ports");
        assertTrue(recorder.stubsAttachedWhenRemoving.getFirst(), "announced before the removal");
    }

    @Test
    void repeatedlyRelatingAndUnrelatingDoesNotGrowTheBigraph() {
        Chain c = chain();
        Relation relation = relation(c.criteria(), c.proof());
        int nodes = nodeCount(), edges = edgeCount();
        for (int i = 0; i < 200; i++) {
            popp.createRelation(relation);
            popp.removeRelation(relation);
        }
        assertEquals(nodes, nodeCount());
        assertEquals(edges, edgeCount());
    }

    @Test
    void severalRelationsAreIndependent() {
        Chain c = chain();
        Relation proves = relation(c.criteria(), c.proof());
        Relation produces = relation(c.proof(), c.solution());
        popp.createRelation(proves);
        popp.createRelation(produces);

        popp.removeRelation(proves);

        assertTrue(popp.findRelationEdge(proves).isEmpty());
        assertTrue(popp.findRelationEdge(produces).isPresent());
    }

    @Test
    void removingAnUnknownRelationFails() {
        Chain c = chain();
        Relation relation = relation(c.criteria(), c.proof());
        assertThrows(NoSuchElementException.class, () -> popp.removeRelation(relation));
    }

    @Test
    void decodeRelationOfAForeignEdgeIsEmpty() {
        var edge = popp.getBigraph().addEdge("not a relation");
        assertTrue(popp.decodeRelation(edge).isEmpty());
    }

    @Test
    void aBigraphWithoutSignatureIsReadOnly() {
        PureBigraphMutable mutable = pureBuilder(signature).createMutable();
        POPPBigraph readOnly = new POPPBigraph(mutable, null);
        assertThrows(IllegalStateException.class, () -> readOnly.addTreeNode(criteria("a")));
    }

    // ---- queries and attributes -------------------------------------------------------------------------------

    @Test
    void nodeQueriesTellDomainNodesChainNodesAndStubsApart() {
        SuccessCriteria parent = parentWithChildren(DecompositionType.OR, 2);
        Chain chain = chain();
        popp.createRelation(relation(chain.proof(), chain.criteria()));
        var domain = entity(parent);
        var chainNode = popp.getById(parent.getId() + "_DECOMP").orElseThrow();
        var stub = popp.getBigraph().getNodes().stream().filter(POPPBigraphSignature.VALIDATES_INCOMING::matches)
                .findFirst().orElseThrow();

        assertTrue(popp.isDomainNode(domain));
        assertFalse(popp.isChainNode(domain));
        assertEquals(NodeKind.SUCCESS_CRITERIA, popp.kindOf(domain).orElseThrow());
        assertEquals(DecompositionType.NONE, popp.decompositionTypeOf(domain));

        assertTrue(popp.isChainNode(chainNode));
        assertTrue(popp.kindOf(chainNode).isEmpty());
        assertEquals(DecompositionType.OR, popp.decompositionTypeOf(chainNode));

        assertFalse(popp.isDomainNode(stub));
        assertFalse(popp.isChainNode(stub));
        assertTrue(popp.kindOf(stub).isEmpty());
        assertEquals(chain.criteria().getId(), ((BigraphEntity.NodeEntity<?>) popp.getBigraph().getParent(stub)).getName());
    }

    @Test
    void decodingANodeWithoutChainReportsItsDirectDomainChildren() {
        SuccessCriteria parent = criteria("parent");
        SuccessCriteria child = criteria("child");
        popp.addTreeNode(parent);
        popp.addTreeNode(child);
        popp.getBigraph().moveNode(entity(child), entity(parent)); // a defect: no chain in between

        var decoded = popp.decodeDecomposition(entity(parent));

        assertEquals(DecompositionType.NONE, decoded.type());
        assertEquals(List.of(child.getId()), decoded.children().stream().map(BigraphEntity.NodeEntity::getName).toList());
    }

    @Test
    void updateDescriptionStoresTheTextWithoutNotifyingObservers() {
        SuccessCriteria node = criteria("old");
        popp.addTreeNode(node);
        Recorder recorder = new Recorder(popp);
        popp.addObserver(recorder);

        popp.updateDescription(node.getId(), "new");
        popp.updateDescription(node.getId(), null);

        assertEquals("", entity(node).getAttributes().get("description"), "null is stored as empty text");
        assertTrue(recorder.added.isEmpty() && recorder.removed.isEmpty() && recorder.reparented.isEmpty()
                && recorder.positions.isEmpty() && recorder.relationsAdded.isEmpty());
    }

    @Test
    void theContainerIsCreatedOnceAndFoundAgain() {
        popp.addTreeNode(criteria("a"));
        popp.addTreeNode(criteria("b"));

        var container = popp.findPOPPContainer(popp.getDefaultRoot());
        assertNotNull(container);
        assertEquals(1, popp.getBigraph().getNodes().stream().filter(POPPBigraphSignature.POPP::matches).count());
        assertEquals(1, popp.getBigraph().getRoots().size());
    }
}
