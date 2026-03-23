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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.Control;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.signature.DynamicControl;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for printing detailed bigraph information to the terminal.
 * Useful for debugging and understanding the current state of a bigraph model.
 */
public final class BigraphDebugPrinter {

    private static final Logger LOGGER = LogManager.getLogger(BigraphDebugPrinter.class);
    private static final String SEPARATOR = "═".repeat(60);

    private BigraphDebugPrinter() {
        // Utility class
    }

    /**
     * Prints a complete overview of the bigraph to the terminal.
     * 
     * @param bigraph the bigraph to print
     */
    public static void print(final PureBigraph bigraph) {
        print(bigraph, "Bigraph Debug Output");
    }

    /**
     * Prints a complete overview of the bigraph to the terminal with a custom title.
     * 
     * @param bigraph the bigraph to print
     * @param title custom title for the output
     */
    public static void print(final PureBigraph bigraph, final String title) {
        if (bigraph == null) {
            LOGGER.info("╔{}╗", SEPARATOR);
            LOGGER.info("║ ⚠️  BIGRAPH IS NULL");
            LOGGER.info("╚{}╝", SEPARATOR);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔").append(SEPARATOR).append("╗\n");
        sb.append("║ 📊 ").append(title).append("\n");
        sb.append("╠").append(SEPARATOR).append("╣\n");

        // Summary
        sb.append("║ SUMMARY\n");
        sb.append("║   Roots: ").append(bigraph.getRoots().size()).append("\n");
        sb.append("║   Nodes: ").append(bigraph.getNodes().size()).append("\n");
        sb.append("║   Sites: ").append(bigraph.getSites().size()).append("\n");
        sb.append("║   Edges: ").append(bigraph.getEdges().size()).append("\n");
        sb.append("║   Inner Names: ").append(bigraph.getInnerNames().size()).append("\n");
        sb.append("║   Outer Names: ").append(bigraph.getOuterNames().size()).append("\n");
        sb.append("╠").append(SEPARATOR).append("╣\n");

        // Signature
        sb.append("║ 🔧 SIGNATURE (Controls)\n");
        if (bigraph.getSignature() != null) {
            for (Control<?, ?> control : bigraph.getSignature().getControls()) {
                sb.append("║   • ").append(control.getNamedType().stringValue())
                  .append(" (arity: ").append(control.getArity().getValue())
                  .append(", kind: ").append(control.getControlKind()).append(")\n");
            }
        } else {
            sb.append("║   (no signature)\n");
        }
        sb.append("╠").append(SEPARATOR).append("╣\n");

        // Roots
        sb.append("║ 🌳 ROOTS\n");
        for (BigraphEntity.RootEntity root : bigraph.getRoots()) {
            sb.append("║   [Root ").append(root.getIndex()).append("]\n");
            List<BigraphEntity<?>> children = bigraph.getChildrenOf(root);
            for (BigraphEntity<?> child : children) {
                appendEntityTree(sb, bigraph, child, "║     ", "");
            }
        }
        sb.append("╠").append(SEPARATOR).append("╣\n");

        // All Nodes (flat list)
        sb.append("║ 📦 NODES (flat list)\n");
        for (BigraphEntity.NodeEntity<DynamicControl> node : bigraph.getNodes()) {
            String nodeName = node.getName() != null ? node.getName() : "(unnamed)";
            String controlName = node.getControl().getNamedType().stringValue();
            BigraphEntity<?> parent = bigraph.getParent(node);
            String parentInfo = getParentInfo(parent);
            
            sb.append("║   • ").append(nodeName).append(" : ").append(controlName)
              .append(" [parent: ").append(parentInfo).append("]\n");
            
            // Show links/ports
            Collection<BigraphEntity.Link> links = bigraph.getIncidentLinksOf(node);
            if (links != null && !links.isEmpty()) {
                sb.append("║       Links: ");
                sb.append(links.stream()
                    .filter(link -> link != null)
                    .map(link -> getLinkInfo(link))
                    .collect(Collectors.joining(", ")));
                sb.append("\n");
            }
        }
        sb.append("╠").append(SEPARATOR).append("╣\n");

        // Sites
        sb.append("║ 🔲 SITES\n");
        if (bigraph.getSites().isEmpty()) {
            sb.append("║   (none)\n");
        }
        for (BigraphEntity.SiteEntity site : bigraph.getSites()) {
            BigraphEntity<?> parent = bigraph.getParent(site);
            sb.append("║   • Site ").append(site.getIndex())
              .append(" [parent: ").append(getParentInfo(parent)).append("]\n");
        }
        sb.append("╠").append(SEPARATOR).append("╣\n");

        // Edges (Hyperedges)
        sb.append("║ 🔗 EDGES (Link Graph)\n");
        if (bigraph.getEdges().isEmpty()) {
            sb.append("║   (none)\n");
        }
        for (BigraphEntity.Edge edge : bigraph.getEdges()) {
            sb.append("║   • Edge: ").append(edge.getName() != null ? edge.getName() : "(anonymous)").append("\n");
            // Get points connected to this edge
            Collection<BigraphEntity<?>> points = bigraph.getPointsFromLink(edge);
            if (points != null && !points.isEmpty()) {
                sb.append("║       Connected: ");
                sb.append(points.stream()
                    .filter(p -> p != null)
                    .map(p -> getEntityInfo(p))
                    .collect(Collectors.joining(", ")));
                sb.append("\n");
            }
        }
        sb.append("╠").append(SEPARATOR).append("╣\n");

        // Inner Names
        sb.append("║ ⬅️  INNER NAMES\n");
        if (bigraph.getInnerNames().isEmpty()) {
            sb.append("║   (none)\n");
        }
        for (BigraphEntity.InnerName innerName : bigraph.getInnerNames()) {
            BigraphEntity.Link link = bigraph.getLinkOfPoint(innerName);
            sb.append("║   • ").append(innerName.getName());
            if (link != null) {
                sb.append(" → ").append(getLinkInfo(link));
            }
            sb.append("\n");
        }
        sb.append("╠").append(SEPARATOR).append("╣\n");

        // Outer Names
        sb.append("║ ➡️  OUTER NAMES\n");
        if (bigraph.getOuterNames().isEmpty()) {
            sb.append("║   (none)\n");
        }
        for (BigraphEntity.OuterName outerName : bigraph.getOuterNames()) {
            sb.append("║   • ").append(outerName.getName()).append("\n");
            // Get points connected to this outer name
            Collection<BigraphEntity<?>> points = bigraph.getPointsFromLink(outerName);
            if (points != null && !points.isEmpty()) {
                sb.append("║       Connected: ");
                sb.append(points.stream()
                    .filter(p -> p != null)
                    .map(p -> getEntityInfo(p))
                    .collect(Collectors.joining(", ")));
                sb.append("\n");
            }
        }

        sb.append("╚").append(SEPARATOR).append("╝\n");

        LOGGER.info(sb.toString());
    }

    /**
     * Recursively appends entity tree information.
     */
    private static void appendEntityTree(StringBuilder sb, PureBigraph bigraph, 
            BigraphEntity<?> entity, String prefix, String childPrefix) {
        
        if (entity instanceof BigraphEntity.NodeEntity) {
            @SuppressWarnings("unchecked")
            BigraphEntity.NodeEntity<DynamicControl> node = (BigraphEntity.NodeEntity<DynamicControl>) entity;
            String nodeName = node.getName() != null ? node.getName() : "(unnamed)";
            String controlName = node.getControl().getNamedType().stringValue();
            sb.append(prefix).append("├─ ").append(nodeName).append(" : ").append(controlName).append("\n");
            
            List<BigraphEntity<?>> children = bigraph.getChildrenOf(node);
            for (int i = 0; i < children.size(); i++) {
                boolean isLast = (i == children.size() - 1);
                String newPrefix = prefix + (isLast ? "   " : "│  ");
                appendEntityTree(sb, bigraph, children.get(i), prefix + "│  ", newPrefix);
            }
        } else if (entity instanceof BigraphEntity.SiteEntity) {
            BigraphEntity.SiteEntity site = (BigraphEntity.SiteEntity) entity;
            sb.append(prefix).append("├─ [Site ").append(site.getIndex()).append("]\n");
        }
    }

    /**
     * Gets a string representation of a parent entity.
     */
    private static String getParentInfo(BigraphEntity<?> parent) {
        if (parent == null) {
            return "none";
        }
        if (parent instanceof BigraphEntity.RootEntity) {
            return "Root " + ((BigraphEntity.RootEntity) parent).getIndex();
        }
        if (parent instanceof BigraphEntity.NodeEntity) {
            @SuppressWarnings("unchecked")
            BigraphEntity.NodeEntity<DynamicControl> node = (BigraphEntity.NodeEntity<DynamicControl>) parent;
            String name = node.getName() != null ? node.getName() : "(unnamed)";
            return name + ":" + node.getControl().getNamedType().stringValue();
        }
        return parent.getClass().getSimpleName();
    }

    /**
     * Gets a string representation of a link.
     */
    private static String getLinkInfo(BigraphEntity.Link link) {
        if (link == null) {
            return "(unlinked)";
        }
        if (link instanceof BigraphEntity.Edge) {
            BigraphEntity.Edge edge = (BigraphEntity.Edge) link;
            return "Edge(" + (edge.getName() != null ? edge.getName() : "anon") + ")";
        }
        if (link instanceof BigraphEntity.OuterName) {
            return "OuterName(" + ((BigraphEntity.OuterName) link).getName() + ")";
        }
        return link.getClass().getSimpleName();
    }

    /**
     * Gets a string representation of any bigraph entity.
     */
    private static String getEntityInfo(BigraphEntity<?> entity) {
        if (entity instanceof BigraphEntity.NodeEntity) {
            @SuppressWarnings("unchecked")
            BigraphEntity.NodeEntity<DynamicControl> node = (BigraphEntity.NodeEntity<DynamicControl>) entity;
            String name = node.getName() != null ? node.getName() : "(unnamed)";
            return "Node(" + name + ":" + node.getControl().getNamedType().stringValue() + ")";
        }
        if (entity instanceof BigraphEntity.InnerName) {
            return "InnerName(" + ((BigraphEntity.InnerName) entity).getName() + ")";
        }
        if (entity instanceof BigraphEntity.OuterName) {
            return "OuterName(" + ((BigraphEntity.OuterName) entity).getName() + ")";
        }
        return entity.getClass().getSimpleName();
    }
}

