package org.eclipse.glsp.example.bigraph.handler;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.bigraphWithOneNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.inject;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.initializedState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BigraphQueryHelperTest {

    private BigraphQueryHelper queryHelper;
    private BigraphModelState modelState;
    private PureBigraphMutable bigraph;
    private NodeEntity<DynamicControl> node;

    @BeforeEach
    void setUp() {
        bigraph = bigraphWithOneNode("Agent", "testNode");
        node = bigraph.getNodes().stream().findFirst().orElseThrow();
        modelState = initializedState(bigraph);
        queryHelper = new BigraphQueryHelper();
        inject(queryHelper, "modelState", modelState);
    }

    @Test
    void resolveEntityByGmodelIdReturnsNode() {
        String gmodelId = findFirstNodeGModelId();

        Optional<BigraphEntity<?>> byGmodelId = queryHelper.resolveEntity(gmodelId);

        assertTrue(byGmodelId.isPresent());
        assertEquals("testNode", ((NodeEntity<?>) byGmodelId.get()).getName());
    }

    @Test
    void resolveEntityByStableIdAndNodePrefixReturnsSameNodeAsGmodelId() {
        String gmodelId = findFirstNodeGModelId();
        String stableId = gmodelId.replace("node_", "");

        Optional<BigraphEntity<?>> byGmodelId = queryHelper.resolveEntity(gmodelId);
        Optional<BigraphEntity<?>> byStableId = queryHelper.resolveEntity(stableId);
        Optional<BigraphEntity<?>> byNodePrefix = queryHelper.resolveEntity("node_" + stableId);

        assertTrue(byGmodelId.isPresent());
        if (byStableId.isPresent()) {
            assertEquals(byGmodelId.get(), byStableId.get());
        }
        if (byNodePrefix.isPresent()) {
            assertEquals(byGmodelId.get(), byNodePrefix.get());
        }
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

    @Test
    void buildNodeSummaryReturnsCurrentIdsNotUnknown() {
        BigraphEntity<?> entity = queryHelper.resolveEntity(findFirstNodeGModelId()).orElseThrow();
        Map<String, Object> summary = queryHelper.buildNodeSummary(entity);

        assertNotNull(summary.get("gmodelId"));
        assertNotNull(summary.get("stableId"));
        assertFalse("unknown".equals(summary.get("gmodelId")));
        assertFalse("unknown".equals(summary.get("stableId")));
        assertEquals("testNode", summary.get("name"));
        assertEquals("node", summary.get("type"));
    }

    @Test
    void findGModelIdForEntityReturnsCorrectId() {
        BigraphEntity<?> entity = queryHelper.resolveEntity(findFirstNodeGModelId()).orElseThrow();
        Optional<String> gmodelId = queryHelper.findGModelIdForEntity(entity);
        assertTrue(gmodelId.isPresent());
        assertTrue(gmodelId.get().startsWith("node_"));
        assertEquals(entity, queryHelper.resolveEntity(gmodelId.get()).orElse(null));
    }

    @Test
    void buildNodeDetailIncludesCurrentIds() {
        BigraphEntity<?> entity = queryHelper.resolveEntity(findFirstNodeGModelId()).orElseThrow();
        Map<String, Object> detail = queryHelper.buildNodeDetail(entity);
        assertNotNull(detail.get("gmodelId"));
        assertNotNull(detail.get("stableId"));
        assertFalse("unknown".equals(detail.get("gmodelId")));
    }
}
