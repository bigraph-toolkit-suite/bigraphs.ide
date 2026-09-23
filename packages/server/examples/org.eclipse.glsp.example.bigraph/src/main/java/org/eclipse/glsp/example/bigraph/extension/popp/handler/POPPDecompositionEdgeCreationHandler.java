package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import java.util.List;
import java.util.Optional;

import com.google.inject.Inject;

import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Consequence;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Goal;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Problem;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Solution;
import org.eclipse.glsp.example.bigraph.extension.popp.types.SuccessCriteria;
import org.eclipse.glsp.example.bigraph.extension.popp.types.SuccessProof;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.server.gmodel.GModelCreateEdgeOperationHandler;
import org.eclipse.glsp.server.model.GModelState;
import org.eclipse.glsp.server.operations.CreateEdgeOperation;

/**
 * Handles CreateEdgeOperations coming from the AND/OR decomposition.
 * Source = parent, target = child.
 */
public class POPPDecompositionEdgeCreationHandler extends GModelCreateEdgeOperationHandler {

    @Inject
    protected POPPExtensionContext context;

    public POPPDecompositionEdgeCreationHandler() {
        super(List.of(
                POPPGModelType.AND_DECOMPOSITION.toString(),
                POPPGModelType.OR_DECOMPOSITION.toString()));
    }

    @Override
    public void executeCreation(final CreateEdgeOperation operation) {
        POPPModel model = context.getOwnState().getPoppModel();

        TreeNode<?> parent = model.findNode(operation.getSourceElementId());
        TreeNode<?> child = model.findNode(operation.getTargetElementId());
        if (parent == null || child == null || parent.getClass() != child.getClass()) {
            return; // decomposition only ever relates two nodes of the same tree/kind
        }

        addChild(parent, child);
    }

    private boolean addChild(final TreeNode<?> parent, final TreeNode<?> child) {
        return switch (parent) {
            case Problem p when child instanceof Problem c -> p.addChild(c);
            case Goal p when child instanceof Goal c -> p.addChild(c);
            case Consequence p when child instanceof Consequence c -> p.addChild(c);
            case Solution p when child instanceof Solution c -> p.addChild(c);
            case SuccessCriteria p when child instanceof SuccessCriteria c -> p.addChild(c);
            case SuccessProof p when child instanceof SuccessProof c -> p.addChild(c);
            default -> false;
        };
    }

    @Override
    protected Optional<GEdge> createEdge(GModelElement source, GModelElement target, GModelState modelState) {
        return Optional.empty();
    }
}
