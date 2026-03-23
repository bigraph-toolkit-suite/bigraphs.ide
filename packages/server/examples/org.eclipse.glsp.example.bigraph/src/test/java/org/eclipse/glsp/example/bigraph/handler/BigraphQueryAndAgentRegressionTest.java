package org.eclipse.glsp.example.bigraph.handler;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.ControlSpec;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.addNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.emptyBigraph;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.firstRoot;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.initializedState;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.inject;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.signature;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.bigraphs.framework.core.impl.BigraphEntity.Edge;
import org.bigraphs.framework.core.impl.BigraphEntity.InnerName;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.OuterName;
import org.bigraphs.framework.core.impl.BigraphEntity.Port;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.actions.agent.AgentResponseAction;
import org.eclipse.glsp.example.bigraph.actions.agent.RequestBigraphChildrenAction;
import org.eclipse.glsp.example.bigraph.actions.agent.RequestBigraphLinksAction;
import org.eclipse.glsp.example.bigraph.actions.agent.RequestBigraphNeighborsAction;
import org.eclipse.glsp.example.bigraph.actions.agent.RequestBigraphNodeInfoAction;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphChildrenActionHandler;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphLinksActionHandler;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphNeighborsActionHandler;
import org.eclipse.glsp.example.bigraph.handler.agent.BigraphNodeInfoActionHandler;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.Action;
import org.junit.jupiter.api.Test;

class BigraphQueryAndAgentRegressionTest {

    @Test
    void resolveEntityRequiresCanonicalNodeGModelIds() {
        Fixture fixture = createFixture();
        String parentGModelId = fixture.parentGModelId();
        String stableId = parentGModelId.replace("node_", "");
        Port parentPort = fixture.state().getMutableBigraph().getPorts(fixture.parent()).get(0);

        assertSame(fixture.parent(), fixture.queryHelper().resolveEntity(parentGModelId).orElseThrow());
        assertTrue(fixture.queryHelper().resolveEntity(stableId).isEmpty());
        assertEquals(parentGModelId, fixture.queryHelper().findOwnerNodeGModelIdForPort(parentPort).orElseThrow());
    }

    @Test
    void agentHandlersReturnCurrentIdsInPayloads() {
        Fixture fixture = createFixture();

        BigraphNodeInfoActionHandler nodeInfoHandler = new BigraphNodeInfoActionHandler();
        inject(nodeInfoHandler, "queryHelper", fixture.queryHelper());

        RequestBigraphNodeInfoAction nodeInfoAction = new RequestBigraphNodeInfoAction();
        nodeInfoAction.setNodeId(fixture.parentGModelId());
        Map<String, Object> nodeInfoPayload = payload(nodeInfoHandler.executeAction(nodeInfoAction));
        assertEquals(fixture.parentGModelId(), nodeInfoPayload.get("gmodelId"));
        assertEquals(fixture.parentGModelId().replace("node_", ""), nodeInfoPayload.get("stableId"));

        BigraphChildrenActionHandler childrenHandler = new BigraphChildrenActionHandler();
        inject(childrenHandler, "modelState", fixture.state());
        inject(childrenHandler, "queryHelper", fixture.queryHelper());

        RequestBigraphChildrenAction childrenAction = new RequestBigraphChildrenAction();
        childrenAction.setNodeId(fixture.parentGModelId());
        Map<String, Object> childrenPayload = payload(childrenHandler.executeAction(childrenAction));
        assertEquals(fixture.parentGModelId(), childrenPayload.get("parentGmodelId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) childrenPayload.get("children");
        assertEquals(1, children.size());
        assertEquals(fixture.childGModelId(), children.get(0).get("gmodelId"));

        BigraphNeighborsActionHandler neighborsHandler = new BigraphNeighborsActionHandler();
        inject(neighborsHandler, "modelState", fixture.state());
        inject(neighborsHandler, "queryHelper", fixture.queryHelper());

        RequestBigraphNeighborsAction neighborsAction = new RequestBigraphNeighborsAction();
        neighborsAction.setNodeId(fixture.parentGModelId());
        Map<String, Object> neighborsPayload = payload(neighborsHandler.executeAction(neighborsAction));
        assertEquals(fixture.parentGModelId(), neighborsPayload.get("nodeGmodelId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> neighbors = (List<Map<String, Object>>) neighborsPayload.get("neighbors");
        assertEquals(1, neighbors.size());
        assertEquals(fixture.childGModelId(), neighbors.get(0).get("gmodelId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> viaLinks = (List<Map<String, Object>>) neighborsPayload.get("viaLinks");
        assertEquals(fixture.edgeGModelId(), viaLinks.get(0).get("gmodelId"));

        BigraphLinksActionHandler linksHandler = new BigraphLinksActionHandler();
        inject(linksHandler, "modelState", fixture.state());
        inject(linksHandler, "queryHelper", fixture.queryHelper());

        Map<String, Object> linksPayload = payload(linksHandler.executeAction(new RequestBigraphLinksAction()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> links = (List<Map<String, Object>>) linksPayload.get("links");
        Map<String, Object> edgePayload = links.stream()
            .filter(link -> fixture.edge().getName().equals(link.get("name")))
            .findFirst()
            .orElseThrow();
        assertEquals(fixture.edgeGModelId(), edgePayload.get("gmodelId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edgePoints = (List<Map<String, Object>>) edgePayload.get("connectedPoints");
        assertTrue(edgePoints.stream().anyMatch(point -> fixture.parentGModelId().equals(point.get("ownerNodeGmodelId"))));
        assertTrue(edgePoints.stream().anyMatch(point -> fixture.childGModelId().equals(point.get("ownerNodeGmodelId"))));

        Map<String, Object> outerPayload = links.stream()
            .filter(link -> fixture.outerName().getName().equals(link.get("name")))
            .findFirst()
            .orElseThrow();
        assertEquals(fixture.outerGModelId(), outerPayload.get("gmodelId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outerPoints = (List<Map<String, Object>>) outerPayload.get("connectedPoints");
        assertTrue(outerPoints.stream().anyMatch(point -> fixture.innerGModelId().equals(point.get("gmodelId"))));
        assertTrue(outerPoints.stream().noneMatch(point -> "unknown".equals(point.get("gmodelId"))));
    }

    private Fixture createFixture() {
        DynamicSignature signature = signature(
            new ControlSpec("Room", 1),
            new ControlSpec("Sensor", 1));
        PureBigraphMutable bigraph = emptyBigraph(signature);
        NodeEntity<DynamicControl> parent = addNode(bigraph, firstRoot(bigraph), "Room", "room");
        NodeEntity<DynamicControl> child = addNode(bigraph, parent, "Sensor", "sensor");
        Edge edge = bigraph.addEdge("edgeA");
        OuterName outerName = bigraph.addOuterName("outerA");
        InnerName innerName = bigraph.addInnerName("innerA");
        bigraph.connectNodeToLink(parent, edge);
        bigraph.connectNodeToLink(child, edge);
        bigraph.connectInnerNameToLink(innerName, outerName);

        BigraphModelState state = initializedState(bigraph);
        BigraphQueryHelper queryHelper = new BigraphQueryHelper();
        inject(queryHelper, "modelState", state);

        return new Fixture(
            state,
            queryHelper,
            parent,
            child,
            edge,
            outerName,
            innerName,
            state.getActiveView().getGModelIdForEntity(parent).orElseThrow(),
            state.getActiveView().getGModelIdForEntity(child).orElseThrow(),
            state.getActiveView().getGModelIdForEntity(edge).orElseThrow(),
            state.getActiveView().getGModelIdForEntity(outerName).orElseThrow(),
            state.getActiveView().getGModelIdForEntity(innerName).orElseThrow());
    }

    private Map<String, Object> payload(final List<Action> actions) {
        assertEquals(1, actions.size());
        AgentResponseAction response = (AgentResponseAction) actions.get(0);
        Map<String, Object> payload = response.getPayload();
        assertNotNull(payload);
        return payload;
    }

    private record Fixture(
        BigraphModelState state,
        BigraphQueryHelper queryHelper,
        NodeEntity<DynamicControl> parent,
        NodeEntity<DynamicControl> child,
        Edge edge,
        OuterName outerName,
        InnerName innerName,
        String parentGModelId,
        String childGModelId,
        String edgeGModelId,
        String outerGModelId,
        String innerGModelId
    ) {}
}
