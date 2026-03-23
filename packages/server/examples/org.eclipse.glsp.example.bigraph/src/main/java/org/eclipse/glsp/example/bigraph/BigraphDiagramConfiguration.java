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

package org.eclipse.glsp.example.bigraph;

import static org.eclipse.glsp.example.bigraph.model.BigraphModelTypes.*;
import static org.eclipse.glsp.graph.DefaultTypes.EDGE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.glsp.graph.DefaultTypes;
import org.eclipse.glsp.graph.GraphPackage;
import org.eclipse.glsp.server.diagram.BaseDiagramConfiguration;
import org.eclipse.glsp.server.layout.ServerLayoutKind;
import org.eclipse.glsp.server.types.EdgeTypeHint;
import org.eclipse.glsp.server.types.ShapeTypeHint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;

/**
 * Configuration for the Bigraph diagram type.
 * Defines the available node types, edge types, and their properties.
 */
public class BigraphDiagramConfiguration extends BaseDiagramConfiguration {

    private static final Logger LOGGER = LogManager.getLogger(BigraphDiagramConfiguration.class);
    

    public BigraphDiagramConfiguration() {
        super();
        //LOGGER.info("🔧 BigraphDiagramConfiguration constructor called");
    }

    @Override
    public String getDiagramType() {
        //LOGGER.info("📋 getDiagramType() called, returning: bigraph-diagram");
        return "bigraph-diagram";
    }

    @Override
    public Map<String, EClass> getTypeMappings() {
        //LOGGER.info("🗺️ getTypeMappings() called");
        Map<String, EClass> mappings = DefaultTypes.getDefaultTypeMappings();
        
        // Add bigraph-specific type mappings - all should map to GNODE
        mappings.put(BIGRAPH, GraphPackage.Literals.GGRAPH);
        mappings.put(BIGRAPH_NODE, GraphPackage.Literals.GNODE);
        mappings.put(ATOMIC_NODE, GraphPackage.Literals.GNODE);
        mappings.put(CONTAINER_NODE, GraphPackage.Literals.GNODE);
        mappings.put(SITE, GraphPackage.Literals.GNODE);
        mappings.put(REGION, GraphPackage.Literals.GNODE);
        mappings.put(CUSTOM_NODE, GraphPackage.Literals.GNODE);
        mappings.put(BIGRAPH_EDGE, GraphPackage.Literals.GEDGE);
        mappings.put(HYPEREDGE, GraphPackage.Literals.GEDGE);
        mappings.put(BIGRAPH_LINK, GraphPackage.Literals.GEDGE);
        mappings.put(OUTER_CONNECTION, GraphPackage.Literals.GEDGE);
        mappings.put(LABEL_NODE_NAME, GraphPackage.Literals.GLABEL);
        mappings.put(LABEL_ARITY, GraphPackage.Literals.GLABEL);
        mappings.put(LABEL_PORT, GraphPackage.Literals.GLABEL);
        
        //LOGGER.info("🗺️ Type mappings created with {} entries", mappings.size());
        for (Map.Entry<String, EClass> entry : mappings.entrySet()) {
            //LOGGER.info("🗺️   Type '{}' -> {}", entry.getKey(), entry.getValue().getName());
        }
        return mappings;
    }

    @Override
    public List<ShapeTypeHint> getShapeTypeHints() {
        List<ShapeTypeHint> nodeHints = new ArrayList<>();
        
        // Basic bigraph nodes
        ShapeTypeHint atomicNodeHint = createDefaultShapeTypeHint(ATOMIC_NODE);
        atomicNodeHint.setResizable(true);
        atomicNodeHint.setReparentable(true);
        nodeHints.add(atomicNodeHint);
        
        ShapeTypeHint containerNodeHint = createDefaultShapeTypeHint(CONTAINER_NODE);
        containerNodeHint.setResizable(true);
        containerNodeHint.setReparentable(true);
        containerNodeHint.setContainableElementTypeIds(Arrays.asList(
            BIGRAPH_NODE, ATOMIC_NODE, CONTAINER_NODE, CUSTOM_NODE, SITE
        ));
        nodeHints.add(containerNodeHint);
        
        ShapeTypeHint customNodeHint = createDefaultShapeTypeHint(CUSTOM_NODE);
        customNodeHint.setResizable(true);
        customNodeHint.setReparentable(true);
        nodeHints.add(customNodeHint);
        
        // Special bigraph elements
        ShapeTypeHint siteHint = createDefaultShapeTypeHint(SITE);
        siteHint.setResizable(false);
        siteHint.setReparentable(true);
        siteHint.setContainableElementTypeIds(Arrays.asList(
            BIGRAPH_NODE, ATOMIC_NODE, CONTAINER_NODE, CUSTOM_NODE
        ));
        nodeHints.add(siteHint);
        
        // Root and region containers
        ShapeTypeHint rootHint = createDefaultShapeTypeHint(BIGRAPH_ROOT);
        rootHint.setResizable(true);
        rootHint.setReparentable(false);
        rootHint.setContainableElementTypeIds(Arrays.asList(
            BIGRAPH_REGION, BIGRAPH_NODE, ATOMIC_NODE, CONTAINER_NODE, CUSTOM_NODE
        ));
        nodeHints.add(rootHint);
        
        ShapeTypeHint regionHint = createDefaultShapeTypeHint(BIGRAPH_REGION);
        regionHint.setResizable(true);
        regionHint.setReparentable(true);
        regionHint.setContainableElementTypeIds(Arrays.asList(
            BIGRAPH_NODE, ATOMIC_NODE, CONTAINER_NODE, CUSTOM_NODE, SITE
        ));
        nodeHints.add(regionHint);
        
        return nodeHints;
    }

    @Override
    public ShapeTypeHint createDefaultShapeTypeHint(final String elementId) {
        // All bigraph nodes should be reparentable by default
        return new ShapeTypeHint(elementId, true, true, true, true);
    }

    @Override
    public List<EdgeTypeHint> getEdgeTypeHints() {
        List<EdgeTypeHint> edgeHints = new ArrayList<>();

        // Standard bigraph edge
        EdgeTypeHint bigraphEdgeHint = createDefaultEdgeTypeHint(BIGRAPH_EDGE);
        bigraphEdgeHint.addSourceElementTypeId(BIGRAPH_NODE, ATOMIC_NODE, CONTAINER_NODE, CUSTOM_NODE);
        bigraphEdgeHint.addTargetElementTypeId(BIGRAPH_NODE, ATOMIC_NODE, CONTAINER_NODE, CUSTOM_NODE);
        edgeHints.add(bigraphEdgeHint);

        // Bigraph link (hyperedge)
        EdgeTypeHint linkHint = createDefaultEdgeTypeHint(BIGRAPH_LINK);
        linkHint.addSourceElementTypeId(PORT);
        linkHint.addTargetElementTypeId(PORT, OUTER_NAME);
        linkHint.setDynamic(true);
        edgeHints.add(linkHint);

        // Hyperedge - ONLY connect Nodes with Names (Link Graph semantics)
        EdgeTypeHint hyperEdgeHint = createDefaultEdgeTypeHint(HYPEREDGE);
        // Allowed sources: Nodes, Inner Names, Outer Names
        hyperEdgeHint.addSourceElementTypeId(BIGRAPH_NODE, INNER_NAME, OUTER_NAME);
        // Allowed targets: Nodes, Inner Names, Outer Names
        hyperEdgeHint.addTargetElementTypeId(BIGRAPH_NODE, INNER_NAME, OUTER_NAME);
        // Note: Validation in CreateHyperEdgeOperationHandler ensures only Node↔Name connections
        hyperEdgeHint.setDynamic(true);
        hyperEdgeHint.setRepositionable(false); // Link graph edges should not be repositioned
        edgeHints.add(hyperEdgeHint);

        // Outer connection for outer name connections
        EdgeTypeHint outerConnectionHint = createDefaultEdgeTypeHint(OUTER_CONNECTION);
        outerConnectionHint.addSourceElementTypeId(OUTER_NAME);
        outerConnectionHint.addTargetElementTypeId(BIGRAPH_NODE, ATOMIC_NODE, CONTAINER_NODE, CUSTOM_NODE);
        outerConnectionHint.setDynamic(true);
        edgeHints.add(outerConnectionHint);

        return edgeHints;
    }

    @Override
    public ServerLayoutKind getLayoutKind() {
        return ServerLayoutKind.MANUAL;
    }

    @Override
    public boolean needsClientLayout() {
        return true;
    }
} 