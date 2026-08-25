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
 * Handles creation of Edges (hyperedges) in the bigraph.
 * Edges are links in the link graph that can connect multiple points (ports and inner names).
 * 
 * This handler creates the bigraph entity and delegates all GModel/view
 * concerns to the BigraphView.
 */
public class CreateEdgeOperationHandler extends GModelCreateNodeOperationHandler {

    private static final Logger LOGGER = LogManager.getLogger(CreateEdgeOperationHandler.class);

    @Inject
    protected BigraphModelState modelState;
    
    @Inject
    protected ActionDispatcher actionDispatcher;

    public CreateEdgeOperationHandler() {
        super(BigraphModelTypes.EDGE);
    }

    @Override
    protected GNode createNode(final Optional<GPoint> location, final Map<String, String> args) {
        // Not used - we override executeCreation for full control
        return null;
    }

    @Override
    public void executeCreation(final CreateNodeOperation operation) {
        LOGGER.info("🔗 Creating Edge - Bigraph-first approach");

        try {
            // === STEP 1: Create in Bigraph ===
            PureBigraphMutable bigraph = modelState.getMutableBigraph();
            if (bigraph == null) {
                LOGGER.error("❌ No mutable bigraph available");
                BigraphNotifications.notifyError(actionDispatcher, "Cannot create edge: bigraph model is not available.");
                return;
            }

            // Generate a unique name for the edge
            String edgeId = "edge_" + System.currentTimeMillis();
            BigraphEntity.Edge edge;
            try {
                edge = bigraph.addEdge(edgeId);
            } catch (Exception e) {
                LOGGER.error("❌ Failed to create Edge in bigraph", e);
                BigraphNotifications.notifyError(actionDispatcher, "Could not create edge: " + e.getMessage());
                return;
            }
            LOGGER.info("✅ Created Edge in Bigraph: {}", edgeId);

            // === STEP 2: Let the view handle GModel creation ===
            Optional<GPoint> location = operation.getLocation();
            GNode gNode = modelState.getActiveView().onAddEdge(edge, location);
            
            LOGGER.info("✅ Successfully created Edge: {} (GNode: {})", edgeId, gNode.getId());
            
            actionDispatcher.dispatchAfterNextUpdate(SelectAction.setSelection(List.of(gNode.getId())));

        } catch (Exception e) {
            LOGGER.error("❌ Failed to create Edge", e);
            BigraphNotifications.notifyError(actionDispatcher, "Could not create edge: " + e.getMessage());
        }
    }
}

