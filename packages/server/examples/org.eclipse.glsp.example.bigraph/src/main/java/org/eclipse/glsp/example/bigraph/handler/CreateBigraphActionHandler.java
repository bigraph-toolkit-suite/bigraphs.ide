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
import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.actions.CreateBigraphAction;
import org.eclipse.glsp.example.bigraph.extensions.CoreIdeExtension;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionList;
import org.eclipse.glsp.example.bigraph.extensions.IdeExtension;
import org.eclipse.glsp.example.bigraph.extensions.ModelVariantRegistry;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaIO;
import org.eclipse.glsp.example.bigraph.model.BigraphIO;
import org.eclipse.glsp.example.bigraph.model.ModelVariant;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import com.google.inject.Inject;

public class CreateBigraphActionHandler extends AbstractActionHandler<CreateBigraphAction> {

    private static final Logger LOGGER = LogManager.getLogger(CreateBigraphActionHandler.class);

    @Inject
    protected ModelVariantRegistry variantRegistry;

    @Override
    public List<Action> executeAction(CreateBigraphAction action) {
        LOGGER.info("🚀 Executing CreateBigraphAction for path: {} (modelType={})",
                action.getPath(), action.getModelType());

        try {
            File file = toFile(action.getPath());
            String path = file.getAbsolutePath();

            if (file.exists()) {
                LOGGER.warn("⚠️ File already exists: {}", path);
                return List.of();
            }

            String requestedVariantId = action.getModelType();
            Optional<ModelVariant> resolved = resolveVariant(requestedVariantId);
            String effectiveVariantId = resolved.map(ModelVariant::getId)
                    .orElse(CoreIdeExtension.BIGRAPH_VARIANT_ID);

            // Variants like behavior-tree ship with a canonical signature
            // (Seq/Sel/Act/Cond) so the very first node-create succeeds.
            // Plain bigraph files get the historical empty signature.
            DynamicSignature initialSignature = resolveInitialSignature(effectiveVariantId);

            LOGGER.info("🆕 Creating new empty Bigraph at: {} (variant={}, controls={})",
                    path, effectiveVariantId,
                    initialSignature == null ? 0 : initialSignature.getControls().size());
            BigraphIO.createEmptyBigraphFile(file, initialSignature);

            // Persist meta only for non-core variants — keeps the on-disk
            // footprint of plain bigraphs unchanged.
            resolved.filter(v -> !CoreIdeExtension.BIGRAPH_VARIANT_ID.equals(v.getId()))
                    .ifPresent(v -> writeVariantMetaFile(file, v));

            LOGGER.info("✅ Created and saved new Bigraph file");
            return List.of();

        } catch (Exception e) {
            LOGGER.error("❌ Failed to create bigraph file", e);
            throw new RuntimeException("Failed to create bigraph file", e);
        }
    }

    /**
     * Map a client-supplied variant id to a registered {@link ModelVariant}.
     * {@code null} / blank / unknown ids resolve to the built-in
     * {@code "bigraph"} variant; an explicit unknown id is logged and
     * downgraded rather than rejected so a stray client can never block
     * file creation.
     */
    private Optional<ModelVariant> resolveVariant(String requestedVariantId) {
        if (requestedVariantId == null || requestedVariantId.isBlank()) {
            return variantRegistry.findById(CoreIdeExtension.BIGRAPH_VARIANT_ID);
        }
        Optional<ModelVariant> match = variantRegistry.findById(requestedVariantId);
        if (match.isEmpty()) {
            LOGGER.warn("⚠️ Unknown model variant '{}' — falling back to 'bigraph'", requestedVariantId);
            return variantRegistry.findById(CoreIdeExtension.BIGRAPH_VARIANT_ID);
        }
        return match;
    }

    /**
     * Asks every registered extension that owns {@code variantId} for
     * its preferred initial signature. The first non-empty answer wins;
     * extensions in this codebase claim disjoint variants so order is
     * not a concern in practice. Returns {@code null} when no extension
     * cares — the caller then falls back to an empty signature.
     */
    private DynamicSignature resolveInitialSignature(String variantId) {
        if (variantId == null || variantId.isBlank()) {
            return null;
        }
        for (IdeExtension ext : ExtensionList.getInstance().getExtensions()) {
            if (!ext.supportsVariant(variantId)) {
                continue;
            }
            Optional<DynamicSignature> sig = ext.getInitialSignature(variantId);
            if (sig.isPresent()) {
                return sig.get();
            }
        }
        return null;
    }

    private void writeVariantMetaFile(File bigraphFile, ModelVariant variant) {
        String metaFilePath = bigraphFile.getAbsolutePath().replace(".xmi", ".bigraph-meta");
        BigraphMetaInformation meta = new BigraphMetaInformation();
        meta.setModelType(variant.getId());
        try {
            new BigraphMetaIO().save(meta, metaFilePath);
            LOGGER.info("📄 Created '{}' meta file at: {}", variant.getId(), metaFilePath);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to write meta file for variant " + variant.getId(), e);
        }
    }

    private File toFile(final String pathOrUri) {
        if (pathOrUri != null && pathOrUri.startsWith("file:")) {
            return new File(URI.create(pathOrUri));
        }
        return new File(pathOrUri);
    }
}
