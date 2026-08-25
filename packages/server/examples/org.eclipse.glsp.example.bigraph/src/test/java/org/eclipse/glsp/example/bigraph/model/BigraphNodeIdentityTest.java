package org.eclipse.glsp.example.bigraph.model;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.ControlSpec;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.addNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.emptyBigraph;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.firstRoot;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.signature;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.junit.jupiter.api.Test;

class BigraphNodeIdentityTest {

    @Test
    void toGModelIdProducesNodePrefixFormat() {
        String stableId = "abc-123-uuid";
        assertEquals("node_abc-123-uuid", BigraphNodeIdentity.toGModelId(stableId));
    }

    @Test
    void getStableIdReturnsEmptyForNullInput() {
        assertTrue(BigraphNodeIdentity.getStableId(null).isEmpty());
    }

    @Test
    void getOrCreateStableIdSurvivesNodeRename() {
        DynamicSignature sig = signature(new ControlSpec("Room", 0));
        PureBigraphMutable bigraph = emptyBigraph(sig);
        NodeEntity<DynamicControl> node = addNode(bigraph, firstRoot(bigraph), "Room", "room");

        String stableId1 = BigraphNodeIdentity.getOrCreateStableId(node);
        node.setName("room-renamed");

        String stableId2 = BigraphNodeIdentity.getOrCreateStableId(node);
        assertEquals(stableId1, stableId2);
        assertTrue(BigraphNodeIdentity.getStableId(node).isPresent());
    }
}
