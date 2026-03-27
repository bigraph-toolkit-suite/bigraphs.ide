package org.eclipse.glsp.example.bigraph.handler;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.ControlSpec;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.addNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.emptyBigraph;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.firstRoot;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.initializedState;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.inject;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.point;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.signature;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bigraphs.framework.core.impl.BigraphEntity.Edge;
import org.bigraphs.framework.core.impl.BigraphEntity.InnerName;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.OuterName;
import org.bigraphs.framework.core.impl.BigraphEntity.Port;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.actions.ConvertNameRoleAction;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.RecordingActionDispatcher;
import org.junit.jupiter.api.Test;

class ConvertNameRoleActionHandlerTest {

    @Test
    void makeInnerOnConnectedOuterRewiresPointsToReplacementEdge() {
        PureBigraphMutable bigraph = emptyBigraph(signature(new ControlSpec("Sensor", 1)));
        NodeEntity<DynamicControl> node = addNode(bigraph, firstRoot(bigraph), "Sensor", "nodeA");
        OuterName outer = bigraph.addOuterName("power");
        Port connectedPort = bigraph.connectNodeToLink(node, outer);
        InnerName existingInner = bigraph.addInnerName("innerA");
        bigraph.connectInnerNameToLink(existingInner, outer);

        BigraphMetaInformation meta = new BigraphMetaInformation();
        meta.getOuterNamePositions().put("power", point(11.0, 22.0));
        BigraphModelState state = initializedState(bigraph, meta);
        RecordingActionDispatcher dispatcher = new RecordingActionDispatcher();
        ConvertNameRoleActionHandler handler = new ConvertNameRoleActionHandler();
        inject(handler, "modelState", state);
        inject(handler, "actionDispatcher", dispatcher);

        String outerId = state.getActiveView().getGModelIdForEntity(outer).orElseThrow();
        List<?> followUps = handler.executeAction(new ConvertNameRoleAction(outerId, "inner"));

        assertTrue(bigraph.getOuterNames().stream().noneMatch(o -> "power".equals(o.getName())));
        InnerName convertedInner = bigraph.getInnerNames().stream()
            .filter(i -> "power".equals(i.getName()))
            .findFirst()
            .orElseThrow();
        assertEquals(1, followUps.size());

        var link = bigraph.getLinkOfPoint(convertedInner);
        Edge replacementEdge = assertInstanceOf(Edge.class, link);
        assertTrue(bigraph.getPointsFromLink(replacementEdge).contains(convertedInner));
        assertTrue(bigraph.getPointsFromLink(replacementEdge).contains(connectedPort));
        assertTrue(bigraph.getPointsFromLink(replacementEdge).contains(existingInner));
        assertEquals(replacementEdge, bigraph.getLinkOfPoint(existingInner));

        assertFalse(meta.getOuterNamePositions().containsKey("power"));
        assertNotNull(meta.getInnerNamePositions().get("power"));
        assertEquals(11.0, meta.getInnerNamePositions().get("power").getX());
        assertEquals(22.0, meta.getInnerNamePositions().get("power").getY());
    }

    @Test
    void makeInnerOnDisconnectedOuterCreatesLooseInnerWithoutEdge() {
        PureBigraphMutable bigraph = emptyBigraph(signature(new ControlSpec("Sensor", 1)));
        OuterName outer = bigraph.addOuterName("idle");
        int initialEdgeCount = bigraph.getEdges().size();

        BigraphModelState state = initializedState(bigraph);
        RecordingActionDispatcher dispatcher = new RecordingActionDispatcher();
        ConvertNameRoleActionHandler handler = new ConvertNameRoleActionHandler();
        inject(handler, "modelState", state);
        inject(handler, "actionDispatcher", dispatcher);

        String outerId = state.getActiveView().getGModelIdForEntity(outer).orElseThrow();
        handler.executeAction(new ConvertNameRoleAction(outerId, "inner"));

        assertEquals(initialEdgeCount, bigraph.getEdges().size());
        assertTrue(bigraph.getOuterNames().stream().noneMatch(o -> "idle".equals(o.getName())));
        InnerName convertedInner = bigraph.getInnerNames().stream()
            .filter(i -> "idle".equals(i.getName()))
            .findFirst()
            .orElseThrow();
        assertTrue(bigraph.getLinkOfPoint(convertedInner) == null);
    }
}
