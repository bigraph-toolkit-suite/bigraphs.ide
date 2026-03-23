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

import java.util.Map;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.meta.ControlProperty;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.example.bigraph.model.BigraphNodeIdentity;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;
import org.eclipse.glsp.graph.GDimension;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.graph.GraphFactory;

/**
 * Rendering helper for Bigraph nodes.
 *
 * Extracted from GraphBigraphView to make rendering logic testable and maintainable.
 */
final class GraphNodeRenderer {
    final class NodeRenderResult {
        final GNode gNode;
        final boolean positionFound;

        NodeRenderResult(final GNode gNode, final boolean positionFound) {
            this.gNode = gNode;
            this.positionFound = positionFound;
        }
    }

    private final IBigraphModelState modelState;

    private final double startX;
    private final double spacingX;
    private final double spacingY;
    private final double nodeWidth;
    private final double nodeHeight;

    GraphNodeRenderer(final IBigraphModelState modelState,
            final double startX,
            final double spacingX,
            final double spacingY,
            final double nodeWidth,
            final double nodeHeight) {
        this.modelState = modelState;
        this.startX = startX;
        this.spacingX = spacingX;
        this.spacingY = spacingY;
        this.nodeWidth = nodeWidth;
        this.nodeHeight = nodeHeight;
    }

    NodeRenderResult renderNode(final NodeEntity<DynamicControl> node,
            final DynamicControl control,
            final String stableNodeId,
            final boolean nodeInSiteContainer) {
        GNode gNode = GraphFactory.eINSTANCE.createGNode();

        String controlName = control.getNamedType().stringValue();
        final String nodePositionKey = BigraphNodeIdentity.toNodePositionKey(node, control);
        int nodeIdHash = Math.floorMod(stableNodeId.hashCode(), Integer.MAX_VALUE);
        String nodeId = BigraphNodeIdentity.toGModelId(stableNodeId);
        gNode.setId(nodeId);
        gNode.setType(BigraphModelTypes.BIGRAPH_NODE);

        // Position — resolution priority:
        // 1. nodeRelativePositions (persisted relative coords for nodes nested in a site container)
        // 2. nodePositions (persisted absolute coords for top-level nodes)
        // 3. layout.x / layout.y attributes (set at creation time for top-level nodes)
        // 4. deterministic fallback grid position
        double x, y;
        boolean positionFound = false;
        BigraphMetaInformation meta = modelState.getMetaInformation();

        GPoint persistedPosition = null;
        if (meta != null) {
            if (nodeInSiteContainer) {
                // Property-based lookup for current meta format
                persistedPosition = getPersistedNodePosition(meta.getNodeRelativePositions(), nodePositionKey);
                // Legacy fallback for older meta files
                if (persistedPosition == null) {
                    persistedPosition = getPersistedNodePosition(meta.getNodeRelativePositions(), stableNodeId);
                }
            } else {
                persistedPosition = getPersistedNodePosition(meta.getNodePositions(), nodePositionKey);
                if (persistedPosition == null) {
                    persistedPosition = getPersistedNodePosition(meta.getNodePositions(), stableNodeId);
                }
            }
        }

        if (persistedPosition != null) {
            x = persistedPosition.getX();
            y = persistedPosition.getY();
            positionFound = true;
        } else {
            Map<String, Object> attributes = node.getAttributes();
            if (attributes.containsKey("layout.x") && attributes.containsKey("layout.y")) {
                try {
                    x = Double.parseDouble(attributes.get("layout.x").toString());
                    y = Double.parseDouble(attributes.get("layout.y").toString());
                    positionFound = true;
                } catch (NumberFormatException e) {
                    x = 0;
                    y = 0;
                }
            } else {
                x = 0;
                y = 0;
            }
        }

        if (!positionFound) {
            x = startX + (nodeIdHash % 5) * spacingX;
            y = 150.0 + ((nodeIdHash / 5) % 10) * spacingY;
        }

        GPoint position = GraphFactory.eINSTANCE.createGPoint();
        position.setX(x);
        position.setY(y);
        gNode.setPosition(position);

        // Size
        GDimension size = GraphFactory.eINSTANCE.createGDimension();
        size.setWidth(nodeWidth);
        size.setHeight(nodeHeight);
        gNode.setSize(size);

        // Styling - color is handled by CSS via bigraph-node class
        gNode.getCssClasses().add("bigraph-node");

        // Arguments
        gNode.getArgs().put("label", node.getName() + ":" + controlName);
        gNode.getArgs().put("name", node.getName());
        gNode.getArgs().put("controlType", controlName);
        gNode.getArgs().put(BigraphNodeIdentity.GMODEL_ARG_KEY, stableNodeId);

        if (meta != null && meta.getControlMeta().containsKey(controlName)) {
            ControlProperty cp = meta.getControlMeta().get(controlName);
            if (cp.getIcon() != null) gNode.getArgs().put("icon", cp.getIcon());
            if (cp.getColor() != null) gNode.getArgs().put("color", cp.getColor());
        }

        return new NodeRenderResult(gNode, positionFound);
    }

    private static GPoint getPersistedNodePosition(final Map<String, GPoint> positions, final String stableNodeId) {
        return positions.get(stableNodeId);
    }
}

