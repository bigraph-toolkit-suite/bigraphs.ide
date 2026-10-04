package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Relation;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationGraph;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.graph.*;
import org.eclipse.glsp.graph.builder.impl.GEdgeBuilder;
import org.eclipse.glsp.graph.builder.impl.GLabelBuilder;
import org.eclipse.glsp.graph.builder.impl.GNodeBuilder;
import org.eclipse.glsp.graph.builder.impl.GPortBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class POPPGModelFactory {
    private static final double CHAR_W = 6.5, LINE_H = 16, PAD_LEFT = 35, PAD_RIGHT = 10, PAD_V = 10;
    private static final double MIN_W = 180, MIN_H = 50, MAX_RATIO = 4.0;

    static List<String> wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.trim().split("\\s+")) {
            if (cur.length() > 0 && cur.length() + 1 + w.length() > maxChars) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        lines.add(cur.toString());
        return lines;
    }

    private static int maxChars(double width) {
        return Math.max(1, (int) ((width - PAD_LEFT - PAD_RIGHT) / CHAR_W));
    }

    private static double heightFor(int lineCount) {
        return Math.max(MIN_H, lineCount * LINE_H + 2 * PAD_V);
    }

    public String portId(final TreeNode<?> node) {
        return node.getId() + "_decomp";
    }

    public String toNodeId(final String elementId) {
        if (elementId == null) return null;
        for (String suffix : List.of("_decomp", "_description")) {
            if (elementId.endsWith(suffix)) {
                return elementId.substring(0, elementId.length() - suffix.length());
            }
        }
        return elementId;
    }

    public GNode createTreeNode(final TreeNode<?> treeNode) {
        GLabel label = new GLabelBuilder(POPPGModelTypes.NODE_DESCRIPTION)
                .id(treeNode.getId() + "_description")
                .text(treeNode.getDescription())
                .build();

        GPort port = new GPortBuilder(POPPGModelTypes.DECOMPOSITION_PORT)
                .id(portId(treeNode))
                .build();

        GNode gnode = new GNodeBuilder(POPPGModelTypes.of(treeNode.getKind()))
                .id(treeNode.getId())
                .add(label)
                .add(port)
                .addArgument("decomposition_type", treeNode.getDecompositionType().toString())
                .build();

        applyLayout(gnode, treeNode);
        return gnode;
    }

    /** Recomputes wrapping, node size, label position and label size from the TreeNode. Idempotent. */
    public void applyLayout(final GNode gnode, final TreeNode<?> treeNode) {
        String desc = treeNode.getDescription() == null ? "" : treeNode.getDescription();

        int minLines = Integer.MAX_VALUE;
        for (double w = MIN_W; w <= 480; w += 20) {
            int n = wrap(desc, maxChars(w)).size();
            if (w / heightFor(n) <= MAX_RATIO) minLines = Math.min(minLines, n);
        }
        double width = MIN_W;
        int lineCount = wrap(desc, maxChars(MIN_W)).size();
        for (double w = MIN_W; w <= 480; w += 20) {
            int n = wrap(desc, maxChars(w)).size();
            if (n == minLines && w / heightFor(n) <= MAX_RATIO) { width = w; lineCount = n; break; }
        }
        double finalWidth = width;
        double height = heightFor(lineCount);
        double labelW = width - PAD_LEFT - PAD_RIGHT;
        double labelH = lineCount * LINE_H;

        gnode.setPosition(point(treeNode.getX(), treeNode.getY()));
        gnode.setSize(size(width, height));

        gnode.getChildren().stream()
                .filter(c -> POPPGModelTypes.NODE_DESCRIPTION.equals(c.getType()) && c instanceof GLabel)
                .map(c -> (GLabel) c)
                .findFirst()
                .ifPresent(label -> {
                    label.setText(desc);
                    label.setPosition(point(PAD_LEFT, (height - labelH) / 2));
                    label.setSize(size(labelW, labelH));
                });

        gnode.getChildren().stream()
                .filter(c -> POPPGModelTypes.DECOMPOSITION_PORT.equals(c.getType()) && c instanceof GPort)
                .map(c -> (GPort) c)
                .findFirst()
                .ifPresent(port -> {
                    port.setSize(size(12, 16));
                    port.setPosition(point(finalWidth / 2 - 6, height)); // diamond tip touches the bottom border
                    port.getArgs().put("decomposition_type", treeNode.getDecompositionType().toString());
                });
    }


    public GEdge createRelationEdge(final Relation relation) {
        return new GEdgeBuilder(POPPGModelTypes.of(relation.type()))
                .id(relationEdgeId(relation))
                .sourceId(relation.source().getId())
                .targetId(relation.target().getId())
                .build();
    }

    public String relationEdgeId(Relation relation) {
        return relation.source().getId() + "_" + relation.type().name().toLowerCase() + "_" + relation.target().getId();
    }

    public List<GEdge> createRelationEdges(final RelationGraph relationGraph) {
        return relationGraph.all().stream()
                .map(this::createRelationEdge)
                .collect(Collectors.toList());
    }

    public GEdge createDecompositionEdge(final TreeNode<?> parent, final TreeNode<?> child) {
        return new GEdgeBuilder(POPPGModelTypes.DECOMPOSITION_EDGE)
                .id(parent.getId() + "_decomposes_" + child.getId())
                .sourceId(portId(parent))
                .targetId(child.getId())
                .build();
    }

    public GPoint point(final double x, final double y) {
        GPoint p = GraphFactory.eINSTANCE.createGPoint();
        p.setX(x);
        p.setY(y);
        return p;
    }

    public GDimension size(final double w, final double h) {
        GDimension d = GraphFactory.eINSTANCE.createGDimension();
        d.setWidth(w);
        d.setHeight(h);
        return d;
    }
}
