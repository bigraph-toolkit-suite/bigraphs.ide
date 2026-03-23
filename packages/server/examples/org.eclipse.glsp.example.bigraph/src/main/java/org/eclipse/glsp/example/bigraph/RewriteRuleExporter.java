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

import org.bigraphs.framework.core.BigraphEntityType;
import org.bigraphs.framework.core.exceptions.InvalidConnectionException;
import org.bigraphs.framework.core.exceptions.InvalidReactionRuleException;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;
import static org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder;

/**
 * Utility class to create a simple rewrite rule and export it to BigRed .bigraph-rule format.
 * 
 * NOTE: Unfortunately, the framework does not provide an existing exporter for reaction rules to BigRed format.
 * Only signatures can be exported using SignatureAdapter + SignatureXMLSaver.
 * This class manually builds the XML, but uses the framework's model classes to access the bigraph structure.
 */
public class RewriteRuleExporter {

    /**
     * Creates a very simple rewrite rule and exports it to BigRed XML format.
     * 
     * This example creates a rule where:
     * - Redex: A root containing a "Node" control with a site
     * - Reactum: The same structure but with an additional "Node" child inside the site
     * 
     * @param outputPath The file path where the .bigraph-rule file should be written
     * @throws InvalidReactionRuleException if the rule is invalid
     * @throws IOException if file writing fails
     */
    public static void createAndExportSimpleRule(String outputPath) throws InvalidReactionRuleException, InvalidConnectionException, IOException {
        // Create a simple signature with one control
        DynamicSignature signature = pureSignatureBuilder()
                .add("Node", 0)  // Control named "Node" with arity 0 (no ports)
                .create();

        // Create the redex (left-hand side of the rule)
        PureBigraphBuilder<DynamicSignature> redexBuilder = pureBuilder(signature);
        redexBuilder.root()
                .child("Node", "node1")  // Create a node with name "node1"
                .down()                   // Go into the node
                .site();                  // Add a site (placeholder)

        PureBigraph redex = redexBuilder.create();

        // Create the reactum (right-hand side of the rule)
        PureBigraphBuilder<DynamicSignature> reactumBuilder = pureBuilder(signature);
        reactumBuilder.root()
                .child("Node", "node1")  // Same node
                .down()                   // Go into the node
                .site()                   // Same site
                .down()                   // Go into the site
                .child("Node", "node2");  // Add a new child node inside the site

        PureBigraph reactum = reactumBuilder.create();

        // Create the reaction rule
        ParametricReactionRule<PureBigraph> rule = new ParametricReactionRule<>(redex, reactum);

        // Export to BigRed XML format
        exportToBigRedFormat(rule, outputPath);
    }

    /**
     * Exports a reaction rule to BigRed .bigraph-rule XML format.
     * This method properly serializes both the redex and reactum using the framework's model classes.
     * 
     * @param rule The reaction rule to export
     * @param outputPath The file path where the XML should be written
     * @throws IOException if file writing fails
     */
    private static void exportToBigRedFormat(ParametricReactionRule<PureBigraph> rule, String outputPath) 
            throws IOException {
        File outputFile = new File(outputPath);
        outputFile.getParentFile().mkdirs(); // Create parent directories if needed

        PureBigraph redex = rule.getRedex();
        PureBigraph reactum = rule.getReactum();
        DynamicSignature signature = redex.getSignature();

        try (FileWriter writer = new FileWriter(outputFile)) {
            // Write XML header
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
            
            // Write root element
            writer.write("<rule:rule xmlns:rule=\"http://www.itu.dk/research/pls/xmlns/2011/rule\">\n");
            
            // Write bigraph section (redex)
            writer.write("  <bigraph:bigraph xmlns:bigraph=\"http://www.itu.dk/research/pls/xmlns/2010/bigraph\">\n");
            
            // Write signature reference (simplified - in real BigRed this would reference an external file)
            writer.write("    <signature:signature xmlns:signature=\"http://www.itu.dk/research/pls/xmlns/2010/signature\">\n");
            writer.write("      <!-- Signature with controls: ");
            signature.getControls().forEach(control -> {
                try {
                    writer.write(control.getNamedType().stringValue() + "(" + control.getArity().getValue() + ") ");
                } catch (Exception e) {
                    // Ignore
                }
            });
            writer.write("-->\n");
            writer.write("    </signature:signature>\n");
            
            // Write outer names
            for (BigraphEntity.OuterName outerName : redex.getOuterNames()) {
                writer.write("    <bigraph:outername name=\"" + escapeXml(outerName.getName()) + "\"/>\n");
            }
            
            // Write edges
            for (BigraphEntity.Edge edge : redex.getEdges()) {
                writer.write("    <bigraph:edge name=\"" + escapeXml(edge.getName()) + "\"/>\n");
            }
            
            // Write roots and their place structure
            List<BigraphEntity.RootEntity> roots = new ArrayList<>(redex.getRoots());
            Collections.sort(roots, Comparator.comparing(BigraphEntity.RootEntity::getIndex));
            
            for (BigraphEntity.RootEntity root : roots) {
                writer.write("    <bigraph:root name=\"" + root.getIndex() + "\">\n");
                writePlaceStructure(writer, redex, root, "      ");
                writer.write("    </bigraph:root>\n");
            }
            
            writer.write("  </bigraph:bigraph>\n");
            
            // Write changes section (how to transform redex to reactum)
            writer.write("  <rule:changes xmlns:change=\"http://www.itu.dk/research/pls/xmlns/2010/change\">\n");
            computeAndWriteChanges(writer, redex, reactum);
            writer.write("  </rule:changes>\n");
            
            // Close root element
            writer.write("</rule:rule>\n");
        }
    }

    /**
     * Helper method to write the place structure (nodes, sites) recursively.
     * This properly serializes the bigraph structure using the framework's model classes.
     */
    private static void writePlaceStructure(FileWriter writer, PureBigraph bigraph, 
            BigraphEntity<?> parent, String indent) throws IOException {
        List<BigraphEntity<?>> children = bigraph.getChildrenOf(parent);
        
        // Sort children for consistent output (nodes first, then sites)
        children.sort((a, b) -> {
            if (BigraphEntityType.isNode(a) && BigraphEntityType.isSite(b)) return -1;
            if (BigraphEntityType.isSite(a) && BigraphEntityType.isNode(b)) return 1;
            return 0;
        });
        
        for (BigraphEntity<?> child : children) {
            if (BigraphEntityType.isNode(child)) {
                BigraphEntity.NodeEntity<?> node = (BigraphEntity.NodeEntity<?>) child;
                String nodeName = node.getName();
                String controlName = node.getControl().getNamedType().stringValue();
                
                writer.write(indent + "<bigraph:node control=\"" + escapeXml(controlName) + "\" name=\"" + escapeXml(nodeName) + "\">\n");
                
                // Write ports with their links
                List<BigraphEntity.Port> ports = new ArrayList<>(bigraph.getPorts(node));
                ports.sort(Comparator.comparing(BigraphEntity.Port::getIndex));
                
                for (BigraphEntity.Port port : ports) {
                    BigraphEntity.Link link = bigraph.getLinkOfPoint(port);
                    if (link != null) {
                        String linkName = getLinkName(link);
                        String portName = getPortName((org.bigraphs.framework.core.impl.signature.DynamicControl) node.getControl(), port.getIndex());
                        writer.write(indent + "  <bigraph:port link=\"" + escapeXml(linkName) + "\" name=\"" + escapeXml(portName) + "\"/>\n");
                    }
                }
                
                // Recursively write children
                writePlaceStructure(writer, bigraph, child, indent + "  ");
                
                writer.write(indent + "</bigraph:node>\n");
            } else if (BigraphEntityType.isSite(child)) {
                BigraphEntity.SiteEntity site = (BigraphEntity.SiteEntity) child;
                writer.write(indent + "<bigraph:site name=\"" + site.getIndex() + "\">\n");
                
                // Recursively write children of the site
                writePlaceStructure(writer, bigraph, child, indent + "  ");
                
                writer.write(indent + "</bigraph:site>\n");
            }
        }
    }
    
    /**
     * Computes the differences between redex and reactum and writes the changes section.
     */
    private static void computeAndWriteChanges(FileWriter writer, PureBigraph redex, PureBigraph reactum) 
            throws IOException {
        // Collect all entities from redex and reactum
        Set<String> redexNodeNames = new HashSet<>();
        Set<Integer> redexSiteIndices = new HashSet<>();
        Map<String, BigraphEntity.NodeEntity<?>> redexNodes = new HashMap<>();
        Map<Integer, BigraphEntity.SiteEntity> redexSites = new HashMap<>();
        
        for (BigraphEntity.NodeEntity<?> node : redex.getNodes()) {
            redexNodeNames.add(node.getName());
            redexNodes.put(node.getName(), node);
        }
        for (BigraphEntity.SiteEntity site : redex.getSites()) {
            redexSiteIndices.add(site.getIndex());
            redexSites.put(site.getIndex(), site);
        }
        
        Set<String> reactumNodeNames = new HashSet<>();
        Set<Integer> reactumSiteIndices = new HashSet<>();
        Map<String, BigraphEntity.NodeEntity<?>> reactumNodes = new HashMap<>();
        Map<Integer, BigraphEntity.SiteEntity> reactumSites = new HashMap<>();
        
        for (BigraphEntity.NodeEntity<?> node : reactum.getNodes()) {
            reactumNodeNames.add(node.getName());
            reactumNodes.put(node.getName(), node);
        }
        for (BigraphEntity.SiteEntity site : reactum.getSites()) {
            reactumSiteIndices.add(site.getIndex());
            reactumSites.put(site.getIndex(), site);
        }
        
        // Find nodes that were added
        Set<String> addedNodes = new HashSet<>(reactumNodeNames);
        addedNodes.removeAll(redexNodeNames);
        
        // Find nodes that were removed
        Set<String> removedNodes = new HashSet<>(redexNodeNames);
        removedNodes.removeAll(reactumNodeNames);
        
        // Find sites that were added
        Set<Integer> addedSites = new HashSet<>(reactumSiteIndices);
        addedSites.removeAll(redexSiteIndices);
        
        // Find sites that were removed
        Set<Integer> removedSites = new HashSet<>(redexSiteIndices);
        removedSites.removeAll(reactumSiteIndices);
        
        // Write change groups
        boolean hasChanges = false;
        
        if (!removedNodes.isEmpty() || !removedSites.isEmpty()) {
            writer.write("    <change:group>\n");
            for (String nodeName : removedNodes) {
                writer.write("      <change:remove name=\"" + escapeXml(nodeName) + "\" type=\"node\"/>\n");
                hasChanges = true;
            }
            for (Integer siteIndex : removedSites) {
                writer.write("      <change:remove name=\"" + siteIndex + "\" type=\"site\"/>\n");
                hasChanges = true;
            }
            writer.write("    </change:group>\n");
        }
        
        if (!addedNodes.isEmpty() || !addedSites.isEmpty()) {
            writer.write("    <change:group>\n");
            for (String nodeName : addedNodes) {
                BigraphEntity.NodeEntity<?> node = reactumNodes.get(nodeName);
                String controlName = node.getControl().getNamedType().stringValue();
                BigraphEntity<?> parent = reactum.getParent(node);
                String parentName = getParentName(parent);
                String parentType = getParentType(parent);
                
                writer.write("      <change:add control=\"" + escapeXml(controlName) + "\" name=\"" + escapeXml(nodeName) + 
                           "\" parent=\"" + escapeXml(parentName) + "\" parent-type=\"" + parentType + "\" type=\"node\"/>\n");
                hasChanges = true;
            }
            for (Integer siteIndex : addedSites) {
                BigraphEntity.SiteEntity site = reactumSites.get(siteIndex);
                BigraphEntity<?> parent = reactum.getParent(site);
                String parentName = getParentName(parent);
                String parentType = getParentType(parent);
                
                writer.write("      <change:add name=\"" + siteIndex + "\" parent=\"" + escapeXml(parentName) + 
                           "\" parent-type=\"" + parentType + "\" type=\"site\"/>\n");
                hasChanges = true;
            }
            writer.write("    </change:group>\n");
        }
        
        // Check for connection changes (simplified - full implementation would compare port connections)
        // For now, we write an empty group if no changes were detected
        if (!hasChanges) {
            writer.write("    <change:group>\n");
            writer.write("      <!-- No structural changes detected -->\n");
            writer.write("    </change:group>\n");
        }
    }
    
    /**
     * Helper to get the name of a link (edge or outer name).
     */
    private static String getLinkName(BigraphEntity.Link link) {
        if (BigraphEntityType.isEdge(link)) {
            return ((BigraphEntity.Edge) link).getName();
        } else if (BigraphEntityType.isOuterName(link)) {
            return ((BigraphEntity.OuterName) link).getName();
        }
        return "";
    }
    
    /**
     * Helper to get port name from control and port index.
     * This is a simplified version - in a full implementation, you'd get the actual port name from the signature.
     */
    private static String getPortName(org.bigraphs.framework.core.impl.signature.DynamicControl control, int portIndex) {
        // In BigRed format, ports are typically named. For simplicity, we use "port" + index
        // A full implementation would look up the actual port name from the signature
        return "port" + portIndex;
    }
    
    /**
     * Helper to get parent name for change operations.
     */
    private static String getParentName(BigraphEntity<?> parent) {
        if (parent == null) return "";
        if (BigraphEntityType.isRoot(parent)) {
            return String.valueOf(((BigraphEntity.RootEntity) parent).getIndex());
        } else if (BigraphEntityType.isNode(parent)) {
            return ((BigraphEntity.NodeEntity<?>) parent).getName();
        } else if (BigraphEntityType.isSite(parent)) {
            return String.valueOf(((BigraphEntity.SiteEntity) parent).getIndex());
        }
        return "";
    }
    
    /**
     * Helper to get parent type for change operations.
     */
    private static String getParentType(BigraphEntity<?> parent) {
        if (parent == null) return "root";
        if (BigraphEntityType.isRoot(parent)) {
            return "root";
        } else if (BigraphEntityType.isNode(parent)) {
            return "node";
        } else if (BigraphEntityType.isSite(parent)) {
            return "site";
        }
        return "root";
    }
    
    /**
     * Escapes XML special characters.
     */
    private static String escapeXml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;");
    }
}

