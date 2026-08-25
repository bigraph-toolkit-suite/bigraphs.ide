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
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.actions.SelectAction;
import org.eclipse.glsp.server.gmodel.GModelCreateNodeOperationHandler;
import org.eclipse.glsp.server.operations.CreateNodeOperation;

import com.google.inject.Inject;

/**
 * Handles creation of Outer Names in the bigraph.
 * Outer Names represent external interfaces in the link graph.
 * 
 * This handler creates the bigraph entity and delegates all GModel/view
 * concerns to the BigraphView.
 */
public class CreateOuterNameOperationHandler extends GModelCreateNodeOperationHandler {

    private static final Logger LOGGER = LogManager.getLogger(CreateOuterNameOperationHandler.class);

    @Inject
    protected BigraphModelState modelState;
    
    @Inject
    protected ActionDispatcher actionDispatcher;

    public CreateOuterNameOperationHandler() {
        super(BigraphModelTypes.OUTER_NAME);
    }

    @Override
    protected GNode createNode(final Optional<GPoint> location, final Map<String, String> args) {
        // Not used - we override executeCreation for full control
        return null;
    }

    @Override
    public void executeCreation(final CreateNodeOperation operation) {
        LOGGER.info("🔗 Creating Outer Name - Bigraph-first approach");

        try {
            // === STEP 1: Create in Bigraph ===
            PureBigraphMutable bigraph = modelState.getMutableBigraph();
            if (bigraph == null) {
                LOGGER.error("❌ No mutable bigraph available");
                BigraphNotifications.notifyError(actionDispatcher, "Cannot create outer name: bigraph model is not available.");
                return;
            }

            // Generate a unique name for the outer name
            String outerNameId = "outer_" + System.currentTimeMillis();
            BigraphEntity.OuterName outerName;
            try {
                outerName = bigraph.addOuterName(outerNameId);
            } catch (Exception e) {
                LOGGER.error("❌ Failed to create Outer Name in bigraph", e);
                BigraphNotifications.notifyError(actionDispatcher, "Could not create outer name: " + e.getMessage());
                return;
            }
            LOGGER.info("✅ Created Outer Name in Bigraph: {}", outerNameId);

            // === STEP 2: Let the view handle GModel creation ===
            Optional<GPoint> location = operation.getLocation();
            GNode gNode = modelState.getActiveView().onAddOuterName(outerName, location);
            
            LOGGER.info("✅ Successfully created Outer Name: {} (GNode: {})", outerNameId, gNode.getId());
            
            actionDispatcher.dispatchAfterNextUpdate(SelectAction.setSelection(List.of(gNode.getId())));

        } catch (Exception e) {
            LOGGER.error("❌ Failed to create Outer Name", e);
            BigraphNotifications.notifyError(actionDispatcher, "Could not create outer name: " + e.getMessage());
        }
    }
}

