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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.RootEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.SiteEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;
import org.eclipse.glsp.graph.GNode;

/**
 * Hit-testing / position lookup logic for GNodes and Bigraph entities.
 *
 * Extracted from GraphBigraphView to reduce the view's responsibility.
 */
final class GraphHitTester {
    private static final Logger LOGGER = LogManager.getLogger(GraphHitTester.class);

    private final IBigraphModelState modelState;
    private final Map<String, BigraphEntity<?>> gModelIdToEntity;

    GraphHitTester(final IBigraphModelState modelState,
            final Map<String, BigraphEntity<?>> gModelIdToEntity) {
        this.modelState = modelState;
        this.gModelIdToEntity = gModelIdToEntity;
    }

    NodeEntity<DynamicControl> findDeepestNodeOnPosition(final double x, final double y) {
        LOGGER.info("🔍 Finding deepest node at position ({}, {})", x, y);

        NodeEntity<DynamicControl> deepestNode = null;
        double smallestArea = Double.MAX_VALUE;

        for (var child : modelState.getRoot().getChildren()) {
            if (!(child instanceof GNode)) continue;
            GNode gNode = (GNode) child;

            if (BigraphModelTypes.BIGRAPH_NODE.equals(gNode.getType())) {
                // Top-level node (absolute position)
                NodeEntity<DynamicControl> candidate =
                        checkNodeHit(gNode, x, y, 0, 0, deepestNode, smallestArea);
                if (candidate != null) {
                    deepestNode = candidate;
                    smallestArea = gNode.getSize().getWidth() * gNode.getSize().getHeight();
                }
            } else if (BigraphModelTypes.SITE.equals(gNode.getType())) {
                // Site: its children have positions relative to the site
                if (gNode.getPosition() == null) continue;
                double siteAbsX = gNode.getPosition().getX();
                double siteAbsY = gNode.getPosition().getY();

                for (var nestedChild : gNode.getChildren()) {
                    if (!(nestedChild instanceof GNode)) continue;
                    GNode nestedNode = (GNode) nestedChild;
                    if (!BigraphModelTypes.BIGRAPH_NODE.equals(nestedNode.getType())) continue;

                    NodeEntity<DynamicControl> candidate =
                            checkNodeHit(nestedNode, x, y, siteAbsX, siteAbsY, deepestNode, smallestArea);
                    if (candidate != null) {
                        deepestNode = candidate;
                        smallestArea = nestedNode.getSize().getWidth() * nestedNode.getSize().getHeight();
                    }
                }
            }
        }

        if (deepestNode != null) {
            LOGGER.info("✅ Deepest node found: {}", deepestNode.getName());
        } else {
            LOGGER.info("📭 No node at position");
        }

        return deepestNode;
    }

    @SuppressWarnings("unchecked")
    NodeEntity<DynamicControl> checkNodeHit(final GNode gNode,
            final double x, final double y,
            final double parentOffsetX, final double parentOffsetY,
            final NodeEntity<DynamicControl> currentBest, final double currentBestArea) {

        if (gNode.getPosition() == null || gNode.getSize() == null) return null;

        double absX = parentOffsetX + gNode.getPosition().getX();
        double absY = parentOffsetY + gNode.getPosition().getY();
        double w = gNode.getSize().getWidth();
        double h = gNode.getSize().getHeight();

        if (x < absX || x > absX + w || y < absY || y > absY + h) return null;

        double area = w * h;
        if (area >= currentBestArea) return null;

        BigraphEntity<?> entity = gModelIdToEntity.get(gNode.getId());
        if (entity instanceof NodeEntity) {
            return (NodeEntity<DynamicControl>) entity;
        }
        return null;
    }

    RootEntity findRootOnPosition(final double x, final double y) {
        LOGGER.info("🔍 Finding root at position ({}, {})", x, y);

        for (var child : modelState.getRoot().getChildren()) {
            if (!(child instanceof GNode)) continue;

            GNode gNode = (GNode) child;

            // Only consider bigraph roots
            if (!BigraphModelTypes.BIGRAPH_ROOT.equals(gNode.getType())) continue;

            // Check bounds
            if (gNode.getPosition() == null || gNode.getSize() == null) continue;

            double rootX = gNode.getPosition().getX();
            double rootY = gNode.getPosition().getY();
            double rootWidth = gNode.getSize().getWidth();
            double rootHeight = gNode.getSize().getHeight();

            // Circular root hit test: distance to center <= radius.
            double centerX = rootX + rootWidth / 2;
            double centerY = rootY + rootHeight / 2;
            double radius = Math.min(rootWidth, rootHeight) / 2;

            double distance = Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));
            if (distance <= radius) {
                BigraphEntity<?> entity = gModelIdToEntity.get(gNode.getId());
                if (entity instanceof RootEntity) {
                    RootEntity rootEntity = (RootEntity) entity;
                    LOGGER.info("✅ Found root: {} at ({}, {})", gNode.getId(), rootX, rootY);
                    return rootEntity;
                }
            }
        }

        LOGGER.info("📭 No root at position");
        return null;
    }

    SiteEntity findSiteOnPosition(final double x, final double y) {
        LOGGER.info("🔍 Finding site at position ({}, {})", x, y);

        for (var child : modelState.getRoot().getChildren()) {
            if (!(child instanceof GNode)) continue;

            GNode gNode = (GNode) child;
            if (!BigraphModelTypes.SITE.equals(gNode.getType())) continue;
            if (gNode.getPosition() == null || gNode.getSize() == null) continue;

            double sx = gNode.getPosition().getX();
            double sy = gNode.getPosition().getY();
            double sw = gNode.getSize().getWidth();
            double sh = gNode.getSize().getHeight();
            if (x >= sx && x <= sx + sw && y >= sy && y <= sy + sh) {
                BigraphEntity<?> entity = gModelIdToEntity.get(gNode.getId());
                if (entity instanceof SiteEntity) {
                    LOGGER.info("✅ Found site: {} at ({}, {})", gNode.getId(), sx, sy);
                    return (SiteEntity) entity;
                }
            }
        }

        LOGGER.info("📭 No site at position");
        return null;
    }
}

