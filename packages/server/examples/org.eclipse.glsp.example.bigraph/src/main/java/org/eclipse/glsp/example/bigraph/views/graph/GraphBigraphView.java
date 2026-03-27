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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.*;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.model.BigraphNodeIdentity;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.meta.ControlProperty;
import org.eclipse.glsp.example.bigraph.views.BigraphView;
import org.eclipse.glsp.graph.GDimension;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.graph.GraphFactory;


public class GraphBigraphView extends BigraphView {

    private static final Logger LOGGER = LogManager.getLogger(GraphBigraphView.class);

    // Visual constants
    private static final double NODE_WIDTH = 80.0;
    private static final double NODE_HEIGHT = 40.0;
    private static final double ROOT_SIZE = 30.0;
    private static final double SPACING_X = 120.0;
    private static final double SPACING_Y = 80.0;
    private static final double START_X = 50.0;
    private static final double START_Y = 50.0;

    // Site layout (slot appearance, below parent)
    private static final double SITE_WIDTH = 70.0;
    private static final double SITE_HEIGHT = 45.0;
    private static final double SITE_HORIZONTAL_GAP = 12.0;
    private static final double SITE_VERTICAL_GAP = 20.0;

    // Index counters
    private int rootCounter = 0;
    private int nodeCounter = 0;
    private int edgeCounter = 0;
    private int outerNameCounter = 0;
    private int innerNameCounter = 0;
    private final LinkRenderer linkRenderer;

    // Registry: BigraphEntity -> GModel ID
    private final Map<BigraphEntity<?>, String> entityToGModelId = new HashMap<>();
    // Registry: GModel ID -> BigraphEntity (reverse mapping)
    private final Map<String, BigraphEntity<?>> gModelIdToEntity = new HashMap<>();
    // Registry: Port -> owning Node's GModel ID
    private final Map<Port, String> portToNodeGModelId = new HashMap<>();
    // Registry: GModel ID -> GNode (for quick lookups)
    private final Map<String, GNode> gModelIdToGNode = new HashMap<>();
    // Registry: site GModel ID -> list of node GModel IDs placed inside the site
    // (used instead of place-edges since site children are visually contained, not connected)
    private final Map<String, List<String>> siteChildNodes = new HashMap<>();

    private final NodeRenderer nodeRenderer = new NodeRenderer();

    /**
     * Creates a GNode for a Bigraph node.
     */
    private final class NodeRenderer {
        private final class NodeRenderResult {
            private final GNode gNode;
            private final boolean positionFound;

            private NodeRenderResult(final GNode gNode, final boolean positionFound) {
                this.gNode = gNode;
                this.positionFound = positionFound;
            }
        }

        private NodeRenderResult renderNode(final NodeEntity<DynamicControl> node,
                final DynamicControl control,
                final String stableNodeId,
                final boolean nodeInSiteContainer) {
            final GraphNodeRenderer.NodeRenderResult rendered = delegate.renderNode(
                node, control, stableNodeId, nodeInSiteContainer);
            return new NodeRenderResult(rendered.gNode, rendered.positionFound);
        }

        private final GraphNodeRenderer delegate =
            new GraphNodeRenderer(modelState, START_X, SPACING_X, SPACING_Y, NODE_WIDTH, NODE_HEIGHT);
    }

    private final SiteLayout siteLayout = new SiteLayout();

    /**
     * Centralizes lookup policy for registry-backed ids.
     * Rendering and containment remain in the main view and the collaborators above.
     */
    private final RegistryLookups registryLookups = new RegistryLookups();

    private final class RegistryLookups {
        private final GraphRegistryLookups delegate =
            new GraphRegistryLookups(gModelIdToEntity, entityToGModelId, portToNodeGModelId);

        private Optional<BigraphEntity<?>> getBigraphEntityForGModelId(final String gModelId) {
            return delegate.getBigraphEntityForGModelId(gModelId);
        }

        private Optional<String> getGModelIdForEntity(final BigraphEntity<?> entity) {
            return delegate.getGModelIdForEntity(entity);
        }

        private Optional<String> getNodeGModelIdForPort(final Port port) {
            return delegate.getNodeGModelIdForPort(port);
        }
    }

    private final HitTester hitTester = new HitTester();

    /**
     * Hit-testing / position lookup logic, extracted to keep the main view manageable.
     */
    private final class HitTester {
        private final GraphHitTester delegate = new GraphHitTester(modelState, gModelIdToEntity);

        private NodeEntity<DynamicControl> findDeepestNodeOnPosition(final double x, final double y) {
            return delegate.findDeepestNodeOnPosition(x, y);
        }

        @SuppressWarnings("unchecked")
        private NodeEntity<DynamicControl> checkNodeHit(final GNode gNode,
                final double x, final double y,
                final double parentOffsetX, final double parentOffsetY,
                final NodeEntity<DynamicControl> currentBest, final double currentBestArea) {
            return delegate.checkNodeHit(gNode, x, y, parentOffsetX, parentOffsetY, currentBest,
                currentBestArea);
        }

        private RootEntity findRootOnPosition(final double x, final double y) {
            return delegate.findRootOnPosition(x, y);
        }

        private SiteEntity findSiteOnPosition(final double x, final double y) {
            return delegate.findSiteOnPosition(x, y);
        }
    }

    /**
     * Responsible for site sizing/layout logic:
     * - deciding whether a site is visually filled
     * - computing and resizing site bounds from nested node positions
     * - positioning newly created nodes inside a site container
     *
     * The caller decides when to invoke these operations based on view/model events.
     */
    private final class SiteLayout {
        private final GraphSiteLayout delegate = new GraphSiteLayout(
            entityToGModelId, gModelIdToGNode, siteChildNodes, SITE_WIDTH, SITE_HEIGHT, NODE_WIDTH);

        private void updateSiteFillState(final SiteEntity site) {
            delegate.updateSiteFillState(site);
        }

        private void refreshSiteContainingNode(final String nodeGModelId) {
            delegate.refreshSiteContainingNode(nodeGModelId);
        }

        /**
         * Sets the position of a newly created node relative to its parent site.
         * Since the node will be nested inside siteGNode.getChildren(), Sprotty treats
         * these coordinates as relative to the site's top-left corner.
         */
        private void positionNodeInsideSite(final GNode nodeGNode, final SiteEntity site) {
            delegate.positionNodeInsideSite(nodeGNode, site);
        }

        /**
         * Fits the site GNode tightly around all its nested child nodes.
         *
         * Algorithm:
         * 1. Find the bounding box of all children in site-local coords (minX, minY, maxRight, maxBottom).
         * 2. Shift the site's absolute canvas position by (minX - PADDING, minY - LABEL_RESERVE - PADDING)
         *    so the top-left padding is always uniform.
         * 3. Counter-shift every child's relative position by the same delta so they stay visually fixed.
         * 4. Set the site's new width/height to cover (maxRight - minX) + 2*PADDING.
         */
        private void resizeSiteToWrapChildren(final GNode siteGNode, final String siteId) {
            delegate.resizeSiteToWrapChildren(siteGNode, siteId);
        }

        private boolean hasNodeChildInGModel(final String siteId) {
            return delegate.hasNodeChildInGModel(siteId);
        }
    }

    public GraphBigraphView(IBigraphModelState modelState) {
        super(modelState);
        this.linkRenderer = new LinkRenderer(modelState, entityToGModelId, portToNodeGModelId);
    }

    /**
     * Resets all counters and clears registry. Call before re-initializing.
     */
    public void reset() {
        rootCounter = 0;
        nodeCounter = 0;
        edgeCounter = 0;
        outerNameCounter = 0;
        innerNameCounter = 0;
        linkRenderer.resetCounters();
        entityToGModelId.clear();
        gModelIdToEntity.clear();
        portToNodeGModelId.clear();
        gModelIdToGNode.clear();
        siteChildNodes.clear();
    }

    // === PLACE GRAPH CALLBACKS ===

    @Override
    public void onAddRoot(RootEntity root) {
        GNode gNode = GraphFactory.eINSTANCE.createGNode();

        String nodeId = "root_" + rootCounter;
        gNode.setId(nodeId);
        gNode.setType(BigraphModelTypes.BIGRAPH_ROOT);

        // Position roots at the top (restore from meta if present)
        GPoint position = GraphFactory.eINSTANCE.createGPoint();
        final BigraphMetaInformation meta = modelState.getMetaInformation();
        final String rootKey = String.valueOf(root.getIndex());
        if (meta != null && meta.getRootPositions().containsKey(rootKey)) {
            final GPoint metaPos = meta.getRootPositions().get(rootKey);
            position.setX(metaPos.getX());
            position.setY(metaPos.getY());
        } else {
            position.setX(START_X + rootCounter * SPACING_X);
            position.setY(START_Y);
        }
        gNode.setPosition(position);

        // Size
        GDimension size = GraphFactory.eINSTANCE.createGDimension();
        size.setWidth(ROOT_SIZE);
        size.setHeight(ROOT_SIZE);
        gNode.setSize(size);

        // Styling
        gNode.getCssClasses().add("bigraph-root");

        // Arguments
        gNode.getArgs().put("label", "R" + rootCounter);

        // Add to graph
        modelState.getRoot().getChildren().add(gNode);

        // Register mapping (bidirectional)
        entityToGModelId.put(root, nodeId);
        gModelIdToEntity.put(nodeId, root);
        gModelIdToGNode.put(nodeId, gNode);

        rootCounter++;
    }

    @Override
    public void onDeleteRoot(String rootId) {
        LOGGER.info("🗑️ Deleting root from view: {}", rootId);
        
        // 1. Find and remove the bigraph entity from registry
        BigraphEntity<?> entity = gModelIdToEntity.remove(rootId);
        if (entity != null) {
            entityToGModelId.remove(entity);
            LOGGER.info("  ✅ Removed root entity from registry");
        }
        gModelIdToGNode.remove(rootId);
        
        // 2. Remove the GNode from the GModel
        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GNode && child.getId().equals(rootId)) {
                LOGGER.info("  ✅ Removed root GNode: {}", rootId);
                return true;
            }
            return false;
        });
        
        // 3. Remove any edges connected to this root (place edges)
        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GEdge) {
                GEdge edge = (GEdge) child;
                if (rootId.equals(edge.getSourceId()) || rootId.equals(edge.getTargetId())) {
                    LOGGER.info("  ✅ Removed connected edge: {}", edge.getId());
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    public GNode onAddNode(NodeEntity<DynamicControl> node, BigraphEntity<?> parent, DynamicControl control) {
        String stableNodeId = BigraphNodeIdentity.getOrCreateStableId(node);
        // A node is "in a site" if its direct parent is a SiteEntity, or if its parent
        // is a NodeEntity that is itself nested in a site (checked via the entity→GModelId map).
        boolean isNodeInSiteContainer = (parent instanceof SiteEntity) ||
            (parent instanceof NodeEntity && isNestedInSite(entityToGModelId.get(parent)));
        final NodeRenderer.NodeRenderResult rendered = nodeRenderer.renderNode(node, control, stableNodeId, isNodeInSiteContainer);
        final GNode gNode = rendered.gNode;
        final boolean positionFound = rendered.positionFound;
        final String nodeId = gNode.getId();

        // Register mapping (bidirectional) — before adding to graph so helpers can find it
        entityToGModelId.put(node, nodeId);
        gModelIdToEntity.put(nodeId, node);
        gModelIdToGNode.put(nodeId, gNode);

        // For site parents: nest the GNode inside the site GNode (Option A — true GModel containment).
        // Sprotty treats positions as relative to the parent → drag-to-move is automatic.
        // For all other parents: add flat to root with a place-edge arrow.
        if (parent instanceof SiteEntity) {
            String siteId = entityToGModelId.get(parent);
            GNode siteGNode = siteId != null ? gModelIdToGNode.get(siteId) : null;
            if (siteGNode != null) {
                siteChildNodes.computeIfAbsent(siteId, k -> new ArrayList<>()).add(nodeId);
                // Only compute slot-based position when no persisted position exists.
                // Persisted positions (from meta or attributes) are already relative coords.
                if (!positionFound) {
                    positionNodeInsideSite(gNode, (SiteEntity) parent);
                }
                siteGNode.getChildren().add(gNode);
                updateSiteFillState((SiteEntity) parent);
            } else {
                // Fallback: site GNode not found, add flat
                modelState.getRoot().getChildren().add(gNode);
            }
        } else {
            if (parent != null) {
                String parentId = entityToGModelId.get(parent);
                GNode parentGNode = parentId != null ? gModelIdToGNode.get(parentId) : null;

                // If the parent node is itself nested inside a site, nest this child inside
                // the parent GNode as well (relative coords). This keeps the entire subtree
                // visually inside the site container.
                if (parentGNode != null && isNestedInSite(parentId)) {
                    // Node is a child of another node that sits inside a site.
                    // We do NOT nest in the GModel (avoids Sprotty parent-bounds clamping).
                    // Instead we add flat to the site GNode and use a place-edge for hierarchy.
                    GNode siteGNode = findAncestorSiteGNode(parentGNode);
                    if (!positionFound) {
                        if (gNode.getPosition() == null) {
                            gNode.setPosition(GraphFactory.eINSTANCE.createGPoint());
                        }
                        // Place below the parent node (relative to site)
                        double parentRelX = getAbsolutePositionInSite(parentGNode).getX();
                        double parentRelY = getAbsolutePositionInSite(parentGNode).getY();
                        gNode.getPosition().setX(parentRelX);
                        gNode.getPosition().setY(parentRelY + NODE_HEIGHT + 14);
                    }
                    if (siteGNode != null) {
                        siteGNode.getChildren().add(gNode);
                        // Also register in siteChildNodes for resize tracking
                        String siteId = siteGNode.getId();
                        siteChildNodes.computeIfAbsent(siteId, k -> new ArrayList<>()).add(nodeId);
                        // Re-run site size
                        BigraphEntity<?> siteEntity = gModelIdToEntity.get(siteId);
                        if (siteEntity instanceof SiteEntity) updateSiteFillState((SiteEntity) siteEntity);
                    } else {
                        modelState.getRoot().getChildren().add(gNode);
                    }
                    GEdge placeEdge = createPlaceGraphEdge(parentId, nodeId);
                    modelState.getRoot().getChildren().add(placeEdge);
                } else {
                    modelState.getRoot().getChildren().add(gNode);
                    if (parentId != null) {
                        GEdge placeEdge = createPlaceGraphEdge(parentId, nodeId);
                        modelState.getRoot().getChildren().add(placeEdge);
                    }
                }
            } else {
                modelState.getRoot().getChildren().add(gNode);
            }
        }

        nodeCounter++;
        return gNode;
    }

    private GPoint getPersistedNodePosition(final Map<String, GPoint> positions, final String stableNodeId) {
        return positions.get(stableNodeId);
    }

    @Override
    public void onDeleteNode(String nodeId) {
        LOGGER.info("🗑️ Deleting node from view: {}", nodeId);

        // 0. Before removal: find parent site (if any) to potentially update fill state after
        SiteEntity parentSite = findParentSiteOf(nodeId);

        // 1. Find and remove the bigraph entity from registry
        BigraphEntity<?> entity = gModelIdToEntity.remove(nodeId);
        if (entity != null) {
            entityToGModelId.remove(entity);
            LOGGER.info("  ✅ Removed entity from registry: {}", entity);
        }
        gModelIdToGNode.remove(nodeId);

        // 2. Remove the GNode — could be in root (normal node) or inside a site GNode (nested)
        boolean removedFromRoot = modelState.getRoot().getChildren().removeIf(child ->
            child instanceof GNode && child.getId().equals(nodeId));

        if (!removedFromRoot) {
            // Recursively search inside nested GNode trees (site → node → node → ...)
            for (var child : modelState.getRoot().getChildren()) {
                if (child instanceof GNode && removeNestedGNode((GNode) child, nodeId)) {
                    break;
                }
            }
        }
        
        // 3. Remove any edges connected to this node (place edges and link connections)
        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GEdge) {
                GEdge edge = (GEdge) child;
                if (nodeId.equals(edge.getSourceId()) || nodeId.equals(edge.getTargetId())) {
                    LOGGER.info("  ✅ Removed connected edge: {}", edge.getId());
                    return true;
                }
            }
            return false;
        });

        // 4. Remove from siteChildNodes tracking map
        siteChildNodes.values().forEach(list -> list.remove(nodeId));

        // 5. If parent was a site, check if it is now empty → mark site as empty
        if (parentSite != null) {
            updateSiteFillState(parentSite);
        }
    }

    @Override
    public void onMoveNode(String nodeId, String newParentId) {
        // TODO: Update place graph edge
    }

    @Override
    public GNode onAddSite(SiteEntity site, BigraphEntity<?> parent, int indexUnderParent) {
        GNode gNode = GraphFactory.eINSTANCE.createGNode();

        String parentId = parent != null ? entityToGModelId.get(parent) : null;
        String nodeId = "site_" + (parentId != null ? parentId + "_" : "") + site.getIndex();
        gNode.setId(nodeId);
        gNode.setType(BigraphModelTypes.SITE);

        // Position: check meta (persisted position) first, then derive from parent layout
        GPoint position = GraphFactory.eINSTANCE.createGPoint();
        String siteKey = String.valueOf(site.getIndex());
        BigraphMetaInformation meta = modelState.getMetaInformation();
        if (meta != null && meta.getSitePositions().containsKey(siteKey)) {
            GPoint metaPos = meta.getSitePositions().get(siteKey);
            position.setX(metaPos.getX());
            position.setY(metaPos.getY());
        } else {
            double x = START_X + indexUnderParent * (SITE_WIDTH + SITE_HORIZONTAL_GAP);
            double y = START_Y + 3 * SPACING_Y;
            if (parentId != null) {
                GNode parentNode = findParentGNode(parentId);
                if (parentNode != null && parentNode.getPosition() != null && parentNode.getSize() != null) {
                    double px = parentNode.getPosition().getX();
                    double py = parentNode.getPosition().getY();
                    double ph = parentNode.getSize().getHeight();
                    x = px + indexUnderParent * (SITE_WIDTH + SITE_HORIZONTAL_GAP);
                    y = py + ph + SITE_VERTICAL_GAP;
                }
            }
            position.setX(x);
            position.setY(y);
        }
        gNode.setPosition(position);

        GDimension size = GraphFactory.eINSTANCE.createGDimension();
        size.setWidth(SITE_WIDTH);
        size.setHeight(SITE_HEIGHT);
        gNode.setSize(size);

        gNode.getCssClasses().add("bigraph-site");

        gNode.getArgs().put("label", "S" + site.getIndex());
        if (parentId != null) {
            gNode.getArgs().put("parentId", parentId);
        }

        modelState.getRoot().getChildren().add(gNode);

        entityToGModelId.put(site, nodeId);
        gModelIdToEntity.put(nodeId, site);
        gModelIdToGNode.put(nodeId, gNode);

        if (parentId != null) {
            GEdge placeEdge = createPlaceGraphEdge(parentId, nodeId);
            modelState.getRoot().getChildren().add(placeEdge);
        }

        LOGGER.info("✅ Created site GNode: {} under parent {}", nodeId, parentId);
        return gNode;
    }

    /**
     * Resolves the GNode for a given parent ID (root or node). Roots are not in gModelIdToGNode.
     */
    private GNode findParentGNode(String parentId) {
        GNode fromRegistry = gModelIdToGNode.get(parentId);
        if (fromRegistry != null) {
            return fromRegistry;
        }
        for (var child : modelState.getRoot().getChildren()) {
            if (child instanceof GNode && ((GNode) child).getId().equals(parentId)) {
                return (GNode) child;
            }
        }
        return null;
    }

    @Override
    public void onDeleteSite(String siteId) {
        LOGGER.info("🗑️ Deleting site from view: {}", siteId);

        // Clean any remaining nested node state first. In the normal path these nodes
        // are deleted explicitly before the site, but this keeps the view registry
        // consistent even if the site container disappears first.
        List<String> nestedNodeIds = new ArrayList<>(siteChildNodes.getOrDefault(siteId, Collections.emptyList()));
        for (String nestedNodeId : nestedNodeIds) {
            cleanupNodeStateForDeletedSite(nestedNodeId);
        }
        siteChildNodes.remove(siteId);
        
        // 1. Find and remove the bigraph entity from registry
        BigraphEntity<?> entity = gModelIdToEntity.remove(siteId);
        if (entity != null) {
            entityToGModelId.remove(entity);
            LOGGER.info("  ✅ Removed site entity from registry");
        }
        gModelIdToGNode.remove(siteId);
        
        // 2. Remove the GNode from the GModel
        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GNode && child.getId().equals(siteId)) {
                LOGGER.info("  ✅ Removed site GNode: {}", siteId);
                return true;
            }
            return false;
        });
        
        // 3. Remove any edges connected to this site (place edges)
        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GEdge) {
                GEdge edge = (GEdge) child;
                if (siteId.equals(edge.getSourceId()) || siteId.equals(edge.getTargetId())) {
                    LOGGER.info("  ✅ Removed connected edge: {}", edge.getId());
                    return true;
                }
            }
            return false;
        });
    }

    private void cleanupNodeStateForDeletedSite(final String nodeId) {
        BigraphEntity<?> nestedEntity = gModelIdToEntity.remove(nodeId);
        if (nestedEntity != null) {
            entityToGModelId.remove(nestedEntity);
        }
        gModelIdToGNode.remove(nodeId);
        portToNodeGModelId.values().removeIf(nodeId::equals);
        siteChildNodes.values().forEach(childIds -> childIds.remove(nodeId));

        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GEdge) {
                GEdge edge = (GEdge) child;
                return nodeId.equals(edge.getSourceId()) || nodeId.equals(edge.getTargetId());
            }
            return false;
        });
    }

    @Override
    public void onRename(String elementId, String newName) {
        LOGGER.info("✏️ Renaming element in view: {} to {}", elementId, newName);
        
        // Use registry for O(1) lookup
        GNode gNode = gModelIdToGNode.get(elementId);
        if (gNode == null) {
            LOGGER.warn("⚠️ Could not find GNode with ID: {}", elementId);
            return;
        }

        final String oldName = (String) gNode.getArgs().get("name");
        final String controlType = (String) gNode.getArgs().get("controlType");
        final String oldPositionKey = (oldName != null && controlType != null)
            ? BigraphNodeIdentity.toNodePositionKey(oldName, controlType)
            : null;
        final String newPositionKey = (newName != null && controlType != null)
            ? BigraphNodeIdentity.toNodePositionKey(newName, controlType)
            : null;
        
        // For nodes, the label is "Name:ControlType" - preserve the control type
        if (controlType != null) {
            gNode.getArgs().put("label", newName + ":" + controlType);
        } else {
            gNode.getArgs().put("label", newName);
        }
        gNode.getArgs().put("name", newName);

        // Meta-file persistence uses property-based keys (node name + control type).
        // When a node is renamed, we must migrate any stored position entries so that
        // a subsequent Ctrl+S persists the position under the new key.
        final BigraphMetaInformation meta = modelState.getMetaInformation();
        if (meta != null && oldPositionKey != null && newPositionKey != null && !oldPositionKey.equals(newPositionKey)) {
            // Move in both maps to be robust w.r.t. whether a node was stored as relative or absolute.
            final GPoint oldAbsolute = meta.getNodePositions().remove(oldPositionKey);
            if (oldAbsolute != null) {
                meta.getNodePositions().put(newPositionKey, oldAbsolute);
            }
            final GPoint oldRelative = meta.getNodeRelativePositions().remove(oldPositionKey);
            if (oldRelative != null) {
                meta.getNodeRelativePositions().put(newPositionKey, oldRelative);
            }
        }

        // Non-node entities (inner/outer names + hyperedges) store positions keyed directly by their name.
        if (meta != null && oldName != null && !oldName.equals(newName)) {
            if (BigraphModelTypes.INNER_NAME.equals(gNode.getType())) {
                final GPoint oldPos = meta.getInnerNamePositions().remove(oldName);
                if (oldPos != null) { meta.getInnerNamePositions().put(newName, oldPos); }
            } else if (BigraphModelTypes.OUTER_NAME.equals(gNode.getType())) {
                final GPoint oldPos = meta.getOuterNamePositions().remove(oldName);
                if (oldPos != null) { meta.getOuterNamePositions().put(newName, oldPos); }
            } else if (BigraphModelTypes.HYPEREDGE.equals(gNode.getType())) {
                final GPoint oldPos = meta.getEdgePositions().remove(oldName);
                if (oldPos != null) { meta.getEdgePositions().put(newName, oldPos); }
            }
        }
        
        LOGGER.info("✅ Updated GNode label to: {}", gNode.getArgs().get("label"));
    }

    // === LINK GRAPH CALLBACKS ===

    @Override
    public void onAddEdge(Edge edge, Collection<BigraphEntity<?>> connectedPoints) {
        onAddEdge(edge, connectedPoints, Optional.empty());
    }
    
    @Override
    public GNode onAddEdge(Edge edge, Collection<BigraphEntity<?>> connectedPoints, Optional<GPoint> optionalPosition) {
        GNode gNode = GraphFactory.eINSTANCE.createGNode();

        String nodeId = "hyper_edge_" + edge.getName() + "_" + edgeCounter;
        gNode.setId(nodeId);
        gNode.setType(BigraphModelTypes.HYPEREDGE);

        // Position - use provided position or check meta, then default
        GPoint position = GraphFactory.eINSTANCE.createGPoint();
        if (optionalPosition.isPresent()) {
            position.setX(optionalPosition.get().getX());
            position.setY(optionalPosition.get().getY());
        } else {
            BigraphMetaInformation meta = modelState.getMetaInformation();
            if (meta != null && meta.getEdgePositions().containsKey(edge.getName())) {
                GPoint metaPos = meta.getEdgePositions().get(edge.getName());
                position.setX(metaPos.getX());
                position.setY(metaPos.getY());
            } else {
                position.setX(START_X + 3 * SPACING_X);
                position.setY(150.0 + (edgeCounter + 3) * SPACING_Y);
            }
        }
        gNode.setPosition(position);

        // Size
        GDimension size = GraphFactory.eINSTANCE.createGDimension();
        size.setWidth(80.0);
        size.setHeight(30.0);
        gNode.setSize(size);

        // Styling - color is handled by CSS via bigraph-hyper-edge class
        gNode.getCssClasses().add("bigraph-hyper-edge");

        // Arguments
        gNode.getArgs().put("label", edge.getName());
        gNode.getArgs().put("name", edge.getName());
        gNode.getArgs().put("isHyperEdge", true);

        // Add to graph
        modelState.getRoot().getChildren().add(gNode);

        // Register mapping (bidirectional)
        entityToGModelId.put(edge, nodeId);
        gModelIdToEntity.put(nodeId, edge);
        gModelIdToGNode.put(nodeId, gNode);

        // Create connections to all connected points
        if (connectedPoints != null && !connectedPoints.isEmpty()) {
            createLinkConnections(nodeId, connectedPoints);
        }

        edgeCounter++;
        
        LOGGER.info("✅ Created edge GNode: {} at ({}, {})", nodeId, position.getX(), position.getY());
        return gNode;
    }

    @Override
    public void onDeleteEdge(String edgeId) {
        LOGGER.info("🗑️ Deleting edge from view: {}", edgeId);
        
        // 1. Find and remove the bigraph entity from registry
        BigraphEntity<?> entity = gModelIdToEntity.remove(edgeId);
        if (entity != null) {
            removeMetaPositionForEntity(entity);
            entityToGModelId.remove(entity);
            LOGGER.info("  ✅ Removed edge entity from registry");
        }
        gModelIdToGNode.remove(edgeId);
        
        // 2. Remove the GNode (hyperedge visual) from the GModel
        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GNode && child.getId().equals(edgeId)) {
                LOGGER.info("  ✅ Removed edge GNode: {}", edgeId);
                return true;
            }
            return false;
        });
        
        // 3. Remove any link connections connected to this edge
        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GEdge) {
                GEdge gEdge = (GEdge) child;
                if (edgeId.equals(gEdge.getSourceId()) || edgeId.equals(gEdge.getTargetId())) {
                    LOGGER.info("  ✅ Removed link connection: {}", gEdge.getId());
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    public GNode onAddOuterName(OuterName outerName, Collection<BigraphEntity<?>> connectedPoints) {
        return onAddOuterName(outerName, connectedPoints, Optional.empty());
    }

    @Override
    public GNode onAddOuterName(OuterName outerName, Collection<BigraphEntity<?>> connectedPoints, Optional<GPoint> optionalPosition) {
        GNode gNode = GraphFactory.eINSTANCE.createGNode();

        String nodeId = "outer_" + outerName.getName() + "_" + outerNameCounter;
        gNode.setId(nodeId);
        gNode.setType(BigraphModelTypes.OUTER_NAME);

        // Position - use provided position or check meta, then default (right side)
        GPoint position = GraphFactory.eINSTANCE.createGPoint();
        if (optionalPosition.isPresent()) {
            position.setX(optionalPosition.get().getX());
            position.setY(optionalPosition.get().getY());
        } else {
            BigraphMetaInformation meta = modelState.getMetaInformation();
            if (meta != null && meta.getOuterNamePositions().containsKey(outerName.getName())) {
                GPoint metaPos = meta.getOuterNamePositions().get(outerName.getName());
                position.setX(metaPos.getX());
                position.setY(metaPos.getY());
            } else {
                position.setX(START_X + 6 * SPACING_X);
                position.setY(150.0 + outerNameCounter * SPACING_Y);
            }
        }
        gNode.setPosition(position);

        // Size
        GDimension size = GraphFactory.eINSTANCE.createGDimension();
        size.setWidth(100.0);
        size.setHeight(30.0);
        gNode.setSize(size);

        // Styling
        gNode.getCssClasses().add("bigraph-outer-name");

        // Arguments
        gNode.getArgs().put("label", outerName.getName());
        gNode.getArgs().put("name", outerName.getName());

        // Add to graph
        modelState.getRoot().getChildren().add(gNode);

        // Register mapping (bidirectional)
        entityToGModelId.put(outerName, nodeId);
        gModelIdToEntity.put(nodeId, outerName);
        gModelIdToGNode.put(nodeId, gNode);

        // Create connections to all connected points
        if (connectedPoints != null && !connectedPoints.isEmpty()) {
            createLinkConnections(nodeId, connectedPoints);
        }

        outerNameCounter++;
        
        LOGGER.info("✅ Created outer name GNode: {} at ({}, {})", nodeId, position.getX(), position.getY());
        return gNode;
    }

    @Override
    public void onDeleteOuterName(String outerNameId) {
        LOGGER.info("🗑️ Deleting outer name from view: {}", outerNameId);
        
        // 1. Find and remove the bigraph entity from registry
        BigraphEntity<?> entity = gModelIdToEntity.remove(outerNameId);
        if (entity != null) {
            removeMetaPositionForEntity(entity);
            entityToGModelId.remove(entity);
            LOGGER.info("  ✅ Removed outer name entity from registry");
        }
        gModelIdToGNode.remove(outerNameId);
        
        // 2. Remove the GNode from the GModel
        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GNode && child.getId().equals(outerNameId)) {
                LOGGER.info("  ✅ Removed outer name GNode: {}", outerNameId);
                return true;
            }
            return false;
        });
        
        // 3. Remove any link connections connected to this outer name
        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GEdge) {
                GEdge gEdge = (GEdge) child;
                if (outerNameId.equals(gEdge.getSourceId()) || outerNameId.equals(gEdge.getTargetId())) {
                    LOGGER.info("  ✅ Removed link connection: {}", gEdge.getId());
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    public GNode onAddInnerName(InnerName innerName, Link connectedLink) {
        return onAddInnerName(innerName, connectedLink, Optional.empty());
    }

    @Override
    public GNode onAddInnerName(InnerName innerName, Link connectedLink, Optional<GPoint> optionalPosition) {
        GNode gNode = GraphFactory.eINSTANCE.createGNode();

        String nodeId = "inner_" + innerName.getName() + "_" + innerNameCounter;
        gNode.setId(nodeId);
        gNode.setType(BigraphModelTypes.INNER_NAME);

        // Position - use provided position or check meta, then default (left side)
        GPoint position = GraphFactory.eINSTANCE.createGPoint();
        if (optionalPosition.isPresent()) {
            position.setX(optionalPosition.get().getX());
            position.setY(optionalPosition.get().getY());
        } else {
            BigraphMetaInformation meta = modelState.getMetaInformation();
            if (meta != null && meta.getInnerNamePositions().containsKey(innerName.getName())) {
                GPoint metaPos = meta.getInnerNamePositions().get(innerName.getName());
                position.setX(metaPos.getX());
                position.setY(metaPos.getY());
            } else {
                position.setX(10.0);
                position.setY(150.0 + innerNameCounter * SPACING_Y);
            }
        }
        gNode.setPosition(position);

        // Size
        GDimension size = GraphFactory.eINSTANCE.createGDimension();
        size.setWidth(100.0);
        size.setHeight(30.0);
        gNode.setSize(size);

        // Styling
        gNode.getCssClasses().add("bigraph-inner-name");

        // Arguments
        gNode.getArgs().put("label", innerName.getName());
        gNode.getArgs().put("name", innerName.getName());

        // Add to graph
        modelState.getRoot().getChildren().add(gNode);

        // Register mapping (bidirectional)
        entityToGModelId.put(innerName, nodeId);
        gModelIdToEntity.put(nodeId, innerName);
        gModelIdToGNode.put(nodeId, gNode);

        // Note: connections to this inner name are created by onAddEdge/onAddOuterName

        innerNameCounter++;
        
        LOGGER.info("✅ Created inner name GNode: {} at ({}, {})", nodeId, position.getX(), position.getY());
        return gNode;
    }

    @Override
    public void onDeleteInnerName(String innerNameId) {
        LOGGER.info("🗑️ Deleting inner name from view: {}", innerNameId);
        
        // 1. Find and remove the bigraph entity from registry
        BigraphEntity<?> entity = gModelIdToEntity.remove(innerNameId);
        if (entity != null) {
            removeMetaPositionForEntity(entity);
            entityToGModelId.remove(entity);
            LOGGER.info("  ✅ Removed inner name entity from registry");
        }
        gModelIdToGNode.remove(innerNameId);
        
        // 2. Remove the GNode from the GModel
        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GNode && child.getId().equals(innerNameId)) {
                LOGGER.info("  ✅ Removed inner name GNode: {}", innerNameId);
                return true;
            }
            return false;
        });
        
        // 3. Remove any link connections connected to this inner name
        modelState.getRoot().getChildren().removeIf(child -> {
            if (child instanceof GEdge) {
                GEdge gEdge = (GEdge) child;
                if (innerNameId.equals(gEdge.getSourceId()) || innerNameId.equals(gEdge.getTargetId())) {
                    LOGGER.info("  ✅ Removed link connection: {}", gEdge.getId());
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    public void onAddPort(NodeEntity<DynamicControl> node, Port port, Link connectedLink) {
        // Register the port -> node mapping for efficient lookup
        String nodeGModelId = entityToGModelId.get(node);
        LOGGER.info("🔌 onAddPort: node={}, port={} (hash={}), nodeGModelId={}", 
            node.getName(), port, System.identityHashCode(port), nodeGModelId);
        if (nodeGModelId != null) {
            portToNodeGModelId.put(port, nodeGModelId);
        }
    }

    @Override
    public void onConnectPort(String nodeId, int portIndex, String linkId) {
        // TODO: Create connection edge
    }

    /**
     * Removes persisted meta positions for link-graph entities.
     * Centralized here so delete callbacks can reuse one policy.
     */
    private void removeMetaPositionForEntity(final BigraphEntity<?> entity) {
        if (entity == null) {
            return;
        }
        final BigraphMetaInformation meta = modelState.getMetaInformation();
        if (meta == null) {
            return;
        }
        if (entity instanceof InnerName) {
            meta.getInnerNamePositions().remove(((InnerName) entity).getName());
        } else if (entity instanceof OuterName) {
            meta.getOuterNamePositions().remove(((OuterName) entity).getName());
        } else if (entity instanceof Edge) {
            meta.getEdgePositions().remove(((Edge) entity).getName());
        }
    }

    @Override
    public void onDisconnectPort(String nodeId, int portIndex) {
        // TODO: Remove connection edge
    }

    // === POSITION-BASED LOOKUP ===

    @Override
    public NodeEntity<DynamicControl> findDeepestNodeOnPosition(double x, double y) {
        return hitTester.findDeepestNodeOnPosition(x, y);
    }

    /**
     * Checks whether the given GNode (with an optional parent offset for nested nodes)
     * contains the click point (x, y). Returns the corresponding NodeEntity if it is a
     * better (smaller-area) hit than the current best, otherwise returns null.
     */
    @SuppressWarnings("unchecked")
    private NodeEntity<DynamicControl> checkNodeHit(GNode gNode, double x, double y,
            double parentOffsetX, double parentOffsetY,
            NodeEntity<DynamicControl> currentBest, double currentBestArea) {
        return hitTester.checkNodeHit(
            gNode, x, y, parentOffsetX, parentOffsetY, currentBest, currentBestArea);
    }

    @Override
    public RootEntity findRootOnPosition(double x, double y) {
        return hitTester.findRootOnPosition(x, y);
    }

    @Override
    public SiteEntity findSiteOnPosition(double x, double y) {
        return hitTester.findSiteOnPosition(x, y);
    }

    // === HELPER METHODS ===

    /**
     * Checks whether a site currently has node children in the GModel registry,
     * and updates its CSS classes and size accordingly.
     */
    private void updateSiteFillState(SiteEntity site) {
        siteLayout.updateSiteFillState(site);
    }

    @Override
    public void refreshSiteContainingNode(String nodeGModelId) {
        siteLayout.refreshSiteContainingNode(nodeGModelId);
    }

    /**
     * Sets the position of a newly created node relative to its parent site.
     * Since the node will be nested inside siteGNode.getChildren(), Sprotty treats
     * these coordinates as relative to the site's top-left corner.
     */
    private void positionNodeInsideSite(GNode nodeGNode, SiteEntity site) {
        siteLayout.positionNodeInsideSite(nodeGNode, site);
    }

    /**
     * Fits the site GNode tightly around all its nested child nodes.
     *
     * Algorithm:
     * 1. Find the bounding box of all children in site-local coords (minX, minY, maxRight, maxBottom).
     * 2. Shift the site's absolute canvas position by (minX - PADDING, minY - LABEL_RESERVE - PADDING)
     *    so the top-left padding is always uniform.
     * 3. Counter-shift every child's relative position by the same delta so they stay visually fixed.
     * 4. Set the site's new width/height to cover (maxRight - minX) + 2*PADDING.
     */
    private void resizeSiteToWrapChildren(GNode siteGNode, String siteId) {
        siteLayout.resizeSiteToWrapChildren(siteGNode, siteId);
    }

    /**
     * Returns true if the given site GModel ID has at least one node child
     * (tracked via siteChildNodes map, no place-edge lookup needed).
     */
    private boolean hasNodeChildInGModel(String siteId) {
        return siteLayout.hasNodeChildInGModel(siteId);
    }

    /**
     * Finds the parent SiteEntity of a node (by its GModel ID), if any,
     * by checking the siteChildNodes reverse mapping.
     */
    private SiteEntity findParentSiteOf(String nodeId) {
        for (var entry : siteChildNodes.entrySet()) {
            if (entry.getValue().contains(nodeId)) {
                BigraphEntity<?> source = gModelIdToEntity.get(entry.getKey());
                if (source instanceof SiteEntity) {
                    return (SiteEntity) source;
                }
            }
        }
        return null;
    }

    /**
     * Walks up the GModel parent chain from the given node to find the nearest site GNode ancestor.
     * Returns null if no site ancestor exists.
     */
    private GNode findAncestorSiteGNode(GNode gNode) {
        if (gNode.getParent() instanceof GNode parentGNode) {
            String parentId = parentGNode.getId();
            if (gModelIdToEntity.get(parentId) instanceof SiteEntity) return parentGNode;
            return findAncestorSiteGNode(parentGNode);
        }
        // Also check siteChildNodes (for nodes directly in the site, parent may be GModelRoot)
        for (var entry : siteChildNodes.entrySet()) {
            if (entry.getValue().contains(gNode.getId())) {
                return gModelIdToGNode.get(entry.getKey());
            }
        }
        return null;
    }

    /**
     * Computes the position of a GNode relative to its ancestor site's top-left corner.
     * Accumulates offsets walking up through GNode parents until the site is reached.
     */
    private GPoint getAbsolutePositionInSite(GNode gNode) {
        if (gNode.getPosition() == null) {
            GPoint p = GraphFactory.eINSTANCE.createGPoint();
            p.setX(0); p.setY(0);
            return p;
        }
        double x = gNode.getPosition().getX();
        double y = gNode.getPosition().getY();
        if (gNode.getParent() instanceof GNode parentGNode) {
            String parentId = parentGNode.getId();
            if (!(gModelIdToEntity.get(parentId) instanceof SiteEntity)) {
                GPoint parentPos = getAbsolutePositionInSite(parentGNode);
                x += parentPos.getX();
                y += parentPos.getY();
            }
        }
        GPoint result = GraphFactory.eINSTANCE.createGPoint();
        result.setX(x);
        result.setY(y);
        return result;
    }

    /**
     * Recursively searches the children of the given GNode for a child with the given ID
     * and removes it. Returns true if found and removed.
     */
    private boolean removeNestedGNode(GNode parent, String targetId) {
        boolean removed = parent.getChildren().removeIf(
            c -> c instanceof GNode && c.getId().equals(targetId));
        if (removed) {
            LOGGER.info("  ✅ Removed nested GNode {} from parent {}", targetId, parent.getId());
            return true;
        }
        for (var child : parent.getChildren()) {
            if (child instanceof GNode && removeNestedGNode((GNode) child, targetId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isNodeNestedInSite(String nodeId) {
        return isNestedInSite(nodeId);
    }

    /**
     * Returns true if the given node GModel ID is directly nested inside a site
     * (i.e. it appears in siteChildNodes), OR if any of its GModel ancestors are.
     * Used to decide whether a new child should be nested in the GModel tree.
     */
    private boolean isNestedInSite(String nodeId) {
        if (nodeId == null) return false;
        // All nodes in a site (direct or indirect) are tracked in siteChildNodes
        for (var childList : siteChildNodes.values()) {
            if (childList.contains(nodeId)) return true;
        }
        return false;
    }

    private GEdge createPlaceGraphEdge(String parentId, String childId) {
        return linkRenderer.createPlaceGraphEdge(parentId, childId);
    }

    private void createLinkConnections(String linkNodeId, Collection<BigraphEntity<?>> connectedPoints) {
        linkRenderer.createLinkConnections(linkNodeId, connectedPoints);
    }

    /**
     * Get the GModel ID of the node that owns the given port (for link/neighbor queries).
     */
    public Optional<String> getNodeGModelIdForPort(final Port port) {
        return registryLookups.getNodeGModelIdForPort(port);
    }

    // === LOOKUP METHODS ===

    @Override
    public Optional<BigraphEntity<?>> getBigraphEntityForGModelId(String gModelId) {
        return registryLookups.getBigraphEntityForGModelId(gModelId);
    }

    @Override
    public Optional<String> getGModelIdForEntity(BigraphEntity<?> entity) {
        return registryLookups.getGModelIdForEntity(entity);
    }

}
