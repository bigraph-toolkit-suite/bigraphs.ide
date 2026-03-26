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

import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphNodeIdentity;
import org.eclipse.glsp.graph.GDimension;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.server.gmodel.GModelChangeBoundsOperationHandler;
import org.eclipse.glsp.server.operations.ChangeBoundsOperation;
import org.eclipse.glsp.server.types.GLSPServerException;

import com.google.inject.Inject;

/**
 * Handles node move operations and synchronizes the position to the Bigraph model immediately.
 */
public class BigraphChangeBoundsOperationHandler extends GModelChangeBoundsOperationHandler {

    private static final Logger LOGGER = LogManager.getLogger(BigraphChangeBoundsOperationHandler.class);

    @Inject
    protected BigraphModelState modelState;

    @Override
    protected void changeElementBounds(final String elementId, final GPoint newPosition, final GDimension newSize) {
        try {
            super.changeElementBounds(elementId, newPosition, newSize);
        } catch (GLSPServerException e) {
            if (e.getMessage() != null && e.getMessage().contains("not found")) {
                LOGGER.debug("Node with id {} not found (likely after transformation), ignoring bounds change", elementId);
                return;
            }
            throw e;
        }

        if (newPosition == null) {
            return;
        }

        BigraphMetaInformation meta = modelState.getMetaInformation();
        if (meta == null) {
            return;
        }

        BigraphEntity<?> entity = modelState.getActiveView().getBigraphEntityForGModelId(elementId)
                .orElse(null);
        if (entity == null) {
            return;
        }

        if (entity instanceof BigraphEntity.NodeEntity) {
            // Position persistence must use property-based keys (node name + control type),
            // since glspNodeId is volatile between editor opens.
            @SuppressWarnings("unchecked")
            final BigraphEntity.NodeEntity<?> node = (BigraphEntity.NodeEntity<?>) entity;
            final String nodeName = node.getName();
            final var control = node.getControl();
            final String controlName = control != null && control.getNamedType() != null
                ? control.getNamedType().stringValue()
                : "";
            final String nodePositionKey = BigraphNodeIdentity.toNodePositionKey(nodeName, controlName);

            // Optional sanity check: if a stable id exists, ensure the view elementId is consistent.
            // Do NOT fail if missing — it is not required for position persistence.
            BigraphNodeIdentity.getStableId(node).ifPresent(nodeStableId -> {
                if (!BigraphNodeIdentity.toGModelId(nodeStableId).equals(elementId)) {
                    LOGGER.debug(
                        "NodeEntity stableId does not match elementId. elementId={}, stableId={}",
                        elementId, nodeStableId);
                }
            });

            Optional<String> gModelId = modelState.getActiveView().getGModelIdForEntity(entity);
            // Nodes nested inside a site have relative positions — store separately so that
            // a site-move does not corrupt the position on the next rebuild.
            if (gModelId.isPresent() && modelState.getActiveView().isNodeNestedInSite(gModelId.get())) {
                meta.getNodeRelativePositions().put(nodePositionKey, newPosition);
                // Re-fit the site container around all its nodes after the move
                modelState.getActiveView().refreshSiteContainingNode(gModelId.get());
            } else {
                meta.getNodePositions().put(nodePositionKey, newPosition);
            }
        } else if (entity instanceof BigraphEntity.SiteEntity) {
            String key = String.valueOf(((BigraphEntity.SiteEntity) entity).getIndex());
            meta.getSitePositions().put(key, newPosition);
        } else if (entity instanceof BigraphEntity.RootEntity) {
            String key = String.valueOf(((BigraphEntity.RootEntity) entity).getIndex());
            meta.getRootPositions().put(key, newPosition);
        } else if (entity instanceof BigraphEntity.Edge) {
            meta.getEdgePositions().put(((BigraphEntity.Edge) entity).getName(), newPosition);
        } else if (entity instanceof BigraphEntity.OuterName) {
            meta.getOuterNamePositions().put(((BigraphEntity.OuterName) entity).getName(), newPosition);
        } else if (entity instanceof BigraphEntity.InnerName) {
            meta.getInnerNamePositions().put(((BigraphEntity.InnerName) entity).getName(), newPosition);
        }
    }
}
