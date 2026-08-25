package org.eclipse.glsp.example.bigraph.handler;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.bigraphWithOneNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.inject;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.initializedState;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.variantGateFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.RecordingActionDispatcher;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.graph.GNode;
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
        inject(handler, "actionDispatcher", new RecordingActionDispatcher());
        inject(handler, "variantGate", variantGateFor(modelState));
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
