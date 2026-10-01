package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import java.util.List;
import java.util.Optional;

import com.google.inject.Inject;

import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationResult;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.gmodel.GModelCreateEdgeOperationHandler;
import org.eclipse.glsp.server.model.GModelState;
import org.eclipse.glsp.server.operations.CreateEdgeOperation;

/**
 * Universal "connect" tool backing the palette's single edge-drawing button:
 * links two nodes of the same kind as a decomposition parent/child, or two
 * nodes of different kinds as a cross-tree relation — whichever the two
 * endpoints support. The actual GModel edge is created by
 * {@code POPPGModelSynchronizer} once the semantic model emits its event.
 */
public class POPPConnectEdgeCreationHandler extends GModelCreateEdgeOperationHandler {

    @Inject
    protected POPPExtensionContext context;

    @Inject
    protected ActionDispatcher actionDispatcher;

    public POPPConnectEdgeCreationHandler() {
        super(List.of(POPPGModelType.CONNECT.toString()));
    }

    @Override
    public void executeCreation(final CreateEdgeOperation operation) {
        POPPModel model = context.getOwnState().getPoppModel();

        TreeNode<?> source = model.findNode(operation.getSourceElementId());
        TreeNode<?> target = model.findNode(operation.getTargetElementId());
        if (source == null || target == null || source == target) {
            return;
        }

        if (source.getClass() == target.getClass()) {
            model.addChild(source, target);
            return;
        }

        RelationResult result = model.relate(source, target);
        if (result == RelationResult.NO_SUCH_RELATION_FOR_KINDS) {
            BigraphNotifications.notifyError(actionDispatcher,
                    "There is no relationship defined between the two node types.");
        }
    }

    @Override
    protected Optional<GEdge> createEdge(GModelElement source, GModelElement target, GModelState modelState) {
        return Optional.empty();
    }
}
