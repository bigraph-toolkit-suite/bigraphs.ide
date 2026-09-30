package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import com.google.inject.Inject;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionState;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelSynchronizer;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Rebuilds a {@link POPPModel} from a bigraph.  Uses a read-only {@link POPPBigraph}
 * for every structural query, so it can't drift from the write side.
 */
@SuppressWarnings("unchecked")
public final class POPPBigraphLoader {
    private final POPPExtensionContext context;
    private final POPPGModelSynchronizer gModelSynchronizer;
    private final POPPBigraphSynchronizer bigraphSynchronizer;


    @Inject
    public POPPBigraphLoader(final POPPExtensionContext context,
                             final POPPGModelSynchronizer gModelSynchronizer,
                             final POPPBigraphSynchronizer bigraphSynchronizer) {
        this.context = context;
        this.gModelSynchronizer = gModelSynchronizer;
        this.bigraphSynchronizer = bigraphSynchronizer;
    }

    public void loadModelFromBigraph(final PureBigraphMutable bigraph) {
        if (bigraph == null || bigraph.getRoots().isEmpty()) {
            return;
        }

        final POPPExtensionState poppExtensionState = context.getOwnState();
        final POPPModel model = new POPPModel();

        if (poppExtensionState.getPoppModel() != null) {
            poppExtensionState.getPoppModel().removeListener(bigraphSynchronizer);
            poppExtensionState.getPoppModel().removeListener(gModelSynchronizer);
        }
        poppExtensionState.setPoppModel(model);

        model.addListener(gModelSynchronizer);
        load(bigraph, model);

        model.addListener(bigraphSynchronizer);
    }

    private POPPModel load(final PureBigraphMutable bigraph, POPPModel model){
        POPPBigraph structure = new POPPBigraph(bigraph);

        if (bigraph.getRoots().isEmpty()) {
            return model;
        }

        BigraphEntity.NodeEntity<DynamicControl> container = structure.findPOPPContainer(bigraph.getRoots().getFirst());
        if (container == null) {
            return model;
        }

        Map<String, TreeNode<?>> nodesById = new HashMap<>();

        for (BigraphEntity<?> child : bigraph.getChildrenOf(container)) {
            if (child instanceof BigraphEntity.NodeEntity<?> raw) {
                BigraphEntity.NodeEntity<DynamicControl> node = (BigraphEntity.NodeEntity<DynamicControl>) raw;
                if (POPPBigraphSignature.forNode(node) != null) {
                    buildDomainSubtree(structure, node, model, null, nodesById);
                }
            }
        }

        reconstructRelations(bigraph, structure, model, nodesById);

        return model;
    }

    /** Recursively restores one domain node and its same-kind decomposition subtree. */
    private static void buildDomainSubtree(
            POPPBigraph structure,
            BigraphEntity.NodeEntity<DynamicControl> bnode,
            POPPModel model,
            TreeNode<?> parentOrNull,
            Map<String, TreeNode<?>> nodesById) {

        POPPBigraphSignature control = POPPBigraphSignature.forNode(bnode);
        NodeKind kind = POPPBigraphSignature.toNodeKind(control);

        Map<String, Object> attrs = bnode.getAttributes();
        String description = attrs.get("description") != null ? attrs.get("description").toString() : "";
        double x = attrs.get("x") instanceof Number n ? n.doubleValue() : 0.0;
        double y = attrs.get("y") instanceof Number n ? n.doubleValue() : 0.0;

        TreeNode<?> node = model.restoreNode(kind, bnode.getName(), description, x, y);
        nodesById.put(bnode.getName(), node);

        if (parentOrNull != null) {
            link(parentOrNull, node);
        }

        POPPBigraph.DecodedDecomposition decoded = structure.decodeDecomposition(bnode);
        for (BigraphEntity.NodeEntity<DynamicControl> childB : decoded.children()) {
            buildDomainSubtree(structure, childB, model, node, nodesById);
        }
        if (decoded.type() != DecompositionType.NONE) {
            setDecompositionType(node, decoded.type());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends TreeNode<T>> void link(TreeNode<T> parent, TreeNode<?> child) {
        boolean linked = parent.addChild((T) child);
        if (!linked) {
            throw new IllegalStateException(
                    "Failed to attach " + child.getId() + " under " + parent.getId() + " while loading");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends TreeNode<T>> void setDecompositionType(
            TreeNode<T> node, DecompositionType type) {
        node.setDecompositionType(type);
    }

    private static void reconstructRelations(
            PureBigraphMutable bigraph, POPPBigraph structure, POPPModel model, Map<String, TreeNode<?>> nodesById) {

        for (BigraphEntity.Edge edge : bigraph.getEdges()) {
            structure.decodeRelation(edge).ifPresent(endpoints -> {
                TreeNode<?> source = nodesById.get(endpoints.source().getName());
                TreeNode<?> target = nodesById.get(endpoints.target().getName());
                if (source != null && target != null) {
                    model.relate(source, target);
                }
            });
        }
    }
}