package org.eclipse.glsp.example.bigraph.handler;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.ControlSpec;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.addNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.emptyBigraph;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.firstRoot;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.initializedState;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.inject;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.point;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.readField;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.signature;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.size;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.bigraphs.framework.core.impl.BigraphEntity.Edge;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.SiteEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.RecordingActionDispatcher;
import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.TestableBigraphChangeBoundsOperationHandler;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.server.actions.SelectAction;
import org.eclipse.glsp.server.operations.CreateNodeOperation;
import org.junit.jupiter.api.Test;

class ViewRegressionTest {

    @Test
    void deleteSiteRemovesNestedNodesConnectionsAndSiteBookkeeping() {
        DeletionFixture fixture = createDeletionFixture();
        DeleteBigraphElementOperationHandler handler = new DeleteBigraphElementOperationHandler();
        inject(handler, "modelState", fixture.state());
        inject(handler, "actionDispatcher", new RecordingActionDispatcher());

        handler.deleteElements(List.of(fixture.siteId()));

        assertFalse(fixture.state().getMutableBigraph().getSites().contains(fixture.site()));
        assertTrue(fixture.state().getActiveView().getBigraphEntityForGModelId(fixture.siteId()).isEmpty());
        assertTrue(fixture.state().getActiveView().getBigraphEntityForGModelId(fixture.childId()).isEmpty());
        assertTrue(fixture.state().getActiveView().getBigraphEntityForGModelId(fixture.grandchildId()).isEmpty());
        assertNoDanglingViewReferences(fixture.state(), fixture.siteId(), fixture.childId(), fixture.grandchildId());
    }

    @Test
    void deleteParentNodeRecursivelyRemovesDescendantSitesAndNodes() {
        DeletionFixture fixture = createDeletionFixture();
        DeleteBigraphElementOperationHandler handler = new DeleteBigraphElementOperationHandler();
        inject(handler, "modelState", fixture.state());
        inject(handler, "actionDispatcher", new RecordingActionDispatcher());

        handler.deleteElements(List.of(fixture.parentId()));

        assertFalse(fixture.state().getMutableBigraph().getNodes().contains(fixture.parent()));
        assertFalse(fixture.state().getMutableBigraph().getSites().contains(fixture.site()));
        assertTrue(fixture.state().getActiveView().getBigraphEntityForGModelId(fixture.parentId()).isEmpty());
        assertTrue(fixture.state().getActiveView().getBigraphEntityForGModelId(fixture.siteId()).isEmpty());
        assertNoDanglingViewReferences(fixture.state(), fixture.parentId(), fixture.siteId(), fixture.childId(), fixture.grandchildId());
    }

    @Test
    void stableLayoutKeyIsUsedOnInitialize() {
        DynamicSignature signature = signature(new ControlSpec("Room", 0));
        PureBigraphMutable bigraph = emptyBigraph(signature);
        NodeEntity<DynamicControl> node = addNode(bigraph, firstRoot(bigraph), "Room", "room");

        BigraphMetaInformation meta = new BigraphMetaInformation();
        String stableId = org.eclipse.glsp.example.bigraph.model.BigraphNodeIdentity.getOrCreateStableId(node);
        meta.getNodePositions().put(stableId, point(123.0, 456.0));
        BigraphModelState state = initializedState(bigraph, meta);

        String initialGModelId = state.getActiveView().getGModelIdForEntity(node).orElseThrow();
        assertTrue(state.getIndex().findElementByClass(initialGModelId, GNode.class).isPresent());
        assertTrue(meta.getNodePositions().containsKey(stableId));
    }

    @Test
    void initializedViewCreatesPlaceEdgesAndLinkConnections() {
        DeletionFixture fixture = createDeletionFixture();

        List<GEdge> edges = fixture.state().getRoot().getChildren().stream()
            .filter(GEdge.class::isInstance)
            .map(GEdge.class::cast)
            .toList();

        long placeEdges = edges.stream()
            .filter(e -> "bigraph:place-edge".equals(e.getType()))
            .count();
        long linkConnections = edges.stream()
            .filter(e -> "bigraph:link-connection".equals(e.getType()))
            .count();

        // root -> parent, parent -> site, child -> grandchild
        assertEquals(3, placeEdges);
        // child port and grandchild port are connected to the same hyperedge
        assertEquals(2, linkConnections);

        assertTrue(edges.stream()
            .filter(e -> "bigraph:place-edge".equals(e.getType()))
            .allMatch(e -> e.getId() != null && e.getId().startsWith("place_edge_")));
        assertTrue(edges.stream()
            .filter(e -> "bigraph:link-connection".equals(e.getType()))
            .allMatch(e -> e.getId() != null && e.getId().startsWith("link_conn_")));
    }

    @Test
    void createNodeAssignsStableIdAndAbsoluteLayoutOnEmptyCanvas() {
        DynamicSignature signature = signature(new ControlSpec("Room", 0));
        BigraphModelState state = initializedState(emptyBigraph(signature));
        RecordingActionDispatcher dispatcher = new RecordingActionDispatcher();
        CreateBigraphNodeOperationHandler handler = new CreateBigraphNodeOperationHandler();
        inject(handler, "modelState", state);
        inject(handler, "actionDispatcher", dispatcher);

        CreateNodeOperation operation = new CreateNodeOperation(
            BigraphModelTypes.BIGRAPH_NODE,
            point(400.0, 400.0),
            null,
            Map.of("controlName", "Room"));

        handler.executeCreation(operation);

        NodeEntity<DynamicControl> createdNode = state.getMutableBigraph().getNodes().stream().findFirst().orElseThrow();
        String gModelId = state.getActiveView().getGModelIdForEntity(createdNode).orElseThrow();
        assertTrue(gModelId.startsWith("node_"));
        assertTrue(state.getIndex().findElementByClass(gModelId, GNode.class).isPresent());
        assertEquals(1, dispatcher.getQueuedAfterNextUpdate().size());
        assertTrue(dispatcher.getQueuedAfterNextUpdate().get(0) instanceof SelectAction);
    }

    @Test
    void changeBoundsStoresTopLevelAndNestedSiteNodesInDifferentMetaBuckets() {
        DynamicSignature signature = signature(
            new ControlSpec("Room", 0),
            new ControlSpec("Sensor", 0));
        PureBigraphMutable bigraph = emptyBigraph(signature);
        NodeEntity<DynamicControl> topLevel = addNode(bigraph, firstRoot(bigraph), "Room", "top");
        NodeEntity<DynamicControl> siteParent = addNode(bigraph, firstRoot(bigraph), "Room", "site-parent");
        SiteEntity site = bigraph.addSite(siteParent);
        NodeEntity<DynamicControl> nested = addNode(bigraph, site, "Sensor", "nested");

        BigraphMetaInformation meta = new BigraphMetaInformation();
        BigraphModelState state = initializedState(bigraph, meta);
        TestableBigraphChangeBoundsOperationHandler handler = new TestableBigraphChangeBoundsOperationHandler();
        inject(handler, "modelState", state);

        handler.changeBoundsForTest(
            state.getActiveView().getGModelIdForEntity(topLevel).orElseThrow(),
            point(300.0, 310.0),
            size(80.0, 40.0));
        handler.changeBoundsForTest(
            state.getActiveView().getGModelIdForEntity(nested).orElseThrow(),
            point(15.0, 20.0),
            size(80.0, 40.0));

        String topStableId = state.getActiveView().getGModelIdForEntity(topLevel).orElseThrow().replace("node_", "");
        String nestedStableId = state.getActiveView().getGModelIdForEntity(nested).orElseThrow().replace("node_", "");
        assertEquals(300.0, meta.getNodePositions().get(topStableId).getX());
        assertEquals(310.0, meta.getNodePositions().get(topStableId).getY());
        assertFalse(meta.getNodeRelativePositions().containsKey(topStableId));
        assertEquals(15.0, meta.getNodeRelativePositions().get(nestedStableId).getX());
        assertEquals(20.0, meta.getNodeRelativePositions().get(nestedStableId).getY());
        assertFalse(meta.getNodePositions().containsKey(nestedStableId));
    }

    @SuppressWarnings("unchecked")
    private void assertNoDanglingViewReferences(final BigraphModelState state, final String... removedIds) {
        Map<String, List<String>> siteChildNodes = readField(state.getActiveView(), "siteChildNodes", Map.class);
        List<String> removed = List.of(removedIds);

        assertTrue(siteChildNodes.keySet().stream().noneMatch(removed::contains));
        assertTrue(siteChildNodes.values().stream().flatMap(List::stream).noneMatch(removed::contains));
        assertTrue(state.getRoot().getChildren().stream()
            .filter(GEdge.class::isInstance)
            .map(GEdge.class::cast)
            .noneMatch(edge -> removed.contains(edge.getSourceId()) || removed.contains(edge.getTargetId())));
    }

    private DeletionFixture createDeletionFixture() {
        DynamicSignature signature = signature(
            new ControlSpec("Room", 0),
            new ControlSpec("Sensor", 1));
        PureBigraphMutable bigraph = emptyBigraph(signature);
        NodeEntity<DynamicControl> parent = addNode(bigraph, firstRoot(bigraph), "Room", "parent");
        SiteEntity site = bigraph.addSite(parent);
        NodeEntity<DynamicControl> child = addNode(bigraph, site, "Sensor", "child");
        NodeEntity<DynamicControl> grandchild = addNode(bigraph, child, "Sensor", "grandchild");
        Edge edge = bigraph.addEdge("edge-delete");
        bigraph.connectNodeToLink(child, edge);
        bigraph.connectNodeToLink(grandchild, edge);

        BigraphModelState state = initializedState(bigraph);
        return new DeletionFixture(
            state,
            parent,
            site,
            child,
            grandchild,
            state.getActiveView().getGModelIdForEntity(parent).orElseThrow(),
            state.getActiveView().getGModelIdForEntity(site).orElseThrow(),
            state.getActiveView().getGModelIdForEntity(child).orElseThrow(),
            state.getActiveView().getGModelIdForEntity(grandchild).orElseThrow());
    }

    private record DeletionFixture(
        BigraphModelState state,
        NodeEntity<DynamicControl> parent,
        SiteEntity site,
        NodeEntity<DynamicControl> child,
        NodeEntity<DynamicControl> grandchild,
        String parentId,
        String siteId,
        String childId,
        String grandchildId
    ) {}
}
