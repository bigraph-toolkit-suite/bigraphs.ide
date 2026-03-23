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

import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.graph.GraphFactory;
import org.eclipse.glsp.server.gmodel.GModelCreateEdgeOperationHandler;
import org.eclipse.glsp.server.model.GModelState;

import com.google.inject.Inject;

/**
 * Handler for creating Hyper Edges (Link Graph connections) in Bigraph diagrams.
 * 
 * Hyper Edges connect:
 * - Inner Name → Node (Port)
 * - Node (Port) → Outer Name
 * - Inner Name → Outer Name
 * - Node (Port) → Node (Port)
 * 
 * This implements the Bigraph Link Graph semantics.
 */
public class CreateHyperEdgeOperationHandler extends GModelCreateEdgeOperationHandler {

    private static final Logger LOGGER = LogManager.getLogger(CreateHyperEdgeOperationHandler.class);

    @Inject
    protected BigraphModelState modelState;

    public CreateHyperEdgeOperationHandler() {
        super(BigraphModelTypes.HYPEREDGE, "Hyper Edge");
    }

    @Override
    protected Optional<GEdge> createEdge(final GModelElement source, final GModelElement target,
             final GModelState modelState) {
        
        // LOGGER.info("🔗 Creating Hyper Edge connection");
        // LOGGER.info("   Source: {} (type: {})", source.getId(), source.getType());
        // LOGGER.info("   Target: {} (type: {})", target.getId(), target.getType());

        // try {
        //     // Validate connection types
        //     if (!isValidConnection(source, target)) {
        //         LOGGER.warn("❌ Invalid connection: {} → {}", source.getType(), target.getType());
        //         return Optional.empty();
        //     }

        //     // === STEP 1: Update Bigraph Model ===
        //     LOGGER.info("📝 Step 1: Updating Bigraph model");
        //     PureBigraphMutable bigraph = this.modelState.getMutableBigraph();
        //     if (bigraph == null) {
        //         LOGGER.error("❌ No mutable bigraph available");
        //         return Optional.empty();
        //     }

        //     // Connect Node with Name (either Inner or Outer)
        //     boolean success = connectNodeWithName(bigraph, source, target);
        //     if (!success) {
        //         LOGGER.error("❌ Failed to connect Node with Name");
        //         return Optional.empty();
        //     }

        //     LOGGER.info("✅ Updated Bigraph model with Link Graph connection");

        //     // === STEP 2: Create GEdge for visualization ===
        //     LOGGER.info("📝 Step 2: Creating GEdge for visualization");
        //     GEdge gEdge = GraphFactory.eINSTANCE.createGEdge();
        //     gEdge.setId("hyperedge_" + System.currentTimeMillis());
        //     gEdge.setType(BigraphModelTypes.HYPEREDGE);
        //     gEdge.setSourceId(source.getId());
        //     gEdge.setTargetId(target.getId());

        //     // Add CSS styling
        //     gEdge.getCssClasses().add("bigraph-hyper-connection");

        //     // Add metadata
        //     gEdge.getArgs().put("edgeType", "hyper");

        //     LOGGER.info("✅ Successfully created Hyper Edge: {} → {}", source.getId(), target.getId());

        //     return Optional.of(gEdge);

        // } catch (Exception e) {
        //     LOGGER.error("❌ Failed to create Hyper Edge", e);
        //     return Optional.empty();
        // }

        return null;
        
    }

    /**
     * Validates if the connection between source and target is allowed.
     * 
     * ONLY allowed connections (Link Graph semantics):
     * - Node → Inner Name
     * - Node → Outer Name
     * - Inner Name → Node (symmetric)
     * - Outer Name → Node (symmetric)
     * 
     * NOT allowed:
     * - Node → Node (use Place Graph hierarchy instead)
     * - Inner Name → Outer Name (not valid in Bigraph theory)
     * - Inner Name → Inner Name
     * - Outer Name → Outer Name
     */
    // private boolean isValidConnection(final GModelElement source, final GModelElement target) {
    //     String sourceType = source.getType();
    //     String targetType = target.getType();

    //     // Node ↔ Inner Name
    //     if (BigraphModelTypes.BIGRAPH_NODE.equals(sourceType) && 
    //         BigraphModelTypes.INNER_NAME.equals(targetType)) {
    //         return true;
    //     }
    //     if (BigraphModelTypes.INNER_NAME.equals(sourceType) && 
    //         BigraphModelTypes.BIGRAPH_NODE.equals(targetType)) {
    //         return true;
    //     }

    //     // Node ↔ Outer Name
    //     if (BigraphModelTypes.BIGRAPH_NODE.equals(sourceType) && 
    //         BigraphModelTypes.OUTER_NAME.equals(targetType)) {
    //         return true;
    //     }
    //     if (BigraphModelTypes.OUTER_NAME.equals(sourceType) && 
    //         BigraphModelTypes.BIGRAPH_NODE.equals(targetType)) {
    //         return true;
    //     }

    //     // All other combinations are invalid
    //     return false;
    // }

    // /**
    //  * Connects a Node with an Inner or Outer Name.
    //  * 
    //  * This implements the Link Graph semantics:
    //  * - Node Port → Inner Name
    //  * - Node Port → Outer Name
    //  * 
    //  * @return true if connection was successful, false otherwise
    //  */
    // private boolean connectNodeWithName(final PureBigraphMutable bigraph, 
    //         final GModelElement source, final GModelElement target) {
        
    //     // Determine which is the node and which is the name
    //     GModelElement nodeElement = null;
    //     GModelElement nameElement = null;
        
    //     if (BigraphModelTypes.BIGRAPH_NODE.equals(source.getType())) {
    //         nodeElement = source;
    //         nameElement = target;
    //     } else if (BigraphModelTypes.BIGRAPH_NODE.equals(target.getType())) {
    //         nodeElement = target;
    //         nameElement = source;
    //     } else {
    //         LOGGER.error("❌ Neither source nor target is a Node!");
    //         return false;
    //     }
        
    //     // Get the Bigraph Node entity
    //     BigraphEntity.NodeEntity<DynamicControl> node = modelState.getNodeByGNodeId(nodeElement.getId());
    //     if (node == null) {
    //         LOGGER.error("❌ Could not find Bigraph node for GNode ID: {}", nodeElement.getId());
    //         return false;
    //     }
        
    //     // Check if node has ports
    //     int arity = node.getControl().getArity().getValue();
    //     if (arity == 0) {
    //         LOGGER.error("❌ Node '{}' has no ports (arity=0)", nodeElement.getId());
    //         return false;
    //     }
        
    //     // Get the first available port (TODO: allow user to select port)
    //     List<BigraphEntity.Port> ports = bigraph.getPorts(node);
    //     if (ports.isEmpty()) {
    //         LOGGER.error("❌ Node '{}' has no ports available", nodeElement.getId());
    //         return false;
    //     }
    //     BigraphEntity.Port port = ports.get(0);
        
    //     // Connect port to the name (Inner or Outer)
    //     String nameType = nameElement.getType();
        
    //     if (BigraphModelTypes.INNER_NAME.equals(nameType)) {
    //         // Connect to Inner Name
    //         BigraphEntity.InnerName innerName = getInnerNameByGNodeId(bigraph, nameElement.getId());
    //         if (innerName == null) {
    //             LOGGER.error("❌ Could not find Inner Name for ID: {}", nameElement.getId());
    //             return false;
    //         }
    //         // Inner Names are not Links themselves - they connect TO links (Edges)
    //         // Check if Inner Name already has a Link (Edge)
    //         BigraphEntity.Link link = bigraph.getLinkOfPoint(innerName);
    //         if (link == null) {
    //             // No link yet - create a new Edge and connect Inner Name to it
    //             link = bigraph.addEdge();
    //             connectInnerNameToLink(innerName, link);
    //             LOGGER.info("   Created new Edge and connected Inner Name to it");
    //         }
    //         // Connect port to the same link
    //         bigraph.connectPortToLink(port, link);
    //         LOGGER.info("✅ Connected port of node '{}' to Inner Name '{}' via Edge", 
    //             nodeElement.getId(), innerName.getName());
    //         return true;
            
    //     } else if (BigraphModelTypes.OUTER_NAME.equals(nameType)) {
    //         // Connect to Outer Name
    //         BigraphEntity.OuterName outerName = getOuterNameByGNodeId(bigraph, nameElement.getId());
    //         if (outerName == null) {
    //             LOGGER.error("❌ Could not find Outer Name for ID: {}", nameElement.getId());
    //             return false;
    //         }
    //         bigraph.connectPortToLink(port, outerName);
    //         LOGGER.info("✅ Connected port of node '{}' to Outer Name '{}'", 
    //             nodeElement.getId(), outerName.getName());
    //         return true;
            
    //     } else {
    //         LOGGER.error("❌ Invalid name type: {}", nameType);
    //         return false;
    //     }
    // }
    
    // /**
    //  * Finds an Inner Name by its GNode ID.
    //  * 
    //  * GNode IDs have format: "inner_{innerNameId}_{timestamp}"
    //  * We need to extract the innerNameId and find the matching Inner Name.
    //  */
    // private BigraphEntity.InnerName getInnerNameByGNodeId(final PureBigraphMutable bigraph, 
    //         final String gNodeId) {
    //     // GNode ID format: "inner_{innerNameId}_{timestamp}"
    //     // Example: "inner_inner_1760398260335_1760398260337"
    //     // Extract innerNameId by removing "inner_" prefix and "_{timestamp}" suffix
        
    //     if (!gNodeId.startsWith("inner_")) {
    //         LOGGER.error("Invalid Inner Name GNode ID format: {}", gNodeId);
    //         return null;
    //     }
        
    //     // Remove "inner_" prefix
    //     String withoutPrefix = gNodeId.substring("inner_".length());
        
    //     // Find last underscore (before timestamp)
    //     int lastUnderscore = withoutPrefix.lastIndexOf('_');
    //     if (lastUnderscore == -1) {
    //         LOGGER.error("Invalid Inner Name GNode ID format (no timestamp): {}", gNodeId);
    //         return null;
    //     }
        
    //     // Extract the actual inner name (everything before last underscore)
    //     String innerNameId = withoutPrefix.substring(0, lastUnderscore);
        
    //     LOGGER.info("   Extracted Inner Name ID: '{}' from GNode ID: '{}'", innerNameId, gNodeId);
        
    //     // Find Inner Name by name
    //     for (BigraphEntity.InnerName innerName : bigraph.getInnerNames()) {
    //         if (innerNameId.equals(innerName.getName())) {
    //             LOGGER.info("   ✅ Found Inner Name: '{}'", innerName.getName());
    //             return innerName;
    //         }
    //     }
        
    //     LOGGER.warn("   ⚠️ No Inner Name found with name: '{}'", innerNameId);
    //     return null;
    // }
    
    // /**
    //  * Finds an Outer Name by its GNode ID.
    //  * 
    //  * GNode IDs have format: "outer_{outerNameId}_{timestamp}"
    //  * We need to extract the outerNameId and find the matching Outer Name.
    //  */
    // private BigraphEntity.OuterName getOuterNameByGNodeId(final PureBigraphMutable bigraph, 
    //         final String gNodeId) {
    //     // GNode ID format: "outer_{outerNameId}_{timestamp}"
    //     // Example: "outer_outer_1760398260335_1760398260337"
        
    //     if (!gNodeId.startsWith("outer_")) {
    //         LOGGER.error("Invalid Outer Name GNode ID format: {}", gNodeId);
    //         return null;
    //     }
        
    //     // Remove "outer_" prefix
    //     String withoutPrefix = gNodeId.substring("outer_".length());
        
    //     // Find last underscore (before timestamp)
    //     int lastUnderscore = withoutPrefix.lastIndexOf('_');
    //     if (lastUnderscore == -1) {
    //         LOGGER.error("Invalid Outer Name GNode ID format (no timestamp): {}", gNodeId);
    //         return null;
    //     }
        
    //     // Extract the actual outer name (everything before last underscore)
    //     String outerNameId = withoutPrefix.substring(0, lastUnderscore);
        
    //     LOGGER.info("   Extracted Outer Name ID: '{}' from GNode ID: '{}'", outerNameId, gNodeId);
        
    //     // Find Outer Name by name
    //     for (BigraphEntity.OuterName outerName : bigraph.getOuterNames()) {
    //         if (outerNameId.equals(outerName.getName())) {
    //             LOGGER.info("   ✅ Found Outer Name: '{}'", outerName.getName());
    //             return outerName;
    //         }
    //     }
        
    //     LOGGER.warn("   ⚠️ No Outer Name found with name: '{}'", outerNameId);
    //     return null;
    // }
    
    // /**
    //  * Connects an Inner Name to a Link (Edge) using direct EMF manipulation.
    //  * This is equivalent to PureBigraphBuilder.connectInnerToLink().
    //  * 
    //  * This performs a BIDIRECTIONAL connection:
    //  * 1. Inner Name → Link (sets the "link" reference on the Inner Name)
    //  * 2. Link → Inner Name (adds Inner Name to the "points" list of the Link)
    //  */
    // private void connectInnerNameToLink(final BigraphEntity.InnerName innerName, final BigraphEntity.Link link) {
    //     org.eclipse.emf.ecore.EObject innerEObject = innerName.getInstance();
    //     org.eclipse.emf.ecore.EObject linkEObject = link.getInstance();
        
    //     // 1. Set link reference on Inner Name (Inner Name → Link)
    //     org.eclipse.emf.ecore.EStructuralFeature linkReference = 
    //         innerEObject.eClass().getEStructuralFeature(
    //             org.bigraphs.framework.core.BigraphMetaModelConstants.REFERENCE_LINK
    //         );
    //     if (linkReference != null) {
    //         innerEObject.eSet(linkReference, linkEObject);
    //         LOGGER.debug("   Set link reference on Inner Name '{}'", innerName.getName());
    //     }
        
    //     // 2. Add Inner Name to points list of Link (Link → Inner Name)
    //     org.eclipse.emf.ecore.EStructuralFeature pointsFeature = 
    //         linkEObject.eClass().getEStructuralFeature(
    //             org.bigraphs.framework.core.BigraphMetaModelConstants.REFERENCE_POINT
    //         );
    //     if (pointsFeature != null) {
    //         @SuppressWarnings("unchecked")
    //         org.eclipse.emf.common.util.EList<org.eclipse.emf.ecore.EObject> points = 
    //             (org.eclipse.emf.common.util.EList<org.eclipse.emf.ecore.EObject>) linkEObject.eGet(pointsFeature);
    //         if (!points.contains(innerEObject)) {
    //             points.add(innerEObject);
    //             LOGGER.debug("   Added Inner Name '{}' to Edge's points list", innerName.getName());
    //         }
    //     }
    // }
}

