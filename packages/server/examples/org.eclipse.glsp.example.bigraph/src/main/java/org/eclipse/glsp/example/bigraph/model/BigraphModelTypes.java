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

/**
 * Defines all model types used in the bigraph diagram.
 * These types correspond to different elements in bigraph theory.
 */
public final class BigraphModelTypes {
    
    // Base bigraph elements
    public static final String BIGRAPH = "bigraph";
    public static final String BIGRAPH_NODE = "bigraph:node";
    public static final String ATOMIC_NODE = "bigraph:atomic";
    public static final String CONTAINER_NODE = "bigraph:container";
    public static final String SITE = "bigraph:site";
    public static final String REGION = "bigraph:region";
    
    // Root and region containers
    public static final String BIGRAPH_ROOT = "bigraph:root";
    public static final String BIGRAPH_REGION = "bigraph:region";
    
    // Custom node type
    public static final String CUSTOM_NODE = "bigraph:custom";
    
    // Port, inner name and outer name
    public static final String PORT = "bigraph:port";
    public static final String INNER_NAME = "bigraph:inner-name";
    public static final String OUTER_NAME = "bigraph:outer-name";
    
    // Bigraph connections
    public static final String LINK = "bigraph:link";
    public static final String BIGRAPH_LINK = "bigraph:link";
    public static final String BIGRAPH_EDGE = "bigraph:edge";
    public static final String HYPEREDGE = "bigraph:hyperedge";
    public static final String OUTER_CONNECTION = "bigraph:outer-connection";
    public static final String EDGE = "bigraph:edge";
    public static final String CONNECTION = "bigraph:connection";
    
    // Labels
    public static final String LABEL_NODE_NAME = "bigraph:label:name";
    public static final String LABEL_ARITY = "bigraph:label:arity";
    public static final String LABEL_PORT = "bigraph:label:port";
    
    // Structural elements
    public static final String ROOT = "bigraph:root";
    public static final String PLACE_GRAPH = "bigraph:place_graph";
    public static final String LINK_GRAPH = "bigraph:link_graph";
    
    // UI elements
    public static final String PALETTE_GROUP = "bigraph:palette_group";
    public static final String TOOLBAR_BUTTON = "bigraph:toolbar_button";
    public static final String NODE_EXPLORER = "bigraph:node_explorer";
    
    // Custom node types (dynamically created)
    public static final String CUSTOM_NODE_PREFIX = "bigraph:custom:";
    
    // Utility method to create custom node type
    public static String customNodeType(String nodeId) {
        return CUSTOM_NODE_PREFIX + nodeId;
    }
    
    // Check if a type is a custom node type
    public static boolean isCustomNodeType(String type) {
        return type != null && type.startsWith(CUSTOM_NODE_PREFIX);
    }
    
    // Extract custom node ID from type
    public static String extractCustomNodeId(String type) {
        if (isCustomNodeType(type)) {
            return type.substring(CUSTOM_NODE_PREFIX.length());
        }
        return null;
    }
    
    private BigraphModelTypes() {
        // Utility class - no instantiation
    }
} 