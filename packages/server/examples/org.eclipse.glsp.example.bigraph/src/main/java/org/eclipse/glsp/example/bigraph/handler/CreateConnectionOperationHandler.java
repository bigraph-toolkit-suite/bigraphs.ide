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

import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.graph.GModelIndex;
import org.eclipse.glsp.graph.GraphFactory;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.gmodel.GModelCreateEdgeOperationHandler;
import org.eclipse.glsp.server.model.GModelState;
import org.eclipse.glsp.server.operations.CreateEdgeOperation;
import org.eclipse.glsp.server.utils.GModelUtil;

import com.google.inject.Inject;

/**
 * Handler for creating connections in the Bigraph Link Graph.
 * 
 * <p>Connections are from Points to Links:</p>
 * <ul>
 *   <li>Node (via Port) → Edge</li>
 *   <li>Node (via Port) → OuterName</li>
 *   <li>InnerName → Edge</li>
 *   <li>InnerName → OuterName</li>
 * </ul>
 * 
 * <p>NOT allowed:</p>
 * <ul>
 *   <li>Link → Link (Edge ↔ Edge, Edge ↔ OuterName, OuterName ↔ OuterName)</li>
 *   <li>Point → Point directly (must go through a Link)</li>
 * </ul>
 * 
 * This implements Robin Milner's Bigraph Link Graph semantics where Points connect TO Links.
 */
public class CreateConnectionOperationHandler extends GModelCreateEdgeOperationHandler {

    private static final Logger LOGGER = LogManager.getLogger(CreateConnectionOperationHandler.class);

    @Inject
    protected BigraphModelState modelState;

    @Inject
    protected ActionDispatcher actionDispatcher;

    public CreateConnectionOperationHandler() {
        super(BigraphModelTypes.CONNECTION, "Connect");
    }

    /**
     * Overrides the base class to swallow the IllegalArgumentException that GLSP
     * throws when createEdge() returns Optional.empty(). We already showed the user
     * a friendly notification at that point, so a stack trace is unnecessary.
     */
    @Override
    public void executeCreation(final CreateEdgeOperation operation) {
        if (operation.getSourceElementId() == null || operation.getTargetElementId() == null) {
            throw new IllegalArgumentException("Incomplete create connection action");
        }

        GModelIndex index = modelState.getIndex();

        Optional<GModelElement> source = index.findElement(operation.getSourceElementId(),
                GModelUtil.IS_CONNECTABLE);
        Optional<GModelElement> target = index.findElement(operation.getTargetElementId(),
                GModelUtil.IS_CONNECTABLE);

        if (!source.isPresent() || !target.isPresent()) {
            throw new IllegalArgumentException(
                    "Invalid source or target for source ID " + operation.getSourceElementId()
                    + " and target ID " + operation.getTargetElementId());
        }

        Optional<GEdge> connection = createEdge(source.get(), target.get(), modelState);
        if (!connection.isPresent()) {
            // createEdge already sent a user-facing notification — just return silently.
            return;
        }
        modelState.getRoot().getChildren().add(connection.get());
    }

    @Override
    protected Optional<GEdge> createEdge(final GModelElement source, final GModelElement target,
             final GModelState gModelState) {
        
        LOGGER.info("🔗 Creating Connection");
        LOGGER.info("   Source: {} (type: {})", source.getId(), source.getType());
        LOGGER.info("   Target: {} (type: {})", target.getId(), target.getType());

        try {
            // === STEP 1: Validate and determine connection type ===
            ConnectionType connectionType = validateAndGetConnectionType(source, target);
            if (connectionType == null) {
                LOGGER.warn("❌ Invalid connection: {} → {}", source.getType(), target.getType());
                BigraphNotifications.notifyWarning(actionDispatcher,
                        "Invalid connection: " + source.getType() + " cannot connect to " + target.getType()
                        + ". Only Point → Link connections are allowed (Node/InnerName → Edge/OuterName).");
                return Optional.empty();
            }
            
            LOGGER.info("   Connection type: {}", connectionType);

            // === STEP 2: Update Bigraph Model ===
            PureBigraphMutable bigraph = this.modelState.getMutableBigraph();
            if (bigraph == null) {
                LOGGER.error("❌ No mutable bigraph available");
                BigraphNotifications.notifyError(actionDispatcher, "Cannot create connection: bigraph model is not available.");
                return Optional.empty();
            }

            boolean success = createBigraphConnection(bigraph, source, target, connectionType);
            if (!success) {
                LOGGER.error("❌ Failed to create connection in bigraph");
                BigraphNotifications.notifyError(actionDispatcher,
                        "Could not create connection — the node may have no free ports (check arity) "
                        + "or the elements could not be found in the bigraph model.");
                return Optional.empty();
            }

            LOGGER.info("✅ Updated Bigraph model with connection");

            // === STEP 3: Create GEdge for visualization ===
            GEdge gEdge = GraphFactory.eINSTANCE.createGEdge();
            gEdge.setId("connection_" + System.currentTimeMillis());
            gEdge.setType("bigraph:link-connection");
            gEdge.setSourceId(source.getId());
            gEdge.setTargetId(target.getId());
            gEdge.getCssClasses().add("bigraph-link-connection");
            gEdge.getArgs().put("connectionType", connectionType.name());

            LOGGER.info("✅ Successfully created connection: {} → {}", source.getId(), target.getId());

            return Optional.of(gEdge);

        } catch (Exception e) {
            LOGGER.error("❌ Failed to create connection", e);
            BigraphNotifications.notifyError(actionDispatcher, "Could not create connection: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Connection types supported by the bigraph link graph.
     */
    private enum ConnectionType {
        NODE_TO_EDGE,       // Node (port) → Edge
        NODE_TO_OUTERNAME,  // Node (port) → OuterName
        INNERNAME_TO_EDGE,  // InnerName → Edge
        INNERNAME_TO_OUTERNAME // InnerName → OuterName
    }

    /**
     * Validates the connection and determines its type.
     * 
     * @return ConnectionType if valid, null if invalid
     */
    private ConnectionType validateAndGetConnectionType(GModelElement source, GModelElement target) {
        String sourceType = source.getType();
        String targetType = target.getType();
        
        // Check for Point → Link connections (in either direction)
        
        // Node → Edge
        if (isNode(sourceType) && isEdge(targetType)) {
            return ConnectionType.NODE_TO_EDGE;
        }
        if (isEdge(sourceType) && isNode(targetType)) {
            return ConnectionType.NODE_TO_EDGE; // Symmetric
        }
        
        // Node → OuterName
        if (isNode(sourceType) && isOuterName(targetType)) {
            return ConnectionType.NODE_TO_OUTERNAME;
        }
        if (isOuterName(sourceType) && isNode(targetType)) {
            return ConnectionType.NODE_TO_OUTERNAME; // Symmetric
        }
        
        // InnerName → Edge
        if (isInnerName(sourceType) && isEdge(targetType)) {
            return ConnectionType.INNERNAME_TO_EDGE;
        }
        if (isEdge(sourceType) && isInnerName(targetType)) {
            return ConnectionType.INNERNAME_TO_EDGE; // Symmetric
        }
        
        // InnerName → OuterName
        if (isInnerName(sourceType) && isOuterName(targetType)) {
            return ConnectionType.INNERNAME_TO_OUTERNAME;
        }
        if (isOuterName(sourceType) && isInnerName(targetType)) {
            return ConnectionType.INNERNAME_TO_OUTERNAME; // Symmetric
        }
        
        // All other combinations are invalid
        return null;
    }
    
    private boolean isNode(String type) {
        return BigraphModelTypes.BIGRAPH_NODE.equals(type);
    }
    
    private boolean isEdge(String type) {
        return BigraphModelTypes.EDGE.equals(type) || 
               BigraphModelTypes.HYPEREDGE.equals(type) ||
               BigraphModelTypes.BIGRAPH_EDGE.equals(type);
    }
    
    private boolean isOuterName(String type) {
        return BigraphModelTypes.OUTER_NAME.equals(type);
    }
    
    private boolean isInnerName(String type) {
        return BigraphModelTypes.INNER_NAME.equals(type);
    }

    /**
     * Creates the actual bigraph connection based on the connection type.
     */
    private boolean createBigraphConnection(PureBigraphMutable bigraph, 
            GModelElement source, GModelElement target, ConnectionType connectionType) {
        
        switch (connectionType) {
            case NODE_TO_EDGE:
                return connectNodeToEdge(bigraph, source, target);
            case NODE_TO_OUTERNAME:
                return connectNodeToOuterName(bigraph, source, target);
            case INNERNAME_TO_EDGE:
                return connectInnerNameToEdge(bigraph, source, target);
            case INNERNAME_TO_OUTERNAME:
                return connectInnerNameToOuterName(bigraph, source, target);
            default:
                return false;
        }
    }

    private boolean connectNodeToEdge(PureBigraphMutable bigraph, GModelElement source, GModelElement target) {
        GModelElement nodeElement = isNode(source.getType()) ? source : target;
        GModelElement edgeElement = isNode(source.getType()) ? target : source;

        BigraphEntity.NodeEntity<DynamicControl> node = getNodeByGModelId(nodeElement.getId());
        BigraphEntity.Edge edge = getEdgeByGModelId(edgeElement.getId());

        if (node == null || edge == null) {
            LOGGER.error("❌ Could not find node or edge in bigraph");
            return false;
        }

        return connectNodeToLinkSafe(bigraph, node, edge);
    }

    private boolean connectNodeToOuterName(PureBigraphMutable bigraph, GModelElement source, GModelElement target) {
        GModelElement nodeElement = isNode(source.getType()) ? source : target;
        GModelElement outerNameElement = isNode(source.getType()) ? target : source;

        BigraphEntity.NodeEntity<DynamicControl> node = getNodeByGModelId(nodeElement.getId());
        BigraphEntity.OuterName outerName = getOuterNameByGModelId(outerNameElement.getId());

        if (node == null || outerName == null) {
            LOGGER.error("❌ Could not find node or outer name in bigraph");
            return false;
        }

        return connectNodeToLinkSafe(bigraph, node, outerName);
    }

    /**
     * Delegates to {@link PureBigraphMutable#connectNodeToLink}, catching the
     * {@link IllegalStateException} that the framework throws when arity is exhausted,
     * and converting it into a user-facing notification.
     */
    @SuppressWarnings("unchecked")
    private boolean connectNodeToLinkSafe(
            PureBigraphMutable bigraph,
            BigraphEntity.NodeEntity<DynamicControl> node,
            BigraphEntity.Link link) {
        try {
            bigraph.connectNodeToLink((BigraphEntity.NodeEntity<?>) node, link);
            LOGGER.info("✅ Connected node '{}' to link '{}'", node.getName(), link.getName());
            return true;
        } catch (IllegalStateException e) {
            int arity = node.getControl().getArity().getValue();
            LOGGER.error("❌ Node '{}' has no available ports (arity={})", node.getName(), arity);
            BigraphNotifications.notifyWarning(actionDispatcher,
                    "Node '" + node.getName() + "' has no free ports "
                    + "(arity=" + arity + ", all ports already connected).");
            return false;
        }
    }

    private boolean connectInnerNameToEdge(PureBigraphMutable bigraph, GModelElement source, GModelElement target) {
        // Determine which is the inner name and which is the edge
        GModelElement innerNameElement = isInnerName(source.getType()) ? source : target;
        GModelElement edgeElement = isInnerName(source.getType()) ? target : source;
        
        // Get inner name and edge from bigraph
        BigraphEntity.InnerName innerName = getInnerNameByGModelId(innerNameElement.getId());
        BigraphEntity.Edge edge = getEdgeByGModelId(edgeElement.getId());
        
        if (innerName == null || edge == null) {
            LOGGER.error("❌ Could not find inner name or edge in bigraph");
            return false;
        }
        
        // Connect inner name to edge
        bigraph.connectInnerNameToLink(innerName, edge);
        
        LOGGER.info("✅ Connected inner name '{}' to edge '{}'", innerName.getName(), edge.getName());
        return true;
    }

    private boolean connectInnerNameToOuterName(PureBigraphMutable bigraph, GModelElement source, GModelElement target) {
        // Determine which is the inner name and which is the outer name
        GModelElement innerNameElement = isInnerName(source.getType()) ? source : target;
        GModelElement outerNameElement = isInnerName(source.getType()) ? target : source;
        
        // Get inner name and outer name from bigraph
        BigraphEntity.InnerName innerName = getInnerNameByGModelId(innerNameElement.getId());
        BigraphEntity.OuterName outerName = getOuterNameByGModelId(outerNameElement.getId());
        
        if (innerName == null || outerName == null) {
            LOGGER.error("❌ Could not find inner name or outer name in bigraph");
            return false;
        }
        
        // Connect inner name to outer name
        bigraph.connectInnerNameToLink(innerName, outerName);
        
        LOGGER.info("✅ Connected inner name '{}' to outer name '{}'", innerName.getName(), outerName.getName());
        return true;
    }

    // === Lookup methods ===
    
    @SuppressWarnings("unchecked")
    private BigraphEntity.NodeEntity<DynamicControl> getNodeByGModelId(String gModelId) {
        BigraphEntity<?> entity = modelState.getActiveView().getBigraphEntityForGModelId(gModelId)
                .orElse(null);
        if (entity instanceof BigraphEntity.NodeEntity) {
            return (BigraphEntity.NodeEntity<DynamicControl>) entity;
        }
        return null;
    }
    
    private BigraphEntity.Edge getEdgeByGModelId(String gModelId) {
        BigraphEntity<?> entity = modelState.getActiveView().getBigraphEntityForGModelId(gModelId)
                .orElse(null);
        if (entity instanceof BigraphEntity.Edge) {
            return (BigraphEntity.Edge) entity;
        }
        return null;
    }
    
    private BigraphEntity.OuterName getOuterNameByGModelId(String gModelId) {
        BigraphEntity<?> entity = modelState.getActiveView().getBigraphEntityForGModelId(gModelId)
                .orElse(null);
        if (entity instanceof BigraphEntity.OuterName) {
            return (BigraphEntity.OuterName) entity;
        }
        return null;
    }
    
    private BigraphEntity.InnerName getInnerNameByGModelId(String gModelId) {
        BigraphEntity<?> entity = modelState.getActiveView().getBigraphEntityForGModelId(gModelId)
                .orElse(null);
        if (entity instanceof BigraphEntity.InnerName) {
            return (BigraphEntity.InnerName) entity;
        }
        return null;
    }
}

