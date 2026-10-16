package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import com.google.inject.Singleton;
import com.google.inject.Inject;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationGraph;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.server.features.typehints.EdgeCreationChecker;

import java.util.Optional;

/**
 * Lets the connect tool start on a node (or its description label) and end on another node of the same kind
 * (a decomposition) or of a kind the metamodel relates it to (a relation).
 */
@Singleton
public class POPPEdgeCreationChecker implements EdgeCreationChecker {
    private final POPPExtensionContext context;
    private final POPPGModelFactory factory = new POPPGModelFactory();

    @Inject
    public POPPEdgeCreationChecker(final POPPExtensionContext context){
        this.context = context;
    }

    @Override
    public boolean isValidSource(String edgeType, GModelElement sourceElement) {
        Optional<NodeKind> nodeKind = POPPGModelTypes.nodeKindOf(sourceElement.getType());
        return nodeKind.isPresent() || sourceElement.getType().equals(POPPGModelTypes.NODE_DESCRIPTION);
    }

    @Override
    public boolean isValidTarget(String edgeType, GModelElement sourceElement, GModelElement targetElement) {
        // the source may be a description label: map element ids to node ids like EdgeCreationHandler does
        TreeNode<?> source = context.getOwnState().getPoppModel().findNode(factory.toNodeId(sourceElement.getId()));
        TreeNode<?> target = context.getOwnState().getPoppModel().findNode(factory.toNodeId(targetElement.getId()));
        if (source == null || target == null || source == target) return false;

        if (source.getKind() == target.getKind()) {
            return true; // same-kind nodes can be linked as a decomposition parent/child
        }
        return RelationGraph.canRelate(source, target);
    }
}
