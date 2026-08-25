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

package org.eclipse.glsp.example.bigraph.model;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.server.actions.SaveModelAction;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.features.core.model.RequestModelAction;
import org.eclipse.glsp.server.gmodel.GModelStorage;
import org.eclipse.glsp.server.model.GModelState;
import org.eclipse.glsp.example.bigraph.BigraphDebugPrinter;
import org.eclipse.glsp.example.bigraph.actions.SetActiveVariantAction;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionList;
import org.eclipse.glsp.example.bigraph.extensions.IdeExtension;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaIO;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;

import com.google.inject.Inject;
import com.google.inject.Injector;

/**
 * Custom model storage that handles XMI files containing Bigraph models.
 * Extends GModelStorage to leverage GLSP infrastructure while
 * adding Bigraph Framework specific loading capabilities.
 */
public class BigraphXMIModelStorage extends GModelStorage {

    private static final Logger LOGGER = LogManager.getLogger(BigraphXMIModelStorage.class);

    @Inject
    protected BigraphModelState modelState;

    @Inject
    protected ActionDispatcher actionDispatcher;

    @Inject
    protected Injector injector;

    @Override
    public void loadSourceModel(final RequestModelAction action) {
        final File file = convertToFile(action.getOptions());
        loadSourceModel(file, modelState).ifPresent(root -> {
            modelState.updateRoot(root);
            root.setRevision(-1);
            // Give extensions a chance to reconstruct their private domain
            // models (e.g. BehaviorTree) from the loaded shared bigraph,
            // then tell the client which variant is now active so it can
            // swap in the matching palette / view set.
            notifyExtensionsModelLoaded();
            broadcastActiveVariant();
        });
    }

    /**
     * Invokes {@link IdeExtension#onModelLoaded} on every registered
     * extension whose declared variant ids include the diagram's current
     * {@code activeVariantId}. Extensions that don't own the active
     * variant are skipped — they have no state to hydrate for this file.
     */
    protected void notifyExtensionsModelLoaded() {
        final String activeVariantId = modelState.getActiveVariantId();
        if (activeVariantId == null) {
            return;
        }
        for (IdeExtension extension : ExtensionList.getInstance().getExtensions()) {
            if (!extension.supportsVariant(activeVariantId)) {
                continue;
            }
            try {
                extension.onModelLoaded(modelState, injector);
            } catch (RuntimeException ex) {
                LOGGER.error("Extension {} failed to hydrate on model load",
                    extension.getId(), ex);
            }
        }
    }

    /**
     * Pushes the current {@code activeVariantId} together with the list
     * of variants this file can be viewed as to the client so its
     * palette host and tab-bar can stay in sync with the server's
     * notion of "what kind of model is this".
     */
    protected void broadcastActiveVariant() {
        final String activeVariantId = modelState.getActiveVariantId();
        if (activeVariantId == null) {
            return;
        }
        LOGGER.info("📣 Broadcasting active variant to client: {} (available: {})",
                activeVariantId, modelState.getAvailableVariantIds());
        actionDispatcher.dispatch(
                new SetActiveVariantAction(activeVariantId, modelState.getAvailableVariantIds()));
    }

    @Override
    protected Optional<GModelRoot> loadSourceModel(final File file, final GModelState mS) {
        //TODO: Maybe send path to model state
        try {
            PureBigraphMutable bigraphModel = BigraphIO.parseBigraphFromFile(file);
            ensureCompanionSignatureFiles(file, bigraphModel);
            BigraphDebugPrinter.print(bigraphModel);

            BigraphMetaInformation meta = parseMetaInformationFromFile(file);

            modelState.setSourceFilePath(file.getAbsolutePath());
            GModelRoot gModelRoot = modelState.initializeBigraphModel(bigraphModel, meta);
            ExtensionList.getInstance().readExtensionMeta(modelState.getMetaInformation(), injector);
            return Optional.of(gModelRoot);
        } catch (BigraphLoadException e) {
            LOGGER.error("❌ Failed to load bigraph model from {}", file.getAbsolutePath(), e);
            BigraphNotifications.notifyError(actionDispatcher, e.getMessage());
            modelState.setSourceFilePath(null);
            modelState.setPendingSignature(null);
            GModelRoot fallbackRoot = modelState.initializeBigraphModel(
                BigraphIO.createEmptyBigraph(),
                new BigraphMetaInformation());
            return Optional.of(fallbackRoot);
        }
    }

    private void ensureCompanionSignatureFiles(final File file, final PureBigraphMutable bigraphModel) {
        if (!BigraphIO.hasNoCompanionSignatureFiles(file)) {
            return;
        }
        try {
            LOGGER.info("Materializing missing companion signature files for {}", file.getAbsolutePath());
            BigraphIO.writeSignatureToFile(bigraphModel.getSignature(), file.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warn("⚠️ Failed to materialize companion signature files for {}", file.getAbsolutePath(), e);
        }
    }
    

    private BigraphMetaInformation parseMetaInformationFromFile(File bigraphFile) {
        
        String metaFilePath = bigraphFile.getAbsolutePath().replace(".xmi", ".bigraph-meta");
        LOGGER.info("Loading meta-information from: {}", metaFilePath);
        File metaFile = new File(metaFilePath);
        if (metaFile.exists()) {
            try {
                return new BigraphMetaIO().load(metaFilePath);
            } catch (Exception e) {
                LOGGER.error("❌ Failed to load meta-information from: {}. Returning empty meta.", metaFilePath, e);
            }
        }
        return new BigraphMetaInformation();
    }




    @Override
    public void saveSourceModel(final SaveModelAction action) {
        File targetFile = convertToFile(action);

        try {
            PureBigraphMutable currentBigraph = modelState.getMutableBigraph();
            if (currentBigraph == null) {
                return;
            }

            BigraphIO.writeToFile(currentBigraph, targetFile);

            // If a composition was performed since the last save, persist the merged signature.
            if (modelState.hasPendingSignature()) {
                DynamicSignature pendingSignature = modelState.getPendingSignature();
                LOGGER.info("💾 Persisting merged signature for composed bigraph: {}", targetFile.getAbsolutePath());
                BigraphIO.writeSignatureToFile(pendingSignature, targetFile.getAbsolutePath());
                modelState.setPendingSignature(null); // clear after successful write
            }

            // Save meta-information (extension sections merged in first).
            BigraphMetaInformation metaInfo = modelState.getMetaInformation();
            if (metaInfo != null) {
                ExtensionList.getInstance().writeExtensionMeta(metaInfo, injector);
                String metaFilePath = targetFile.getAbsolutePath().replace(".xmi", ".bigraph-meta");
                LOGGER.info("Saving meta-information to: {}", metaFilePath);
                new BigraphMetaIO().save(metaInfo, metaFilePath);
            }

        } catch (IOException e) {
            LOGGER.error("❌ Failed to save Bigraph model", e);
            throw new RuntimeException("Failed to save Bigraph model", e);
        }
    }
}

