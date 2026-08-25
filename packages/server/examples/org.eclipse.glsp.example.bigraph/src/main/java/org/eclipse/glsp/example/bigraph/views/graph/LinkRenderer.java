/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.glsp.example.bigraph.views.graph;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.InnerName;
import org.bigraphs.framework.core.impl.BigraphEntity.Link;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.Port;
import org.bigraphs.framework.core.impl.BigraphEntity.OuterName;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.graph.GraphFactory;

import org.bigraphs.framework.core.impl.BigraphEntity.Edge;

import org.eclipse.glsp.graph.GNode;

import java.util.Objects;

/**
 * Rendering helper for place-edges and link-connection edges.
 *
 * <p>Extracted to keep GraphBigraphView focused on containment +
 * orchestration. Reads its target container lazily through a
 * {@link Supplier} so the renderer can be constructed before the
 * owning view's GModel root has been bound — see
 * {@link org.eclipse.glsp.example.bigraph.views.BigraphView#setOwnerRoot}.</p>
 */
final class LinkRenderer {
    private static final Logger LOGGER = LogManager.getLogger(LinkRenderer.class);

    private final Supplier<GModelRoot> ownerRootSupplier;
    private final Map<BigraphEntity<?>, String> entityToGModelId;
    private final Map<Port, String> portToNodeGModelId;

    private int placeEdgeCounter = 0;
    private int linkConnectionCounter = 0;

    LinkRenderer(final Supplier<GModelRoot> ownerRootSupplier,
            final Map<BigraphEntity<?>, String> entityToGModelId,
            final Map<Port, String> portToNodeGModelId) {
        this.ownerRootSupplier = Objects.requireNonNull(ownerRootSupplier);
        this.entityToGModelId = Objects.requireNonNull(entityToGModelId);
        this.portToNodeGModelId = Objects.requireNonNull(portToNodeGModelId);
    }

    void resetCounters() {
        placeEdgeCounter = 0;
        linkConnectionCounter = 0;
    }

    GEdge createPlaceGraphEdge(final String parentId, final String childId) {
        GEdge gEdge = GraphFactory.eINSTANCE.createGEdge();
        gEdge.setId("place_edge_" + placeEdgeCounter++);
        gEdge.setType("bigraph:place-edge");
        gEdge.setSourceId(parentId);
        gEdge.setTargetId(childId);
        gEdge.getCssClasses().add("bigraph-place-edge");
        if (parentId != null && parentId.startsWith("root_")) {
            gEdge.getCssClasses().add("bigraph-root-edge");
        }
        return gEdge;
    }

    void createLinkConnections(final String linkNodeId, final Collection<BigraphEntity<?>> connectedPoints) {
        LOGGER.info("🔗 createLinkConnections: linkNodeId={}, connectedPoints count={}",
            linkNodeId, connectedPoints.size());

        for (BigraphEntity<?> point : connectedPoints) {
            String targetId = null;

            if (point instanceof Port) {
                Port port = (Port) point;
                // For ports, we use the registered port -> node GModel ID mapping.
                targetId = Optional.ofNullable(portToNodeGModelId.get(port)).orElse(null);
                LOGGER.info("  🔌 Port point: {} (hash={}), found targetId={}, portToNodeGModelId size={}",
                    port, System.identityHashCode(port), targetId, portToNodeGModelId.size());
            } else if (point instanceof InnerName) {
                targetId = entityToGModelId.get(point);
                LOGGER.info("  📛 InnerName point: {}, found targetId={}",
                    ((InnerName) point).getName(), targetId);
            } else if (point instanceof OuterName) {
                targetId = entityToGModelId.get(point);
                LOGGER.info("  📛 OuterName point: {}, found targetId={}",
                    ((OuterName) point).getName(), targetId);
            } else if (point instanceof Link) {
                // Links don't map directly to a GModel id in this view implementation.
                LOGGER.info("  ❓ Unknown link point type: {}", point.getClass().getName());
            } else if (point != null) {
                LOGGER.info("  ❓ Unknown point type: {}", point.getClass().getName());
            }

            if (targetId != null) {
                GEdge connectionEdge = GraphFactory.eINSTANCE.createGEdge();
                connectionEdge.setId("link_conn_" + linkConnectionCounter++);
                connectionEdge.setType("bigraph:link-connection");
                connectionEdge.setSourceId(linkNodeId);
                connectionEdge.setTargetId(targetId);
                connectionEdge.getCssClasses().add("bigraph-link-connection");

                ownerRootSupplier.get().getChildren().add(connectionEdge);
                LOGGER.info("  ✅ Created link connection: {} -> {}", linkNodeId, targetId);
            } else {
                LOGGER.warn("  ⚠️ Could not find targetId for point: {}", point);
            }
        }
    }
}

