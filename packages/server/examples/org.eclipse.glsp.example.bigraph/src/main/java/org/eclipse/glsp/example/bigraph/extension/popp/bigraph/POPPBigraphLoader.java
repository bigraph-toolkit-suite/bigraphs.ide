package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import com.google.inject.Inject;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionMeta;
import org.eclipse.glsp.example.bigraph.extension.popp.validation.POPPValidationService;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionResult;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionState;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelSynchronizer;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Rebuilds a {@link POPPModel} from a bigraph.  Uses a read-only {@link POPPBigraph}
 * for every structural query, so it can't drift from the write side.
 */
@SuppressWarnings("unchecked")
public final class POPPBigraphLoader {
    private final static Logger LOGGER = LoggerFactory.getLogger(POPPBigraphLoader.class);

    private final POPPExtensionContext context;
    private final POPPGModelSynchronizer gModelSynchronizer;
    private final POPPBigraphSynchronizer bigraphSynchronizer;
    private final POPPValidationService validationService;


    @Inject
    public POPPBigraphLoader(final POPPExtensionContext context,
                             final POPPGModelSynchronizer gModelSynchronizer,
                             final POPPBigraphSynchronizer bigraphSynchronizer,
                             final POPPValidationService validationService) {
        this.context = context;
        this.gModelSynchronizer = gModelSynchronizer;
        this.bigraphSynchronizer = bigraphSynchronizer;
        this.validationService = validationService;
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
            poppExtensionState.getPoppModel().removeListener(validationService);
        }
        poppExtensionState.setPoppModel(model);

        model.addListener(gModelSynchronizer);
        load(bigraph, model);

        model.addListener(bigraphSynchronizer);
        model.addListener(validationService);
        validationService.schedule(); // the loaded file may already be broken

        applyMetaData(context.getOwnState().getInitialLoadedMeta(), model);
        context.getOwnState().setInitialLoadedMeta(null);
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
                var node = (BigraphEntity.NodeEntity<DynamicControl>) raw;
                if (structure.isDomainNode(node)) {
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
        NodeKind kind = structure.kindOf(bnode).orElseThrow(
                () -> new IllegalStateException("Not a domain node: " + bnode.getName()));

        Map<String, Object> attrs = bnode.getAttributes();
        String description = attrs.get("description") != null ? attrs.get("description").toString() : "";
        double x = attrs.get("layout.x") instanceof Number n ? n.doubleValue() : 0.0;
        double y = attrs.get("layout.y") instanceof Number n ? n.doubleValue() : 0.0;

        TreeNode<?> node = model.restoreNode(kind, bnode.getName(), description, x, y);
        nodesById.put(bnode.getName(), node);

        if (parentOrNull != null) {
            DecompositionResult attached = model.decompose(parentOrNull, node);
            if (!attached.isSuccess()) {
                // e.g. a node of another kind nested in the file: keep it as a root, validation reports the file
                LOGGER.warn("Could not attach {} under {} while loading: {}", node.getId(), parentOrNull.getId(), attached);
            }
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

    private static void applyMetaData(Map<String, POPPExtensionMeta.NodeData> metaData, POPPModel model) {
        metaData.forEach((id, data) -> {
            TreeNode<?> node = model.findNode(id);
            if (node != null) {
                data.restoreInfo(node);
            }
        });
    }
}