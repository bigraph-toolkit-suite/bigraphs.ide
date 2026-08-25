package org.eclipse.glsp.example.bigraph.handler;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.bigraphWithOneNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.bigraphWithSiteAndNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.inject;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.initializedState;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.point;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.size;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.variantGateFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.TestableBigraphChangeBoundsOperationHandler;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GPoint;
import org.junit.jupiter.api.Test;

class BigraphChangeBoundsOperationHandlerTest {

    @Test
    void topLevelNodeMoveWritesToNodePositions() {
        PureBigraphMutable bigraph = bigraphWithOneNode("Agent", "n1");
        BigraphMetaInformation meta = new BigraphMetaInformation();
        BigraphModelState state = initializedState(bigraph, meta);
        String gmodelId = findFirstNodeGModelId(state);

        var handler = new TestableBigraphChangeBoundsOperationHandler();
        inject(handler, "modelState", state);
        inject(handler, "variantGate", variantGateFor(state));

        GPoint newPos = point(100, 200);
        handler.changeBoundsForTest(gmodelId, newPos, size(80, 40));

        assertFalse(meta.getNodePositions().isEmpty());
        GPoint stored = meta.getNodePositions().values().iterator().next();
        assertEquals(100, stored.getX());
        assertEquals(200, stored.getY());
    }

    @Test
    void siteNestedNodeMoveWritesToNodeRelativePositions() {
        PureBigraphMutable bigraph = bigraphWithSiteAndNode("Agent", "n1");
        BigraphMetaInformation meta = new BigraphMetaInformation();
        BigraphModelState state = initializedState(bigraph, meta);
        String gmodelId = findFirstNodeGModelId(state);

        var handler = new TestableBigraphChangeBoundsOperationHandler();
        inject(handler, "modelState", state);
        inject(handler, "variantGate", variantGateFor(state));

        GPoint newPos = point(50, 60);
        handler.changeBoundsForTest(gmodelId, newPos, size(80, 40));

        assertFalse(meta.getNodeRelativePositions().isEmpty());
        GPoint stored = meta.getNodeRelativePositions().values().iterator().next();
        assertEquals(50, stored.getX());
        assertEquals(60, stored.getY());
    }


    private String findFirstNodeGModelId(BigraphModelState state) {
        return findNodeIdsRecursive(state.getRoot()).stream()
            .findFirst()
            .orElseThrow();
    }

    private List<String> findNodeIdsRecursive(GModelElement el) {
        List<String> result = new ArrayList<>();
        if (el instanceof GNode gn
                && BigraphModelTypes.BIGRAPH_NODE.equals(gn.getType())) {
            result.add(gn.getId());
        }
        for (var child : el.getChildren()) {
            result.addAll(findNodeIdsRecursive(child));
        }
        return result;
    }
}
