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
import org.eclipse.glsp.example.bigraph.actions.RequestVariantSwitchAction;
import org.eclipse.glsp.example.bigraph.actions.SetActiveVariantAction;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;

import com.google.inject.Inject;

/**
 * Switches the active variant for the current diagram session.
 *
 * <p>Fired by the in-canvas variant tab bar (Bigraph ↔ BT). Hot-standby
 * design: every variant has its own persistent GModel container that
 * is kept in sync with the underlying domain model continuously — so
 * a switch is a cheap pointer swap, no rebuild involved.</p>
 *
 * <ol>
 *   <li>{@link BigraphModelState#swapActiveVariantTo(String)} validates
 *       the target id and rebinds the GLSP-visible root to that
 *       variant's container.</li>
 *   <li>The handler bumps the revision and emits an
 *       {@link UpdateModelAction} so the client re-renders.</li>
 *   <li>A fresh {@link SetActiveVariantAction} brings the palette and
 *       the in-canvas tab bar in line with the new state.</li>
 * </ol>
 *
 * <p>The persisted {@code modelType} in {@code .bigraph-meta} is not
 * touched — re-opening the file always restores the original view.</p>
 */
public class RequestVariantSwitchActionHandler extends AbstractActionHandler<RequestVariantSwitchAction> {

    private static final Logger LOGGER = LogManager.getLogger(RequestVariantSwitchActionHandler.class);

    @Inject
    protected BigraphModelState modelState;

    @Override
    public List<Action> executeAction(final RequestVariantSwitchAction action) {
        final String targetVariantId = action.getVariantId();
        if (targetVariantId == null || targetVariantId.isBlank()) {
            LOGGER.warn("Ignoring variant switch with empty target id.");
            return List.of();
        }

        final String previousVariantId = modelState.getActiveVariantId();
        if (!modelState.swapActiveVariantTo(targetVariantId)) {
            LOGGER.debug("Variant switch to '{}' is a no-op (already active, "
                    + "not in available set, or no container provisioned).", targetVariantId);
            return List.of();
        }
        LOGGER.info("Switched variant: {} → {}", previousVariantId, targetVariantId);

        final GModelRoot root = modelState.getRoot();
        if (root != null) {
            root.setRevision(root.getRevision() + 1);
        }
        return List.of(
                new UpdateModelAction(root, false),
                new SetActiveVariantAction(targetVariantId, modelState.getAvailableVariantIds()));
    }
}
