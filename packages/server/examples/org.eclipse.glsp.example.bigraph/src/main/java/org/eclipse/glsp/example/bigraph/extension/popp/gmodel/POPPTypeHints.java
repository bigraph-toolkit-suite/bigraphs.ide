package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtension;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.eclipse.glsp.server.types.EdgeTypeHint;
import org.eclipse.glsp.server.types.ShapeTypeHint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class POPPTypeHints {
    private POPPTypeHints() {}

    public static List<EdgeTypeHint> edgeTypeHintList(final Function<String, EdgeTypeHint> hintFactory) {
        List<EdgeTypeHint> hints = new ArrayList<>(2+RelationType.values().length);

        EdgeTypeHint decomp = hintFactory.apply(POPPGModelTypes.DECOMPOSITION_EDGE);
        EdgeTypeHint connect = hintFactory.apply(POPPGModelTypes.CONNECT);
        decomp.setDynamic(true);
        decomp.setRepositionable(false);
        connect.setDynamic(true);
        connect.setRepositionable(false);

        Arrays.stream(NodeKind.values()).forEach(n -> {
            String gType = POPPGModelTypes.of(n);
            decomp.addSourceElementTypeId(gType);
            decomp.addTargetElementTypeId(gType);
            connect.addSourceElementTypeId(gType);
            connect.addTargetElementTypeId(gType);
        });

        hints.add(decomp);
        hints.add(connect);

        for (RelationType relation : RelationType.values()){
            EdgeTypeHint relationHint = hintFactory.apply(POPPGModelTypes.of(relation));
            relationHint.setDynamic(true);
            relationHint.setRepositionable(false);

            Set<RelationType.NodeTypePair> supportedTypes = relation.getSupportedNodeTypePair();
            supportedTypes.forEach(pair -> {
                relationHint.addSourceElementTypeId(POPPGModelTypes.of(pair.from()));
                relationHint.addTargetElementTypeId(POPPGModelTypes.of(pair.to()));
            });
        }

        return hints;
    }

    public static List<ShapeTypeHint> shapeTypeHints(final Function<String, ShapeTypeHint> hintFactory) {
        List<ShapeTypeHint> hints = new ArrayList<>(NodeKind.values().length + 2);

        for (NodeKind nodeType : NodeKind.values()) {
            ShapeTypeHint nodeHint = hintFactory.apply(POPPGModelTypes.of(nodeType));
            nodeHint.setRepositionable(true);
            nodeHint.setResizable(false);
            nodeHint.setReparentable(true);
            nodeHint.setDeletable(true);
            hints.add(nodeHint);
        }

        ShapeTypeHint nodeDescriptionHint = hintFactory.apply(POPPGModelTypes.NODE_DESCRIPTION);
        nodeDescriptionHint.setRepositionable(false);
        nodeDescriptionHint.setResizable(false);
        nodeDescriptionHint.setReparentable(false);
        nodeDescriptionHint.setDeletable(false);
        hints.add(nodeDescriptionHint);

        ShapeTypeHint variantRoot = hintFactory.apply(POPPExtension.POPP_VARIANT_ID + "-root");
        variantRoot.setRepositionable(false);
        variantRoot.setResizable(false);
        variantRoot.setReparentable(false);
        variantRoot.setDeletable(false);
        variantRoot.setContainableElementTypeIds(Arrays.stream(NodeKind.values()).map(POPPGModelTypes::of).toList());
        hints.add(variantRoot);

        return hints;
    }
}
