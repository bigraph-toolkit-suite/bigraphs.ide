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
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.DemoBigraphCreator;
import org.eclipse.glsp.example.bigraph.actions.TestRewriteRuleAction;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;

import com.google.inject.Inject;

/**
 * Handler for testing rewrite rules. Applies the simple Sensor->Device rule
 * to the current bigraph and updates the model.
 */
public class TestRewriteRuleActionHandler extends AbstractActionHandler<TestRewriteRuleAction> {

    private static final Logger LOGGER = LogManager.getLogger(TestRewriteRuleActionHandler.class);

    @Inject
    protected BigraphModelState modelState;

    @Override
    public List<Action> executeAction(TestRewriteRuleAction action) {
        LOGGER.info("🔄 Executing TestRewriteRuleAction - Applying Sensor->Device rewrite rule");
        
        try {
            // Step 1: Get the current bigraph from model state
            PureBigraphMutable mutableBigraph = modelState.getMutableBigraph();
            if (mutableBigraph == null) {
                LOGGER.error("❌ No bigraph available in model state");
                return List.of();
            }
            
            DynamicSignature signature = mutableBigraph.getSignature();
            if (signature == null) {
                LOGGER.error("❌ No signature available in bigraph");
                return List.of();
            }
            
            LOGGER.info("✅ Got bigraph with {} nodes and {} roots", 
                mutableBigraph.getNodes().size(), 
                mutableBigraph.getRoots().size());
            
            // Step 2: Convert PureBigraphMutable to PureBigraph for the rewrite rule function
            // The function expects PureBigraph, so we need to create one from the mutable version
            PureBigraph immutableBigraph = convertToImmutable(mutableBigraph, signature);
            
            // Step 3: Create the redex and reactum for the Sensor->Device rule
            PureBigraph redex = DemoBigraphCreator.createBigraph2(signature);
            PureBigraph reactum = DemoBigraphCreator.createBigraph3(signature);
            
            // Step 4: Apply the rewrite rule
            PureBigraph transformedBigraph = DemoBigraphCreator.applyRewriteRule(
                immutableBigraph, redex, reactum, "sensorToDevice");
            
            if (transformedBigraph == null) {
                LOGGER.warn("⚠️ Rewrite rule did not match or failed");
                return List.of();
            }
            
            LOGGER.info("✅ Rewrite rule applied successfully");
            LOGGER.info("📊 Transformed bigraph BEFORE conversion - Nodes: {}, Roots: {}, Edges: {}, Outer names: {}, Inner names: {}, Sites: {}, Is ground: {}", 
                transformedBigraph.getNodes().size(),
                transformedBigraph.getRoots().size(),
                transformedBigraph.getEdges().size(),
                transformedBigraph.getOuterNames().size(),
                transformedBigraph.getInnerNames().size(),
                transformedBigraph.getSites().size(),
                transformedBigraph.isGround());
            
            
            // Step 5: Convert back to PureBigraphMutable (post-process already applied by framework in buildParametricReaction)
            PureBigraphMutable transformedMutable = convertToMutable(transformedBigraph, signature);
            LOGGER.info("📊 After conversion - Nodes: {}, Roots: {}, Edges: {}, Outer names: {}", 
                transformedMutable.getNodes().size(),
                transformedMutable.getRoots().size(),
                transformedMutable.getEdges().size(),
                transformedMutable.getOuterNames().size());
            
            // Step 6: Initialize the model with the transformed bigraph (rebuilds GModel from scratch)
            GModelRoot newRoot = modelState.initializeBigraphModel(transformedMutable, modelState.getMetaInformation());
            
            LOGGER.info("✅ Model updated with transformed bigraph");
            
            // Step 7: Return UpdateModelAction to refresh the client view
            // This tells the client to update its model with the new GModel
            return List.of(new UpdateModelAction(newRoot, false));
            
        } catch (Exception e) {
            LOGGER.error("❌ Failed to apply rewrite rule", e);
            return List.of();
        }
    }
    
    /**
     * Converts PureBigraphMutable to PureBigraph (immutable) and makes it ground if needed.
     * A ground bigraph has no inner names and no sites, which is required for matching.
     */
    private PureBigraph convertToImmutable(PureBigraphMutable mutable, DynamicSignature signature) {
        // Create a builder from the mutable bigraph's instance model
        PureBigraphBuilder<DynamicSignature> builder = PureBigraphBuilder
            .create(signature, mutable.getMetaModel(), mutable.getInstanceModel());
    
        
        // Create immutable bigraph
        return builder.create();
    }
    
    /**
     * Converts PureBigraph to PureBigraphMutable.
     */
    private PureBigraphMutable convertToMutable(PureBigraph immutable, DynamicSignature signature) {
        // Create a builder from the immutable bigraph's instance model
        PureBigraphBuilder<DynamicSignature> builder = PureBigraphBuilder
            .create(signature, immutable.getMetaModel(), immutable.getInstanceModel());
        
        // Create mutable bigraph
        return builder.createMutable();
    }
    
}

