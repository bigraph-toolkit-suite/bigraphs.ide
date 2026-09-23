package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtension;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.eclipse.glsp.server.types.EdgeTypeHint;
import org.eclipse.glsp.server.types.ShapeTypeHint;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class POPPTypeHints {
    private POPPTypeHints() {}

    public static List<EdgeTypeHint> edgeTypeHintList(final Function<String, EdgeTypeHint> hintFactory) {
        List<POPPGModelType> relations = POPPGModelType.getRelations();
        List<EdgeTypeHint> hints = new ArrayList<>(2+relations.size());

        EdgeTypeHint or = hintFactory.apply(POPPGModelType.OR_DECOMPOSITION.toString());
        or.setDynamic(true);
        or.setRepositionable(false);
        EdgeTypeHint and = hintFactory.apply(POPPGModelType.AND_DECOMPOSITION.toString());
        and.setDynamic(true);
        and.setRepositionable(false);

        POPPGModelType.getNodes().forEach(n -> {
            or.addSourceElementTypeId(n.toString());
            or.addTargetElementTypeId(n.toString());
            and.addSourceElementTypeId(n.toString());
            and.addTargetElementTypeId(n.toString());
        });

        hints.add(or);
        hints.add(and);

        for (POPPGModelType relation : relations){
            EdgeTypeHint relationHint = hintFactory.apply(relation.toString());
            relationHint.setDynamic(true);
            relationHint.setRepositionable(false);

            Set<RelationType.NodeTypePair> supportedTypes = relation.getDomainEquivalentRelation().getSupportedNodeTypePair();
            supportedTypes.forEach(pair -> {
                POPPGModelType.getFromClass(pair.from()).ifPresent(source -> relationHint.addSourceElementTypeId(source.toString()));
                POPPGModelType.getFromClass(pair.to()).ifPresent(target -> relationHint.addTargetElementTypeId(target.toString()));
            });
        }

        return hints;
    }

    public static List<ShapeTypeHint> shapeTypeHints(final Function<String, ShapeTypeHint> hintFactory) {
        List<POPPGModelType> nodeTypes = POPPGModelType.getNodes();
        List<ShapeTypeHint> hints = new ArrayList<>(nodeTypes.size() + 2);

        for (POPPGModelType nodeType : nodeTypes) {
            ShapeTypeHint nodeHint = hintFactory.apply(nodeType.toString());
            nodeHint.setRepositionable(true);
            nodeHint.setResizable(false);
            nodeHint.setReparentable(true);
            nodeHint.setDeletable(true);
            hints.add(nodeHint);
        }

        ShapeTypeHint nodeDescriptionHint = hintFactory.apply(POPPGModelType.NODE_DESCRIPTION.toString());
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
        variantRoot.setContainableElementTypeIds(nodeTypes.stream().map(POPPGModelType::toString).toList());
        hints.add(variantRoot);

        return hints;
    }
}
