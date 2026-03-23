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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.SiteEntity;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GraphFactory;

/**
 * Rendering helper for visual site sizing/layout.
 *
 * Extracted from GraphBigraphView to separate concerns.
 */
final class GraphSiteLayout {
    private static final Logger LOGGER = LogManager.getLogger(GraphSiteLayout.class);

    private final Map<BigraphEntity<?>, String> entityToGModelId;
    private final Map<String, GNode> gModelIdToGNode;
    private final Map<String, List<String>> siteChildNodes;

    private final double siteWidth;
    private final double siteHeight;
    private final double nodeWidth;

    GraphSiteLayout(final Map<BigraphEntity<?>, String> entityToGModelId,
            final Map<String, GNode> gModelIdToGNode,
            final Map<String, List<String>> siteChildNodes,
            final double siteWidth,
            final double siteHeight,
            final double nodeWidth) {
        this.entityToGModelId = entityToGModelId;
        this.gModelIdToGNode = gModelIdToGNode;
        this.siteChildNodes = siteChildNodes;
        this.siteWidth = siteWidth;
        this.siteHeight = siteHeight;
        this.nodeWidth = nodeWidth;
    }

    void updateSiteFillState(final SiteEntity site) {
        String siteId = entityToGModelId.get(site);
        if (siteId == null) return;

        GNode siteGNode = gModelIdToGNode.get(siteId);
        if (siteGNode == null) return;

        // Count how many nodes are registered as children of this site.
        boolean filled = hasNodeChildInGModel(siteId);

        LOGGER.info("🔲 Updating site {} fill state: {}", siteId, filled ? "filled" : "empty");

        if (filled) {
            siteGNode.getCssClasses().remove("bigraph-site-empty");
            if (!siteGNode.getCssClasses().contains("bigraph-site-filled")) {
                siteGNode.getCssClasses().add("bigraph-site-filled");
            }
            // Grow site to wrap all child node bounds with padding.
            resizeSiteToWrapChildren(siteGNode, siteId);
        } else {
            siteGNode.getCssClasses().remove("bigraph-site-filled");
            if (!siteGNode.getCssClasses().contains("bigraph-site-empty")) {
                siteGNode.getCssClasses().add("bigraph-site-empty");
            }
            // Restore compact empty-site size.
            siteGNode.getSize().setWidth(siteWidth);
            siteGNode.getSize().setHeight(siteHeight);
        }
    }

    void refreshSiteContainingNode(final String nodeGModelId) {
        // Find which site contains this node.
        for (var entry : siteChildNodes.entrySet()) {
            if (entry.getValue().contains(nodeGModelId)) {
                String siteId = entry.getKey();
                GNode siteGNode = gModelIdToGNode.get(siteId);
                if (siteGNode != null) {
                    resizeSiteToWrapChildren(siteGNode, siteId);
                    LOGGER.info("🔄 Refreshed site {} bounds after node move", siteId);
                }
                return;
            }
        }
    }

    /**
     * Sets the position of a newly created node relative to its parent site.
     * Since the node will be nested inside siteGNode.getChildren(), Sprotty treats
     * these coordinates as relative to the site's top-left corner.
     */
    void positionNodeInsideSite(final GNode nodeGNode, final SiteEntity site) {
        final double PADDING = 14.0;
        final double LABEL_RESERVE = 24.0; // vertical space for the "$n" caption at top

        String siteId = entityToGModelId.get(site);
        if (siteId == null) return;

        // siblings = nodes already registered (current node is already in list → index = size-1)
        List<String> siblings = siteChildNodes.getOrDefault(siteId, Collections.emptyList());
        int slotIndex = Math.max(0, siblings.size() - 1);

        // Relative coordinates (0,0 = site top-left corner)
        double relX = PADDING + slotIndex * (nodeWidth + PADDING);
        double relY = LABEL_RESERVE + PADDING;

        if (nodeGNode.getPosition() == null) {
            nodeGNode.setPosition(GraphFactory.eINSTANCE.createGPoint());
        }
        nodeGNode.getPosition().setX(relX);
        nodeGNode.getPosition().setY(relY);

        LOGGER.info("📍 Node positioned at relative ({}, {}) inside site {}, slot {}", relX, relY, siteId, slotIndex);
    }

    /**
     * Fits the site GNode tightly around all its nested child nodes.
     *
     * Algorithm:
     * 1. Find the bounding box of all children in site-local coords.
     * 2. Shift the site's absolute canvas position so the top-left padding is uniform.
     * 3. Counter-shift every child's relative position by the same delta.
     * 4. Set the site's new width/height to cover the children plus padding.
     */
    void resizeSiteToWrapChildren(final GNode siteGNode, final String siteId) {
        final double PADDING = 16.0;
        final double LABEL_RESERVE = 24.0;

        List<String> childIds = siteChildNodes.getOrDefault(siteId, Collections.emptyList());
        if (childIds.isEmpty()) return;

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxRight = Double.MIN_VALUE, maxBottom = Double.MIN_VALUE;

        for (String childId : childIds) {
            GNode c = gModelIdToGNode.get(childId);
            if (c == null || c.getPosition() == null || c.getSize() == null) continue;
            double cx = c.getPosition().getX();
            double cy = c.getPosition().getY();
            if (cx < minX) minX = cx;
            if (cy < minY) minY = cy;
            if (cx + c.getSize().getWidth() > maxRight) maxRight = cx + c.getSize().getWidth();
            if (cy + c.getSize().getHeight() > maxBottom) maxBottom = cy + c.getSize().getHeight();
        }

        // How much we need to shift the site origin (in canvas coords)
        double desiredRelX = PADDING;
        double desiredRelY = LABEL_RESERVE + PADDING;
        double shiftX = minX - desiredRelX;
        double shiftY = minY - desiredRelY;

        // Move site position on canvas
        if (siteGNode.getPosition() != null && (Math.abs(shiftX) > 0.5 || Math.abs(shiftY) > 0.5)) {
            siteGNode.getPosition().setX(siteGNode.getPosition().getX() + shiftX);
            siteGNode.getPosition().setY(siteGNode.getPosition().getY() + shiftY);

            // Counter-shift all children so they stay visually in place
            for (String childId : childIds) {
                GNode c = gModelIdToGNode.get(childId);
                if (c == null || c.getPosition() == null) continue;
                c.getPosition().setX(c.getPosition().getX() - shiftX);
                c.getPosition().setY(c.getPosition().getY() - shiftY);
            }
            // Recompute bounds after shift
            maxRight -= shiftX;
            maxBottom -= shiftY;
        }

        double newWidth = Math.max(siteWidth, maxRight + PADDING);
        double newHeight = Math.max(siteHeight, maxBottom + PADDING + LABEL_RESERVE);

        siteGNode.getSize().setWidth(newWidth);
        siteGNode.getSize().setHeight(newHeight);

        LOGGER.info("📐 Site {} fitted to {}x{} (shift {}, {})", siteId, newWidth, newHeight, shiftX, shiftY);
    }

    boolean hasNodeChildInGModel(final String siteId) {
        List<String> children = siteChildNodes.get(siteId);
        return children != null && !children.isEmpty();
    }
}

