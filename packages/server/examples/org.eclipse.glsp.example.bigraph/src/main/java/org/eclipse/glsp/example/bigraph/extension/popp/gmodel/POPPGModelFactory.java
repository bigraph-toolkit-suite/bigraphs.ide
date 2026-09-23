package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Relation;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationGraph;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.graph.*;
import org.eclipse.glsp.graph.builder.impl.GEdgeBuilder;
import org.eclipse.glsp.graph.builder.impl.GLabelBuilder;
import org.eclipse.glsp.graph.builder.impl.GNodeBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class POPPGModelFactory {
    private static final Map<DecompositionType, POPPGModelType> DECOMPOSITION_EDGE_TYPE = Map.of(
            DecompositionType.AND, POPPGModelType.AND_DECOMPOSITION,
            DecompositionType.OR, POPPGModelType.OR_DECOMPOSITION
    );

    public GNode createTreeNode(final TreeNode<?> treeNode){
        POPPGModelType gtype = POPPGModelType.getFromClass(treeNode.getClass()).orElseThrow();

        GLabel labelElement = new GLabelBuilder(POPPGModelType.NODE_DESCRIPTION.toString())
                .id(treeNode.getId() + "_description")
                .text(treeNode.getDescription())
                .build();

        GNode gnode = new GNodeBuilder(gtype.toString())
                .id(treeNode.getId())
                .layout("vbox")
                .add(labelElement)
                .addArgument("decomposition_type", treeNode.getDecompositionType().toString())
                .build();

        return gnode;
    }

    public GEdge createRelationEdge(final Relation relation) {
        POPPGModelType gtype = POPPGModelType.getFromRelationType(relation.type()).orElseThrow();

        return new GEdgeBuilder(gtype.toString())
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
        POPPGModelType gtype = DECOMPOSITION_EDGE_TYPE.get(parent.getDecompositionType());

        return new GEdgeBuilder(gtype.toString())
                .id(parent.getId() + "_decomposes_" + child.getId())
                .sourceId(parent.getId())
                .targetId(child.getId())
                .build();
    }

    public List<GEdge> createDecompositionEdges(final TreeNode<?> node) {
        List<GEdge> edges = new ArrayList<>();
        for (TreeNode<?> child : node.getChildren()) {
            edges.add(createDecompositionEdge(node, child));
            edges.addAll(createDecompositionEdges(child));
        }
        return edges;
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
