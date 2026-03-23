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
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.RootEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.SiteEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.example.bigraph.views.BigraphView;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.actions.SelectAction;
import org.eclipse.glsp.server.gmodel.GModelCreateNodeOperationHandler;
import org.eclipse.glsp.server.operations.CreateNodeOperation;

import com.google.inject.Inject;

/**
 * Handles the creation of bigraph sites from the tool palette.
 *
 * <p>Sites are place-graph placeholders (holes). They must always be placed under a parent
 * (a Root or a Node). The handler resolves the parent from the click position:</p>
 * <ul>
 *   <li>Click on an existing Node → site is created as child of that node</li>
 *   <li>Click on an existing Root → site is created as child of that root</li>
 *   <li>Click on empty canvas → no parent found, operation is rejected with a warning</li>
 * </ul>
 */
public class CreateBigraphSiteOperationHandler extends GModelCreateNodeOperationHandler {

    private static final Logger LOGGER = LogManager.getLogger(CreateBigraphSiteOperationHandler.class);

    @Inject
    protected BigraphModelState modelState;

    @Inject
    protected ActionDispatcher actionDispatcher;

    public CreateBigraphSiteOperationHandler() {
        super(BigraphModelTypes.SITE);
    }

    @Override
    protected GNode createNode(final Optional<GPoint> location, final Map<String, String> args) {
        // Not used — we override executeCreation for full control
        return null;
    }

    @Override
    public void executeCreation(final CreateNodeOperation operation) {
        LOGGER.info("⬜ Creating bigraph site - Bigraph-first approach");

        try {
            // === STEP 1: Get click location ===
            Optional<GPoint> locationOpt = operation.getLocation();
            if (!locationOpt.isPresent()) {
                LOGGER.error("❌ No location provided for site creation");
                return;
            }
            GPoint location = locationOpt.get();
            double clickX = location.getX();
            double clickY = location.getY();
            LOGGER.info("📍 Click location: ({}, {})", clickX, clickY);

            // === STEP 2: Get bigraph model ===
            PureBigraphMutable bigraph = modelState.getMutableBigraph();
            if (bigraph == null) {
                LOGGER.error("❌ No mutable bigraph available");
                BigraphNotifications.notifyError(actionDispatcher, "Cannot create site: bigraph model is not available.");
                return;
            }

            // === STEP 3: Resolve parent from click position ===
            BigraphView view = modelState.getActiveView();
            BigraphEntity<?> parent = null;

            // Try node first (more specific), then root
            NodeEntity<DynamicControl> parentNode = view.findDeepestNodeOnPosition(clickX, clickY);
            if (parentNode != null) {
                parent = parentNode;
                LOGGER.info("👨‍👧 Site parent resolved to node: {}", parentNode.getName());
            } else {
                RootEntity parentRoot = view.findRootOnPosition(clickX, clickY);
                if (parentRoot != null) {
                    parent = parentRoot;
                    LOGGER.info("🎯 Site parent resolved to root: {}", parentRoot.getIndex());
                }
            }

            if (parent == null) {
                LOGGER.warn("⚠️ Sites must be placed under a Node or Root. Click on an existing node or root.");
                BigraphNotifications.notifyWarning(actionDispatcher,
                        "Sites must be placed inside a Node or Root — click on an existing node or root.");
                return;
            }

            // === STEP 4: Create the site in the bigraph model ===
            SiteEntity newSite = bigraph.addSite(parent);
            LOGGER.info("✅ Created bigraph site: index={}", newSite.getIndex());

            // === STEP 5: Determine sibling index for layout ===
            // Count how many sites already exist under the same parent
            int siblingIndex = 0;
            for (BigraphEntity<?> child : bigraph.getChildrenOf(parent)) {
                if (child instanceof SiteEntity && child != newSite) {
                    siblingIndex++;
                }
            }

            // === STEP 6: Update the view ===
            GNode gNode = view.onAddSite(newSite, parent, siblingIndex);
            LOGGER.info("✅ Site added to view: {}", gNode.getId());

            // === STEP 7: Select the new site ===
            actionDispatcher.dispatchAfterNextUpdate(SelectAction.addSelection(List.of(gNode.getId())));

        } catch (Exception e) {
            LOGGER.error("❌ Failed to create bigraph site", e);
            BigraphNotifications.notifyError(actionDispatcher,
                    "Could not create site: " + e.getMessage());
        }
    }
}
