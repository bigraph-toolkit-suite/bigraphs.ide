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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.RootEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.SiteEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.example.bigraph.model.BigraphNodeIdentity;
import org.eclipse.glsp.example.bigraph.views.BigraphView;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.actions.SelectAction;
import org.eclipse.glsp.server.gmodel.GModelCreateNodeOperationHandler;
import org.eclipse.glsp.server.operations.CreateNodeOperation;

import com.google.inject.Inject;

/**
 * Handles the creation of bigraph nodes from the tool palette.
 * Creates nodes based on the control definition from the signature.
 * 
 * When a user clicks:
 * - On an existing node: creates a child node under that parent
 * - On empty canvas: creates a new root with the node as a child
 */
public class CreateBigraphNodeOperationHandler extends GModelCreateNodeOperationHandler {

    private static final Logger LOGGER = LogManager.getLogger(CreateBigraphNodeOperationHandler.class);

    @Inject
    protected BigraphModelState modelState;
    
    @Inject
    protected ActionDispatcher actionDispatcher;

    public CreateBigraphNodeOperationHandler() {
        super(BigraphModelTypes.BIGRAPH_NODE);
    }

    @Override
    protected GNode createNode(final Optional<GPoint> location, final Map<String, String> args) {
        // Not used - we override executeCreation instead for full control
        return null;
    }

    @Override
    public void executeCreation(final CreateNodeOperation operation) {
        LOGGER.info("🔨 Creating bigraph node - Bigraph-first approach");

        try {
            // === STEP 1: Get click location ===
            Optional<GPoint> locationOpt = operation.getLocation();
            if (!locationOpt.isPresent()) {
                LOGGER.error("❌ No location provided for node creation");
                return;
            }
            GPoint location = locationOpt.get();
            double clickX = location.getX();
            double clickY = location.getY();
            LOGGER.info("📍 Click location: ({}, {})", clickX, clickY);

            // === STEP 2: Get control name from args ===
            Map<String, String> args = operation.getArgs();
            String controlName = args.get("controlName");
            if (controlName == null || controlName.isEmpty()) {
                LOGGER.error("❌ No controlName provided in args");
                return;
            }
            LOGGER.info("🏷️ Control name: {}", controlName);

            // === STEP 3: Get bigraph model and signature ===
            PureBigraphMutable bigraph = modelState.getMutableBigraph();
            if (bigraph == null) {
                LOGGER.error("❌ No mutable bigraph available");
                BigraphNotifications.notifyError(actionDispatcher, "Cannot create node: bigraph model is not available.");
                return;
            }

            DynamicSignature signature = bigraph.getSignature();
            if (signature == null) {
                LOGGER.error("❌ No signature available");
                return;
            }

            // === STEP 4: Find the control in the signature ===
            DynamicControl control = null;
            for (DynamicControl c : signature.getControls()) {
                if (c.getNamedType().stringValue().equals(controlName)) {
                    control = c;
                    break;
                }
            }
            if (control == null) {
                LOGGER.error("❌ Control '{}' not found in signature", controlName);
                return;
            }
            LOGGER.info("✅ Found control: {} (arity: {})", controlName, control.getArity().getValue());

            // === STEP 5: Find deepest node at click position ===
            BigraphView view = modelState.getActiveView();
            NodeEntity<DynamicControl> parentNode = view.findDeepestNodeOnPosition(clickX, clickY);
            
            // === STEP 6: Create the node in the bigraph model ===
            NodeEntity<DynamicControl> newNode;
            BigraphEntity<?> parentEntity;
            SiteEntity parentSite = null;

            if (parentNode != null) {
                // Clicked on an existing node - add as child
                LOGGER.info("👨‍👧 Creating node as child of: {}", parentNode.getName());
                newNode = bigraph.addNode(parentNode, control, null);
                parentEntity = parentNode;
            } else {
                // Check if clicked on a site first
                SiteEntity site = view.findSiteOnPosition(clickX, clickY);

                if (site != null) {
                    // Clicked on a site - add node inside the site
                    LOGGER.info("📦 Creating node inside site: {}", site.getIndex());
                    newNode = bigraph.addNode(site, control, null);
                    parentEntity = site;
                    parentSite = site;
                } else {
                    // No node found - check if clicked on an existing root
                    RootEntity existingRoot = view.findRootOnPosition(clickX, clickY);

                    if (existingRoot != null) {
                        // Clicked on an existing root - add node to that root
                        LOGGER.info("🎯 Adding node to existing root: {}", existingRoot);
                        newNode = bigraph.addNode(existingRoot, control, null);
                        parentEntity = existingRoot;
                    } else {
                        // Clicked on empty canvas - create new root and add node to it
                        LOGGER.info("🌱 Creating new root and adding node to it");
                        RootEntity newRoot = bigraph.addRoot();
                        newNode = bigraph.addNode(newRoot, control, null);
                        parentEntity = newRoot;

                        // Also add the root to the view
                        view.onAddRoot(newRoot);
                    }
                }
            }
            
            // Set node name: base = control name, then controlName1, controlName2, ... among existing nodes
            String nodeName = allocateUniqueNodeName(bigraph, controlName);
            newNode.setName(nodeName);
            BigraphNodeIdentity.getOrCreateStableId(newNode);
            
            // Store layout position — but NOT when placed inside a site or when the parent
            // node is itself inside a site (nested subtree). In those cases the view computes
            // relative coordinates; storing absolute canvas coords here would corrupt them.
            boolean parentNodeIsInSite = parentNode != null
                && view.getGModelIdForEntity(parentNode).map(view::isNodeNestedInSite).orElse(false);
            if (parentSite == null && !parentNodeIsInSite) {
                newNode.getAttributes().put("layout.x", clickX);
                newNode.getAttributes().put("layout.y", clickY);
            }
            
            LOGGER.info("✅ Created bigraph node: {} (name: {})", newNode, nodeName);

            // === STEP 7: Update the view ===
            GNode gNode = view.onAddNode(newNode, parentEntity, control);
            LOGGER.info("✅ Node added to view: {}", gNode.getId());


            // Select only the new node so a later drag does not also move the clicked parent.
            actionDispatcher.dispatchAfterNextUpdate(SelectAction.setSelection(List.of(gNode.getId())));

        } catch (Exception e) {
            LOGGER.error("❌ Failed to create bigraph node", e);
            BigraphNotifications.notifyError(actionDispatcher,
                    "Could not create node: " + e.getMessage());
        }
    }

    /**
     * Picks a short display name: {@code controlName}, or {@code controlName1}, {@code controlName2}, …
     * if that string is already used by any node. Names are compared against all current nodes.
     */
    static String allocateUniqueNodeName(final PureBigraphMutable bigraph, final String controlName) {
        final Set<String> used = new HashSet<>();
        for (NodeEntity<?> n : bigraph.getNodes()) {
            if (n.getName() != null && !n.getName().isEmpty()) {
                used.add(n.getName());
            }
        }
        if (!used.contains(controlName)) {
            return controlName;
        }
        int i = 1;
        while (used.contains(controlName + i)) {
            i++;
        }
        return controlName + i;
    }
}
