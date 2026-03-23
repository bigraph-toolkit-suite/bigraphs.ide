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
import java.io.FileOutputStream;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.ControlStatus;
import org.bigraphs.framework.core.factory.BigraphFactory;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.impl.signature.DynamicSignatureBuilder;
import org.eclipse.emf.ecore.EObject;
import org.bigraphs.framework.core.BigraphFileModelManagement;
import org.eclipse.glsp.example.bigraph.actions.CreateBigraphControlAction;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.features.core.model.RequestModelAction;
import com.google.inject.Inject;

public class CreateBigraphControlActionHandler extends AbstractActionHandler<CreateBigraphControlAction> {

    private static final Logger LOGGER = LogManager.getLogger(CreateBigraphControlActionHandler.class);

    @Inject
    protected BigraphModelState modelState;

    @Override
    public List<Action> executeAction(CreateBigraphControlAction action) {
        LOGGER.info("🚀 Executing CreateBigraphControlAction: Name={}, Arity={}, Status={}", action.getName(), action.getArity(), action.getStatus());
        
        try {
            String sourcePath = modelState.getSourceFilePath();
            if (sourcePath == null) {
                throw new RuntimeException("No active model loaded. Cannot determine signature file.");
            }

            String signatureXmiPath = sourcePath.replace(".xmi", ".signature.xmi");
            String signatureEcorePath = sourcePath.replace(".xmi", ".signature.ecore");
            File sigFile = new File(signatureXmiPath);

            DynamicSignature signature;
            if (!sigFile.exists()) {
                LOGGER.info("🆕 Signature file not found, creating new one: {}", signatureXmiPath);
                signature = BigraphFactory.pureSignatureBuilder().create();
            } else {
                // Load existing signature
                List<EObject> signatureObjects = BigraphFileModelManagement.Load.signatureInstanceModel(
                    signatureEcorePath,
                    signatureXmiPath
                );
                
                if (signatureObjects.isEmpty()) {
                    throw new RuntimeException("Signature file is empty or invalid");
                }

                EObject signatureEObject = signatureObjects.get(0);
                signature = BigraphFactory.createOrGetSignature(signatureEObject);
            }

            // Create a NEW signature builder based on existing one to add the new control
            DynamicSignatureBuilder builder = BigraphFactory.pureSignatureBuilder();
            
            // Copy existing controls
            signature.getControls().forEach(c -> {
                builder.add(c);
            });


            // Add NEW control
            ControlStatus status = ControlStatus.ATOMIC;
            if (action.getStatus() != null) {
                try {
                    status = ControlStatus.valueOf(action.getStatus().toUpperCase());
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("⚠️ Invalid status '{}', defaulting to ATOMIC", action.getStatus());
                }
            }
            
            builder.add(action.getName(), action.getArity(), status);

            DynamicSignature newSignature = builder.create();

            // Save the new signature
            LOGGER.info("💾 Saving new signature to XMI: {} and Ecore: {}", signatureXmiPath, signatureEcorePath);
            
            // Save Instance Model (XMI)
            BigraphFileModelManagement.Store.exportAsInstanceModel(
                newSignature, 
                new FileOutputStream(signatureXmiPath)
            );
            
            // Save Metamodel (Ecore)
            BigraphFileModelManagement.Store.exportAsMetaModel(
                newSignature, 
                new FileOutputStream(signatureEcorePath)
            );
            
            LOGGER.info("✅ Successfully updated signature files with new control: {}", action.getName());
            
            // Trigger model reload - this reloads the bigraph with the updated signature
            // The client will automatically request context actions (including tool palette) after model load
            Map<String, String> options = new HashMap<>();
            options.put("sourceUri", sourcePath);
            
            LOGGER.info("🔄 Triggering model reload to refresh signature and tool palette");
            return List.of(new RequestModelAction(options));

        } catch (Exception e) {
            LOGGER.error("❌ Failed to create bigraph control", e);
            throw new RuntimeException("Failed to create bigraph control", e);
        }
    }
}
