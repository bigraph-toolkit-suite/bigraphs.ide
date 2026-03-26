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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.eclipse.glsp.example.bigraph.actions.RenameNodeAction;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.bigraphs.framework.core.BigraphMetaModelConstants;
import org.eclipse.glsp.server.actions.SetDirtyStateAction;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;
import org.eclipse.glsp.server.model.GModelState;

import com.google.inject.Inject;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.BigraphEntity.Edge;
import org.bigraphs.framework.core.impl.BigraphEntity.InnerName;
import org.bigraphs.framework.core.impl.BigraphEntity.OuterName;
import org.bigraphs.framework.core.utils.emf.EMFUtils;
import org.eclipse.glsp.example.bigraph.views.BigraphView;

public class RenameNodeActionHandler extends AbstractActionHandler<RenameNodeAction> {

    private static final Logger LOGGER = LogManager.getLogger(RenameNodeActionHandler.class);

    @Inject
    protected GModelState modelState;

    @Inject
    protected ActionDispatcher actionDispatcher;

    @Override
    public List<Action> executeAction(RenameNodeAction action) {
        LOGGER.info("✏️ Renaming element {} to {}", action.getElementId(), action.getNewName());
        
        if (!(modelState instanceof BigraphModelState)) {
            LOGGER.error("❌ Model state is not valid");
            return List.of();
        }
        
        BigraphModelState bigraphState = (BigraphModelState) modelState;
        BigraphView view = bigraphState.getActiveView();
        
        // Look up the node directly from the view registry
        BigraphEntity<?> entity = view.getBigraphEntityForGModelId(action.getElementId())
                .orElse(null);

        try {
            if (entity == null) {
                LOGGER.error("❌ Could not resolve element for ID {}", action.getElementId());
                return List.of();
            }

            // Update the bigraph model first
            if (entity instanceof NodeEntity) {
                @SuppressWarnings("unchecked")
                final NodeEntity<DynamicControl> node = (NodeEntity<DynamicControl>) entity;
                node.setName(action.getNewName());
            } else if (entity instanceof InnerName || entity instanceof OuterName || entity instanceof Edge) {
                renameLinkEntity(entity, action.getNewName());
            } else {
                LOGGER.error("❌ Rename not supported for entity type: {}", entity.getClass().getSimpleName());
                return List.of();
            }

            // Update the graphical model only after bigraph mutation succeeded
            view.onRename(action.getElementId(), action.getNewName());
        } catch (Exception e) {
            LOGGER.error("❌ Failed to rename element: {} → {}", action.getElementId(), action.getNewName(), e);
            BigraphNotifications.notifyError(actionDispatcher,
                    "Could not rename element to '" + action.getNewName() + "': " + e.getMessage());
            return List.of();
        }

        LOGGER.info("✅ Renamed node: {} to {}", action.getElementId(), action.getNewName());

        // Proper GLSP way: bump root revision so the client re-renders.
        // Avoid a full GModel re-initialization for a simple rename.
        final var root = bigraphState.getRoot();
        root.setRevision(root.getRevision() + 1);

        // Mark the model as dirty so "save" writes the updated bigraph + meta files.
        // (Without this, the VSCode integration may skip triggering the server-side SaveModelAction.)
        actionDispatcher.dispatch(new SetDirtyStateAction(true, "operation"));

        return List.of(new UpdateModelAction(root, false));
    }

    /**
     * Renames link-type entities by mutating their EMF "name" attribute.
     * (Unlike {@link NodeEntity} they don't expose a setName API.)
     */
    private void renameLinkEntity(final BigraphEntity<?> entity, final String newName) {
        final var nameAttr = EMFUtils.findAttribute(entity.getInstance().eClass(), BigraphMetaModelConstants.ATTRIBUTE_NAME);
        if (nameAttr == null) {
            throw new IllegalStateException(
                    "Missing EMF attribute '" + BigraphMetaModelConstants.ATTRIBUTE_NAME + "' on " + entity.getInstance().eClass().getName());
        }
        entity.getInstance().eSet(nameAttr, newName);

        // Note: we intentionally do not mutate getAttributes() maps here because those maps are derived
        // from EMF lists and may not be backed by the original instance model.
    }
}
