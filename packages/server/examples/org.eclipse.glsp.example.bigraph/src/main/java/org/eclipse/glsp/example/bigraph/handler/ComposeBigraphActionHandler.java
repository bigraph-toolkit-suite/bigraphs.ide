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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.BigraphFileModelManagement;
import org.bigraphs.framework.core.Control;
import org.bigraphs.framework.core.exceptions.IncompatibleSignatureException;
import org.bigraphs.framework.core.exceptions.operations.IncompatibleInterfaceException;
import org.bigraphs.framework.core.factory.BigraphFactory;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.utils.BigraphUtil;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
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

        final DynamicSignature mergedSignature = BigraphUtil.mergeSignatures(
            targetMutable.getSignature(), sourceMutable.getSignature());

        // Both bigraphs must share the same EPackage for juxtapose/compose to work.
        // Each bigraph was loaded with its own EPackage (one has Room+Lamp, the other
        // Room+Thermostat). The framework's node mapping uses EClass identity, so
        // cross-package nodes get silently dropped. Fix: create ONE unified EPackage
        // with all controls, then re-load both instance models against it.
        final EPackage unifiedMetaModel;
        try {
            unifiedMetaModel = createUnifiedMetaModel(mergedSignature);
        } catch (IOException e) {
            BigraphNotifications.notifyError(actionDispatcher,
                "Failed to create unified meta-model: " + e.getMessage());
            return List.of();
        }

        final PureBigraph b1;
        final PureBigraph b2;
        try {
            b1 = reloadWithUnifiedMetaModel(targetMutable, mergedSignature, unifiedMetaModel);
            b2 = reloadWithUnifiedMetaModel(sourceMutable, mergedSignature, unifiedMetaModel);
        } catch (IOException e) {
            BigraphNotifications.notifyError(actionDispatcher,
                "Failed to rebind bigraphs to unified meta-model: " + e.getMessage());
            return List.of();
        }

        final PureBigraph result;
        try {
            if ("parallel".equals(operator)) {
                result = BigraphFactory.ops(b1).juxtapose(b2).getOuterBigraph();
            } else if ("sequential".equals(operator)) {
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

        final PureBigraphMutable resultMutable = PureBigraphBuilder
            .create(mergedSignature, result.getMetaModel(), result.getInstanceModel())
            .createMutable();

        modelState.setPendingSignature(mergedSignature);

        final GModelRoot newRoot = modelState.initializeBigraphModel(resultMutable, modelState.getMetaInformation());

        actionDispatcher.dispatch(new SetDirtyStateAction(true, "operation"));

        LOGGER.info("Composition ({}) applied in-memory. Pending save to disk.", operator);
        return List.of(new UpdateModelAction(newRoot, true));
    }

    /**
     * Creates a single EPackage that extends the base bigraph meta-model with
     * EClasses for every control in the merged signature. Both operand bigraphs
     * are then re-loaded against this package so that all node EClasses share
     * the same EPackage — a requirement for the framework's composition operators.
     */
    private EPackage createUnifiedMetaModel(final DynamicSignature mergedSignature) throws IOException {
        EPackage metaModel = BigraphFileModelManagement.Load.internalBigraphMetaMetaModel();
        EClass bNodeClass = (EClass) metaModel.getEClassifier("BNode");
        if (bNodeClass == null) {
            throw new IOException("BNode class not found in base bigraph meta-model");
        }

        Set<String> added = new HashSet<>();
        for (Control<?, ?> control : mergedSignature.getControls()) {
            String name = control.getNamedType().stringValue();
            if (added.add(name)) {
                EClass controlClass = EcoreFactory.eINSTANCE.createEClass();
                controlClass.setName(name);
                controlClass.getESuperTypes().add(bNodeClass);
                metaModel.getEClassifiers().add(controlClass);
            }
        }

        return metaModel;
    }

    /**
     * Serializes a bigraph's instance model to XMI bytes, then re-loads it
     * against the unified meta-model so that all EObjects are typed against
     * EClasses from one shared EPackage.
     */
    private PureBigraph reloadWithUnifiedMetaModel(
            final PureBigraphMutable mutable,
            final DynamicSignature signature,
            final EPackage unifiedMetaModel) throws IOException {

        ByteArrayOutputStream xmiBytes = new ByteArrayOutputStream();
        BigraphFileModelManagement.Store.exportAsInstanceModel(mutable, xmiBytes);

        List<EObject> reloaded = BigraphFileModelManagement.Load
            .bigraphInstanceModel(unifiedMetaModel, new ByteArrayInputStream(xmiBytes.toByteArray()));

        return PureBigraphBuilder
            .create(signature, unifiedMetaModel, reloaded.get(0))
            .create();
    }

}
