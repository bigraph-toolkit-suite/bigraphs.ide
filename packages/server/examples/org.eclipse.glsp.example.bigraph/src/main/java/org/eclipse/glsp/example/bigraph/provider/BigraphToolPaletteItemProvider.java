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

package org.eclipse.glsp.example.bigraph.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.glsp.example.bigraph.extensions.VariantGate;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.server.actions.TriggerNodeCreationAction;
import org.eclipse.glsp.server.actions.TriggerEdgeCreationAction;
import org.eclipse.glsp.server.features.toolpalette.PaletteItem;
import org.eclipse.glsp.server.features.toolpalette.ToolPaletteItemProvider;
import org.eclipse.glsp.server.model.GModelState;

import com.google.inject.Inject;

/**
 * Provides the bigraph variant's tool palette items (Place Graph
 * controls + Link Graph tools).
 *
 * <p>Variant-aware: when the active variant is not the bigraph itself
 * (e.g. the user is viewing a Behavior Tree), this provider returns
 * an empty list. The frontend palette host detects the active variant
 * via {@code SetActiveVariantAction} and mounts the matching
 * variant-specific palette implementation on the client side; the
 * server no longer needs to ship per-extension palette items.</p>
 */
public class BigraphToolPaletteItemProvider implements ToolPaletteItemProvider {

    private static final Logger LOGGER = LogManager.getLogger(BigraphToolPaletteItemProvider.class);

    @Inject
    protected GModelState modelState;

    @Inject
    protected VariantGate variantGate;

    @Override
    public List<PaletteItem> getItems(final Map<String, String> args) {
        String activeVariant = variantGate.activeVariantId();
        LOGGER.info("🎨 Building tool palette for variant '{}'", activeVariant);

        // Other variants build their palette entirely in the webview now —
        // see IClientExtension.createPalette in extensions.ts. The server
        // only provides palette items for the classic bigraph variant.
        if (!variantGate.isBigraphEditingPermitted()) {
            return List.of();
        }

        List<PaletteItem> items = new ArrayList<>();

        List<PaletteItem> nodeItems = createNodePaletteItems();
        items.add(PaletteItem.createPaletteGroup(
            "bigraph-nodes",
            "Place Graph",
            nodeItems,
            "symbol-property",
            "A"
        ));

        List<PaletteItem> linkItems = createLinkPaletteItems();
        items.add(PaletteItem.createPaletteGroup(
            "bigraph-links",
            "Link Graph",
            linkItems,
            "symbol-misc",
            "B"
        ));

        LOGGER.info("✅ Tool palette created with {} node types and {} link types",
            nodeItems.size(), linkItems.size());
        return items;
    }

    /**
     * Creates palette items for all controls from the signature.
     */
    protected List<PaletteItem> createNodePaletteItems() {
        List<PaletteItem> nodeItems = new ArrayList<>();
        
        // Get signature from model state
        if (modelState instanceof BigraphModelState) {
            BigraphModelState bigraphState = (BigraphModelState) modelState;
            
            // Try to get signature directly first (works even if bigraph is not mutable/set yet)
            var mutableBigraph = bigraphState.getMutableBigraph();
            var signature = mutableBigraph != null ? mutableBigraph.getSignature() : null;
        
            
            if (signature != null) {
                LOGGER.info("📋 Found {} controls in signature", signature.getControls().size());
                
                int counter = 0;
                for (var control : signature.getControls()) {
                    String controlName = control.getNamedType().stringValue();
                    int arity = control.getArity().getValue().intValue();
                    
                    // Create palette item for each control
                    String itemId = "bigraph.node." + controlName.toLowerCase();
                    String label = controlName + " (arity: " + arity + ")";
                    
                    // Create node creation action
                    TriggerNodeCreationAction action = new TriggerNodeCreationAction("bigraph:node");
                    
                    // Add metadata for the control
                    action.getArgs().put("controlName", controlName);
                    action.getArgs().put("arity", String.valueOf(arity));
                    action.getArgs().put("status", control.getControlKind().name());
                    
                    PaletteItem item = new PaletteItem(itemId, label, action);
                    nodeItems.add(item);
                    
                    LOGGER.debug("  ➕ Palette item: {} ({})", controlName, arity);
                    counter++;
                }
                
                LOGGER.info("✅ Created {} palette items from controls", counter);
            } else {
                LOGGER.warn("⚠️ No source bigraph available in model state");
            }
        } else {
            LOGGER.warn("⚠️ Model state is not a BigraphModelState");
        }
        
        // Fallback: If no controls found, add a placeholder
        if (nodeItems.isEmpty()) {
            LOGGER.warn("⚠️ No controls found, adding placeholder");
            nodeItems.add(new PaletteItem(
                "bigraph.node.placeholder",
                "No controls loaded",
                new TriggerNodeCreationAction("bigraph:node")
            ));
        }

        // Site tool — always appended last.
        // sortString "~" (ASCII 126) sorts after all uppercase/lowercase letters,
        // so Site always appears at the bottom of the Place Graph palette group
        // regardless of what controls the signature defines.
        nodeItems.add(new PaletteItem(
            "bigraph.node.site",
            "Site",
            new TriggerNodeCreationAction(BigraphModelTypes.SITE),
            null,
            "~"
        ));
        
        return nodeItems;
    }
    
    /**
     * Creates palette items for link graph elements (Edges, Inner Names, Outer Names).
     */
    protected List<PaletteItem> createLinkPaletteItems() {
        List<PaletteItem> linkItems = new ArrayList<>();
        
        LOGGER.info("📋 Creating link graph palette items");
        
        // Edge tool - creates a hyperedge node that can connect multiple points
        linkItems.add(new PaletteItem(
            "bigraph.link.edge",
            "Edge",
            new TriggerNodeCreationAction(BigraphModelTypes.EDGE),
            "fa-link",
            "E"
        ));
        
        // Inner Name tool
        linkItems.add(new PaletteItem(
            "bigraph.link.inner",
            "Inner Name",
            new TriggerNodeCreationAction(BigraphModelTypes.INNER_NAME),
            "fa-sign-in-alt",
            "I"
        ));
        
        // Outer Name tool
        linkItems.add(new PaletteItem(
            "bigraph.link.outer",
            "Outer Name",
            new TriggerNodeCreationAction(BigraphModelTypes.OUTER_NAME),
            "fa-sign-out-alt",
            "O"
        ));
        
        // Connect tool - connects Points (Nodes/InnerNames) to Links (Edges/OuterNames)
        linkItems.add(new PaletteItem(
            "bigraph.link.connect",
            "Connect",
            new TriggerEdgeCreationAction(
                BigraphModelTypes.CONNECTION
            ),
            "fa-plug",
            "C"
        ));
        
        LOGGER.info("✅ Created {} link graph palette items", linkItems.size());
        return linkItems;
    }
}

