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

package org.eclipse.glsp.example.bigraph.handler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.Edge;
import org.bigraphs.framework.core.impl.BigraphEntity.InnerName;
import org.bigraphs.framework.core.impl.BigraphEntity.Link;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.OuterName;
import org.bigraphs.framework.core.impl.BigraphEntity.Port;
import org.bigraphs.framework.core.impl.BigraphEntity.RootEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.SiteEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.example.bigraph.views.BigraphView;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.actions.SetDirtyStateAction;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;
import org.eclipse.glsp.server.gmodel.GModelDeleteOperationHandler;

import com.google.inject.Inject;

/**
 * Handles deletion of all bigraph elements (nodes, inner names, outer names, edges, sites, connections).
 * Routes deletion to the appropriate bigraph method based on the element type.
 * For nodes, recursively deletes descendants (children, grandchildren, etc.).
 * For connections (GEdge), disconnects the linked elements.
 */
public class DeleteBigraphElementOperationHandler extends GModelDeleteOperationHandler {

    private static final Logger LOGGER = LogManager.getLogger(DeleteBigraphElementOperationHandler.class);
    
    /** Type for link connections created by the Connect operation */
    private static final String LINK_CONNECTION_TYPE = "bigraph:link-connection";

    private static final class NodeDeletion {
        private final NodeEntity<DynamicControl> entity;
        private final String gModelId;

        private NodeDeletion(final NodeEntity<DynamicControl> entity, final String gModelId) {
            this.entity = entity;
            this.gModelId = gModelId;
        }
    }

    private static final class SiteDeletion {
        private final SiteEntity entity;
        private final String gModelId;

        private SiteDeletion(final SiteEntity entity, final String gModelId) {
            this.entity = entity;
            this.gModelId = gModelId;
        }
    }

    private static final class DeletionPlan {
        private final Set<String> nodeIds = new HashSet<>();
        private final List<NodeDeletion> nodes = new ArrayList<>();
        private final Set<String> siteIds = new HashSet<>();
        private final List<SiteDeletion> sites = new ArrayList<>();

        private boolean addNode(final NodeEntity<DynamicControl> entity, final String gModelId) {
            if (gModelId == null || !nodeIds.add(gModelId)) {
                return false;
            }
            nodes.add(new NodeDeletion(entity, gModelId));
            return true;
        }

        private boolean addSite(final SiteEntity entity, final String gModelId) {
            if (gModelId == null || !siteIds.add(gModelId)) {
                return false;
            }
            sites.add(new SiteDeletion(entity, gModelId));
            return true;
        }
    }

    @Inject
    protected BigraphModelState modelState;

    @Inject
    protected ActionDispatcher actionDispatcher;

    @Override
    public void deleteElements(final List<String> elementIds) {
        LOGGER.info("🗑️ Deleting elements: {}", elementIds);

        PureBigraphMutable bigraph = modelState.getMutableBigraph();
        if (bigraph == null) {
            LOGGER.error("❌ No mutable bigraph available");
            return;
        }

        BigraphView view = modelState.getActiveView();

        for (String elementId : elementIds) {
            // Get the GModelElement to determine its type
            Optional<GModelElement> elementOpt = modelState.getIndex().get(elementId);
            if (elementOpt.isEmpty()) {
                LOGGER.warn("⚠️ Element not found in index: {}", elementId);
                continue;
            }

            GModelElement element = elementOpt.get();
            
            // Handle GEdge elements (connections)
            if (element instanceof GEdge) {
                GEdge gEdge = (GEdge) element;
                String type = gEdge.getType();
                LOGGER.info("🔍 Processing GEdge element: {} (type: {})", elementId, type);
                
                if (LINK_CONNECTION_TYPE.equals(type)) {
                    deleteConnection(gEdge, bigraph, view);
                } else {
                    LOGGER.info("ℹ️ Skipping non-connection GEdge: {} (type: {})", elementId, type);
                }
                continue;
            }
            
            // Handle GNode elements
            if (!(element instanceof GNode)) {
                LOGGER.info("ℹ️ Skipping non-GNode/GEdge element: {}", elementId);
                continue;
            }

            GNode gNode = (GNode) element;
            String type = gNode.getType();

            LOGGER.info("🔍 Processing element: {} (type: {})", elementId, type);

            // Route to appropriate deletion method based on type
            if (BigraphModelTypes.BIGRAPH_NODE.equals(type)) {
                deleteNode(elementId, bigraph, view);
            } else if (BigraphModelTypes.INNER_NAME.equals(type)) {
                closeName(elementId, bigraph, view);
            } else if (BigraphModelTypes.OUTER_NAME.equals(type)) {
                closeName(elementId, bigraph, view);
            } else if (BigraphModelTypes.HYPEREDGE.equals(type)) {
                deleteEdge(elementId, bigraph, view);
            } else if (BigraphModelTypes.SITE.equals(type)) {
                deleteSite(elementId, bigraph, view);
            } else if (BigraphModelTypes.ROOT.equals(type) || BigraphModelTypes.BIGRAPH_ROOT.equals(type)) {
                deleteRoot(elementId, bigraph, view);
            } else {
                LOGGER.warn("⚠️ Unknown element type: {} for element: {}", type, elementId);
            }
        }

        LOGGER.info("🎉 Deletion complete");
    }

    // ============================================
    // NODE DELETION (with recursive descendant handling)
    // ============================================

    private void deleteNode(String elementId, PureBigraphMutable bigraph, BigraphView view) {
        BigraphEntity<?> entity = view.getBigraphEntityForGModelId(elementId)
                .orElse(null);
        if (entity == null) {
            LOGGER.warn("⚠️ No bigraph entity found for node: {}", elementId);
            return;
        }

        if (!(entity instanceof NodeEntity)) {
            LOGGER.warn("⚠️ Entity is not a NodeEntity: {}", elementId);
            return;
        }

        @SuppressWarnings("unchecked")
        NodeEntity<DynamicControl> node = (NodeEntity<DynamicControl>) entity;

        // Collect this node and all descendants, including nodes nested inside sites.
        DeletionPlan deletionPlan = new DeletionPlan();
        deletionPlan.addNode(node, elementId);
        collectDescendants(bigraph, node, deletionPlan, view);

        LOGGER.info("📋 Collected {} nodes and {} sites for deletion (including descendants)", 
            deletionPlan.nodes.size(), deletionPlan.sites.size());

        // Sites can contain nodes that the underlying bigraph implementation may not delete
        // reliably via removeNode() (e.g. nodes nested directly inside a site). Removing sites
        // first lets the bigraph perform its own cleanup, and we then delete remaining nodes.
        deletePlannedSites(bigraph, deletionPlan);
        Set<String> bigraphDeletedNodeIds = deletePlannedNodes(bigraph, deletionPlan);
        deleteNodeViews(view, deletionPlan, bigraphDeletedNodeIds);
        deleteRemovedSiteViews(bigraph, view, deletionPlan);
    }

    private void collectDescendants(
            final PureBigraphMutable bigraph,
            final BigraphEntity<?> parent,
            final DeletionPlan deletionPlan,
            final BigraphView view) {
        for (BigraphEntity<?> child : bigraph.getChildrenOf(parent)) {
            if (child instanceof NodeEntity) {
                @SuppressWarnings("unchecked")
                NodeEntity<DynamicControl> childNode = (NodeEntity<DynamicControl>) child;

                Optional<String> childGModelId = findGModelIdForEntity(childNode, view);
                if (childGModelId.isPresent()) {
                    if (deletionPlan.addNode(childNode, childGModelId.get())) {
                        LOGGER.info("  📌 Marked node for deletion: {} (GModel ID: {})",
                            childNode.getName(), childGModelId.get());
                    }
                    collectDescendants(bigraph, childNode, deletionPlan, view);
                } else {
                    LOGGER.warn("⚠️ Could not find GModel ID for child node: {}", childNode.getName());
                }
            } else if (child instanceof SiteEntity) {
                SiteEntity site = (SiteEntity) child;
                Optional<String> siteGModelId = findGModelIdForEntity(site, view);
                if (siteGModelId.isPresent()) {
                    collectDescendants(bigraph, site, deletionPlan, view);
                    if (deletionPlan.addSite(site, siteGModelId.get())) {
                        LOGGER.info("  📌 Marked site for deletion: index={} (GModel ID: {})",
                            site.getIndex(), siteGModelId.get());
                    }
                } else {
                    LOGGER.warn("⚠️ Could not find GModel ID for child site: index={}", site.getIndex());
                }
            }
        }
    }

    private Set<String> deletePlannedNodes(final PureBigraphMutable bigraph, final DeletionPlan deletionPlan) {
        Set<String> bigraphDeletedNodeIds = new HashSet<>();
        for (int i = deletionPlan.nodes.size() - 1; i >= 0; i--) {
            NodeDeletion nodeDeletion = deletionPlan.nodes.get(i);
            if (!nodeExistsInBigraph(bigraph, nodeDeletion.entity)) {
                bigraphDeletedNodeIds.add(nodeDeletion.gModelId);
                continue;
            }
            try {
                bigraph.removeNode(nodeDeletion.entity);
                LOGGER.info("✅ Deleted bigraph node: {}", nodeDeletion.entity.getName());
                bigraphDeletedNodeIds.add(nodeDeletion.gModelId);
            } catch (Exception e) {
                LOGGER.error("❌ Failed to delete bigraph node: {}", nodeDeletion.entity.getName(), e);
                BigraphNotifications.notifyError(actionDispatcher,
                        "Could not delete node '" + nodeDeletion.entity.getName() + "': " + e.getMessage());
            }
        }
        return bigraphDeletedNodeIds;
    }

    private void deleteNodeViews(final BigraphView view, final DeletionPlan deletionPlan,
            final Set<String> bigraphDeletedNodeIds) {
        for (int i = deletionPlan.nodes.size() - 1; i >= 0; i--) {
            NodeDeletion nodeDeletion = deletionPlan.nodes.get(i);
            if (bigraphDeletedNodeIds.contains(nodeDeletion.gModelId)) {
                view.onDeleteNode(nodeDeletion.gModelId);
                LOGGER.info("✅ Deleted node from view: {}", nodeDeletion.gModelId);
            } else {
                LOGGER.warn("⚠️ Skipping view deletion for node {} — bigraph removal failed", nodeDeletion.gModelId);
            }
        }
    }

    private void deletePlannedSites(final PureBigraphMutable bigraph, final DeletionPlan deletionPlan) {
        for (int i = deletionPlan.sites.size() - 1; i >= 0; i--) {
            SiteDeletion siteDeletion = deletionPlan.sites.get(i);
            if (!siteExistsInBigraph(bigraph, siteDeletion.entity)) {
                continue;
            }
            try {
                bigraph.removeSite(siteDeletion.entity);
                LOGGER.info("✅ Deleted bigraph site (descendant): index={}", siteDeletion.entity.getIndex());
            } catch (Exception e) {
                LOGGER.error("❌ Failed to delete descendant site: index={}", siteDeletion.entity.getIndex(), e);
            }
        }
    }

    private void deleteRemovedSiteViews(final PureBigraphMutable bigraph, final BigraphView view,
            final DeletionPlan deletionPlan) {
        for (SiteDeletion siteDeletion : deletionPlan.sites) {
            if (!siteExistsInBigraph(bigraph, siteDeletion.entity)) {
                view.onDeleteSite(siteDeletion.gModelId);
                LOGGER.info("✅ Deleted site from view: {}", siteDeletion.gModelId);
            } else {
                LOGGER.warn("⚠️ Skipping view deletion for site {} — site still exists in bigraph", siteDeletion.gModelId);
            }
        }
    }

    private boolean siteExistsInBigraph(final PureBigraphMutable bigraph, final SiteEntity site) {
        return bigraph.getSites().stream().anyMatch(existingSite -> existingSite == site);
    }

    private boolean nodeExistsInBigraph(final PureBigraphMutable bigraph, final NodeEntity<DynamicControl> node) {
        return bigraph.getNodes().stream().anyMatch(existingNode -> existingNode == node);
    }

    private void closeName(final String elementId, final PureBigraphMutable bigraph, final BigraphView view) {
        final BigraphEntity<?> entity = view.getBigraphEntityForGModelId(elementId).orElse(null);
        if (entity == null) {
            return;
        }
        try {
            if (entity instanceof InnerName) {
                bigraph.closeName((InnerName) entity);
                view.onDeleteInnerName(elementId);
            } else if (entity instanceof OuterName) {
                bigraph.closeName((OuterName) entity);
                view.onDeleteOuterName(elementId);
            } else {
                return;
            }
        } catch (final Exception e) {
            LOGGER.error("❌ Failed to close name: {}", elementId, e);
            BigraphNotifications.notifyError(actionDispatcher, "Could not close name: " + e.getMessage());
            return;
        }

        final var root = modelState.getRoot();
        root.setRevision(root.getRevision() + 1);
        actionDispatcher.dispatch(new SetDirtyStateAction(true, "operation"));
        actionDispatcher.dispatch(new UpdateModelAction(root, false));
    }

    // ============================================
    // EDGE (HYPEREDGE) DELETION
    // ============================================

    private void deleteEdge(String elementId, PureBigraphMutable bigraph, BigraphView view) {
        BigraphEntity<?> entity = view.getBigraphEntityForGModelId(elementId)
                .orElse(null);
        if (entity == null) {
            LOGGER.warn("⚠️ No bigraph entity found for edge: {}", elementId);
            return;
        }

        if (!(entity instanceof Edge)) {
            LOGGER.warn("⚠️ Entity is not an Edge: {}", elementId);
            return;
        }

        Edge edge = (Edge) entity;

        try {
            bigraph.removeEdge(edge);
            LOGGER.info("✅ Deleted bigraph edge");

            view.onDeleteEdge(elementId);
            LOGGER.info("✅ Deleted edge from view: {}", elementId);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to delete edge: {}", elementId, e);
            BigraphNotifications.notifyError(actionDispatcher,
                    "Could not delete edge: " + e.getMessage());
        }
    }

    // ============================================
    // SITE DELETION
    // ============================================

    private void deleteSite(String elementId, PureBigraphMutable bigraph, BigraphView view) {
        BigraphEntity<?> entity = view.getBigraphEntityForGModelId(elementId)
                .orElse(null);
        if (entity == null) {
            LOGGER.warn("⚠️ No bigraph entity found for site: {}", elementId);
            return;
        }

        if (!(entity instanceof SiteEntity)) {
            LOGGER.warn("⚠️ Entity is not a SiteEntity: {}", elementId);
            return;
        }

        SiteEntity site = (SiteEntity) entity;

        DeletionPlan deletionPlan = new DeletionPlan();
        collectDescendants(bigraph, site, deletionPlan, view);
        Set<String> bigraphDeletedNodeIds = deletePlannedNodes(bigraph, deletionPlan);

        try {
            bigraph.removeSite(site);
            LOGGER.info("✅ Deleted bigraph site: index={}", site.getIndex());
        } catch (Exception e) {
            LOGGER.error("❌ Failed to delete site: index={}", site.getIndex(), e);
            BigraphNotifications.notifyError(actionDispatcher,
                    "Could not delete site (index=" + site.getIndex() + "): " + e.getMessage());
        }

        deleteNodeViews(view, deletionPlan, bigraphDeletedNodeIds);
        deleteRemovedSiteViews(bigraph, view, deletionPlan);
        if (!siteExistsInBigraph(bigraph, site)) {
            view.onDeleteSite(elementId);
            LOGGER.info("✅ Deleted site from view: {}", elementId);
        } else {
            LOGGER.warn("⚠️ Skipping view deletion for site {} — site still exists in bigraph", elementId);
        }
    }

    // ============================================
    // ROOT DELETION
    // ============================================

    private void deleteRoot(String elementId, PureBigraphMutable bigraph, BigraphView view) {
        BigraphEntity<?> entity = view.getBigraphEntityForGModelId(elementId)
                .orElse(null);
        if (entity == null) {
            LOGGER.warn("⚠️ No bigraph entity found for root: {}", elementId);
            return;
        }

        if (!(entity instanceof RootEntity)) {
            LOGGER.warn("⚠️ Entity is not a RootEntity: {}", elementId);
            return;
        }

        RootEntity root = (RootEntity) entity;

        DeletionPlan deletionPlan = new DeletionPlan();
        collectDescendants(bigraph, root, deletionPlan, view);

        LOGGER.info("📋 Root {} has {} nodes and {} sites to delete first", 
            root.getIndex(), deletionPlan.nodes.size(), deletionPlan.sites.size());

        Set<String> bigraphDeletedNodeIds = deletePlannedNodes(bigraph, deletionPlan);

        try {
            // Now delete the root itself
            bigraph.removeRoot(root);
            LOGGER.info("✅ Deleted bigraph root: {}", root.getIndex());
        } catch (Exception e) {
            LOGGER.error("❌ Failed to delete root: {}", root.getIndex(), e);
            BigraphNotifications.notifyError(actionDispatcher,
                    "Could not delete root (index=" + root.getIndex() + "): " + e.getMessage());
        }

        deleteNodeViews(view, deletionPlan, bigraphDeletedNodeIds);
        deleteRemovedSiteViews(bigraph, view, deletionPlan);
        if (bigraph.getRoots().stream().noneMatch(existingRoot -> existingRoot == root)) {
            view.onDeleteRoot(elementId);
            LOGGER.info("✅ Deleted root from view: {}", elementId);
        } else {
            LOGGER.warn("⚠️ Skipping view deletion for root {} — root still exists in bigraph", elementId);
        }
    }

    // ============================================
    // CONNECTION DELETION
    // ============================================

    /**
     * Deletes a link connection (GEdge) by disconnecting the linked elements in the bigraph.
     * Handles connections between:
     * - Node (port) ↔ Edge
     * - Node (port) ↔ OuterName
     * - InnerName ↔ Edge
     * - InnerName ↔ OuterName
     */
    private void deleteConnection(GEdge gEdge, PureBigraphMutable bigraph, BigraphView view) {
        String sourceId = gEdge.getSourceId();
        String targetId = gEdge.getTargetId();
        
        LOGGER.info("🔗 Deleting connection: {} → {}", sourceId, targetId);
        
        // Get the source and target elements
        Optional<GModelElement> sourceOpt = modelState.getIndex().get(sourceId);
        Optional<GModelElement> targetOpt = modelState.getIndex().get(targetId);
        
        if (sourceOpt.isEmpty() || targetOpt.isEmpty()) {
            LOGGER.warn("⚠️ Could not find source or target for connection");
            // Still remove the GEdge from the view
            removeConnectionFromView(gEdge);
            return;
        }
        
        GModelElement source = sourceOpt.get();
        GModelElement target = targetOpt.get();
        String sourceType = source.getType();
        String targetType = target.getType();
        
        LOGGER.info("   Source: {} (type: {})", sourceId, sourceType);
        LOGGER.info("   Target: {} (type: {})", targetId, targetType);
        
        try {
            // Determine connection type and disconnect accordingly
            if (isNode(sourceType) && isLinkTarget(targetType)) {
                // Node → Edge/OuterName: disconnect the port
                disconnectNodeFromLink(sourceId, targetId, bigraph, view);
            } else if (isLinkTarget(sourceType) && isNode(targetType)) {
                // Edge/OuterName → Node: disconnect the port (symmetric case)
                disconnectNodeFromLink(targetId, sourceId, bigraph, view);
            } else if (isInnerName(sourceType) && isLinkTarget(targetType)) {
                // InnerName → Edge/OuterName: disconnect the inner name
                disconnectInnerNameFromLink(sourceId, bigraph, view);
            } else if (isLinkTarget(sourceType) && isInnerName(targetType)) {
                // Edge/OuterName → InnerName: disconnect the inner name (symmetric case)
                disconnectInnerNameFromLink(targetId, bigraph, view);
            } else {
                LOGGER.warn("⚠️ Unknown connection type: {} → {}", sourceType, targetType);
            }
            
            // Remove the GEdge from the view
            removeConnectionFromView(gEdge);
            
            LOGGER.info("✅ Deleted connection: {}", gEdge.getId());
        } catch (Exception e) {
            LOGGER.error("❌ Failed to delete connection: {}", gEdge.getId(), e);
            BigraphNotifications.notifyError(actionDispatcher,
                    "Could not delete connection: " + e.getMessage());
        }
    }
    
    /**
     * Disconnects a node's port from a link (edge or outer name).
     */
    private void disconnectNodeFromLink(String nodeId, String linkId, PureBigraphMutable bigraph, BigraphView view) {
        BigraphEntity<?> nodeEntity = view.getBigraphEntityForGModelId(nodeId)
                .orElse(null);
        BigraphEntity<?> linkEntity = view.getBigraphEntityForGModelId(linkId)
                .orElse(null);
        
        if (!(nodeEntity instanceof NodeEntity)) {
            LOGGER.warn("⚠️ Source is not a NodeEntity: {}", nodeId);
            return;
        }
        
        if (!(linkEntity instanceof Link)) {
            LOGGER.warn("⚠️ Target is not a Link: {}", linkId);
            return;
        }
        
        @SuppressWarnings("unchecked")
        NodeEntity<DynamicControl> node = (NodeEntity<DynamicControl>) nodeEntity;
        Link link = (Link) linkEntity;
        
        // Find the port that connects this node to this link
        List<Port> ports = (List<Port>) bigraph.getPorts(node);
        Port portToDisconnect = null;
        
        for (Port port : ports) {
            Link portLink = bigraph.getLinkOfPoint(port);
            if (portLink != null && portLink.equals(link)) {
                portToDisconnect = port;
                break;
            }
        }
        
        if (portToDisconnect == null) {
            LOGGER.warn("⚠️ No port found connecting node '{}' to link", node.getName());
            return;
        }
        
        // Disconnect the port
        bigraph.disconnectPort(portToDisconnect);
        LOGGER.info("✅ Disconnected port from link for node '{}'", node.getName());
    }
    
    /**
     * Disconnects an inner name from its link (edge or outer name).
     */
    private void disconnectInnerNameFromLink(String innerNameId, PureBigraphMutable bigraph, BigraphView view) {
        BigraphEntity<?> entity = view.getBigraphEntityForGModelId(innerNameId)
                .orElse(null);
        
        if (!(entity instanceof InnerName)) {
            LOGGER.warn("⚠️ Entity is not an InnerName: {}", innerNameId);
            return;
        }
        
        InnerName innerName = (InnerName) entity;
        
        // Disconnect the inner name from its link
        bigraph.disconnectInnerName(innerName);
        LOGGER.info("✅ Disconnected inner name '{}' from link", innerName.getName());
    }
    
    /**
     * Removes the GEdge from the view/model.
     */
    private void removeConnectionFromView(GEdge gEdge) {
        modelState.getRoot().getChildren().remove(gEdge);
        LOGGER.info("✅ Removed connection GEdge from view: {}", gEdge.getId());
    }
    
    // ============================================
    // TYPE CHECK HELPERS
    // ============================================
    
    private boolean isNode(String type) {
        return BigraphModelTypes.BIGRAPH_NODE.equals(type);
    }
    
    private boolean isLinkTarget(String type) {
        return BigraphModelTypes.EDGE.equals(type) || 
               BigraphModelTypes.HYPEREDGE.equals(type) ||
               BigraphModelTypes.BIGRAPH_EDGE.equals(type) ||
               BigraphModelTypes.OUTER_NAME.equals(type);
    }
    
    private boolean isInnerName(String type) {
        return BigraphModelTypes.INNER_NAME.equals(type);
    }

    // ============================================
    // HELPER METHODS
    // ============================================

    private Optional<String> findGModelIdForEntity(BigraphEntity<?> targetEntity, BigraphView view) {
        return view.getGModelIdForEntity(targetEntity);
    }
}

