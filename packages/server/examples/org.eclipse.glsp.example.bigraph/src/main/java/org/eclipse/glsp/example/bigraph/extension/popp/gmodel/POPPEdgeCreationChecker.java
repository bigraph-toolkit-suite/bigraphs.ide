package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import com.google.inject.Singleton;
import com.google.inject.Inject;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationGraph;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.server.features.typehints.EdgeCreationChecker;

import java.util.Optional;

@Singleton
public class POPPEdgeCreationChecker implements EdgeCreationChecker {
    private final POPPExtensionContext context;

    @Inject
    public POPPEdgeCreationChecker(final POPPExtensionContext context){
        this.context = context;
    }

    @Override
    public boolean isValidSource(String edgeType, GModelElement sourceElement) {
        Optional<POPPGModelType> sourceTypeUnpacked = POPPGModelType.getFromId(sourceElement.getType());
        if (sourceTypeUnpacked.isEmpty()) return false;
        POPPGModelType sourceType = sourceTypeUnpacked.get();

        if (!sourceType.isNodeType()) return false;
        return true;
    }

    @Override
    public boolean isValidTarget(String edgeType, GModelElement sourceElement, GModelElement targetElement) {
        TreeNode<?> source = context.getOwnState().getPoppModel().findNode(sourceElement.getId());
        TreeNode<?> target = context.getOwnState().getPoppModel().findNode(targetElement.getId());
        if (source == null || target == null || source == target) return false;

        if (source.getClass() == target.getClass()) {
            return true; // same-kind nodes can be linked as a decomposition parent/child
        }
        return RelationGraph.canRelate(source, target);
    }
}
