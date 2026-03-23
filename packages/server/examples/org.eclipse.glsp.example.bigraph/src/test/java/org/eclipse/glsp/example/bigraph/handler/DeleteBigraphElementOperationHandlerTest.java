package org.eclipse.glsp.example.bigraph.handler;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.bigraphWithOneNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.inject;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.initializedState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeleteBigraphElementOperationHandlerTest {

    private DeleteBigraphElementOperationHandler handler;
    private BigraphModelState modelState;
    private PureBigraphMutable bigraph;

    @BeforeEach
    void setUp() {
        bigraph = bigraphWithOneNode("Agent", "toDelete");
        modelState = initializedState(bigraph);
        handler = new DeleteBigraphElementOperationHandler();
        inject(handler, "modelState", modelState);
        inject(handler, "actionDispatcher", new org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.RecordingActionDispatcher());
    }

    @Test
    void recursiveNodeDeletionLeavesNoDanglingNodes() {
        String gmodelId = findFirstNodeGModelId();

        handler.deleteElements(List.of(gmodelId));

        assertTrue(bigraph.getNodes().isEmpty());
        assertEquals(1, bigraph.getRoots().size());
    }

    private String findFirstNodeGModelId() {
        return findNodeIdsRecursive(modelState.getRoot()).stream().findFirst().orElseThrow();
    }

    private java.util.List<String> findNodeIdsRecursive(org.eclipse.glsp.graph.GModelElement el) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (el instanceof org.eclipse.glsp.graph.GNode gn
                && org.eclipse.glsp.example.bigraph.model.BigraphModelTypes.BIGRAPH_NODE.equals(gn.getType())) {
            result.add(gn.getId());
        }
        for (var child : el.getChildren()) {
            result.addAll(findNodeIdsRecursive(child));
        }
        return result;
    }
}
