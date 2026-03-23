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

import java.io.File;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.exceptions.IncompatibleSignatureException;
import org.bigraphs.framework.core.exceptions.operations.IncompatibleInterfaceException;
import org.bigraphs.framework.core.factory.BigraphFactory;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.utils.BigraphUtil;
import org.eclipse.glsp.example.bigraph.actions.ComposeBigraphAction;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.example.bigraph.model.BigraphIO;
import org.eclipse.glsp.example.bigraph.model.BigraphLoadException;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.actions.SetDirtyStateAction;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;

import com.google.inject.Inject;

/**
 * Handles {@link ComposeBigraphAction} dispatched from the VS Code extension.
 *
 * The composition result is written to the model state in-memory only.
 * The merged signature and updated bigraph are persisted to disk
 * only when the user explicitly saves via {@code BigraphXMIModelStorage.saveSourceModel}.
 */
public class ComposeBigraphActionHandler extends AbstractActionHandler<ComposeBigraphAction> {

    private static final Logger LOGGER = LogManager.getLogger(ComposeBigraphActionHandler.class);

    @Inject
    protected BigraphModelState modelState;

    @Inject
    protected ActionDispatcher actionDispatcher;

    @Override
    public List<Action> executeAction(final ComposeBigraphAction action) {
        final String operator = action.getOperator();
        final String sourcePath = action.getSourcePath();

        LOGGER.info("ComposeBigraphAction – operator={}, source={}", operator, sourcePath);

        final PureBigraphMutable targetMutable = modelState.getMutableBigraph();
        if (targetMutable == null) {
            BigraphNotifications.notifyError(actionDispatcher, "No bigraph loaded in the editor.");
            return List.of();
        }

        final PureBigraphMutable sourceMutable;
        try {
            sourceMutable = BigraphIO.parseBigraphFromFile(new File(sourcePath));
        } catch (BigraphLoadException e) {
            BigraphNotifications.notifyError(actionDispatcher, e.getMessage());
            return List.of();
        }

        // Merge signatures — left (target) takes precedence for duplicate control names.
        // Uses mergeSignatures (not composeSignatures) because the latter requires disjoint controls.
        final DynamicSignature mergedSignature = BigraphUtil.mergeSignatures(
            targetMutable.getSignature(), sourceMutable.getSignature());

        final PureBigraph b1 = rebind(targetMutable, mergedSignature);
        final PureBigraph b2 = rebind(sourceMutable, mergedSignature);

        if (b1 == null || b2 == null) {
            BigraphNotifications.notifyError(actionDispatcher, "Failed to rebind bigraphs to merged signature.");
            return List.of();
        }

        final PureBigraph result;
        try {
            if ("parallel".equals(operator)) {
                result = BigraphFactory.ops(b1).juxtapose(b2).getOuterBigraph();
            } else if ("sequential".equals(operator)) {
                // outer ∘ inner: target is outer (must have sites), source is inner.
                // The number of sites in b1 must equal the number of outer names in b2.
                result = BigraphFactory.ops(b1).compose(b2).getOuterBigraph();
            } else {
                BigraphNotifications.notifyError(actionDispatcher, "Unknown composition operator: " + operator);
                return List.of();
            }
        } catch (IncompatibleSignatureException e) {
            BigraphNotifications.notifyError(actionDispatcher,
                "Composition failed — incompatible signatures: " + e.getMessage());
            return List.of();
        } catch (IncompatibleInterfaceException e) {
            if ("sequential".equals(operator)) {
                BigraphNotifications.notifyError(actionDispatcher,
                    "Sequential composition failed: the canvas bigraph must have exactly as many sites "
                    + "as the imported bigraph has outer names. " + e.getMessage());
            } else {
                BigraphNotifications.notifyError(actionDispatcher,
                    "Parallel composition failed: " + e.getMessage());
            }
            return List.of();
        }

        // Convert result back to mutable for model state storage.
        final PureBigraphMutable resultMutable = PureBigraphBuilder
            .create(mergedSignature, result.getMetaModel(), result.getInstanceModel())
            .createMutable();

        // Store merged signature as pending — written to disk only on Ctrl+S.
        modelState.setPendingSignature(mergedSignature);

        // Update canvas in-memory, no disk writes here.
        final GModelRoot newRoot = modelState.initializeBigraphModel(resultMutable, modelState.getMetaInformation());

        // Notify the client that the model has unsaved changes.
        actionDispatcher.dispatch(new SetDirtyStateAction(true, "operation"));

        LOGGER.info("Composition ({}) applied in-memory. Pending save to disk.", operator);
        return List.of(new UpdateModelAction(newRoot, true));
    }

    /**
     * Rebinds a mutable bigraph to a new signature by creating a new builder
     * that reuses the existing EMF meta-model and instance-model resources.
     * Returns null if rebinding fails.
     */
    private PureBigraph rebind(final PureBigraphMutable mutable, final DynamicSignature signature) {
        try {
            return PureBigraphBuilder
                .create(signature, mutable.getMetaModel(), mutable.getInstanceModel())
                .create();
        } catch (Exception e) {
            LOGGER.error("Failed to rebind bigraph to merged signature", e);
            return null;
        }
    }

}
