package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.google.inject.Inject;

import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationResult;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.gmodel.GModelCreateEdgeOperationHandler;
import org.eclipse.glsp.server.model.GModelState;
import org.eclipse.glsp.server.operations.CreateEdgeOperation;

/**
 * Handles CreateEdgeOperations coming from edges created between different trees.
 * Delegates entirely to POPPModel.relate(), which already derives the correct RelationType
 * and canonical direction from the two node-types.
 */
public class POPPRelationEdgeCreationHandler extends GModelCreateEdgeOperationHandler {

    @Inject
    protected POPPExtensionContext context;

    @Inject
    protected ActionDispatcher actionDispatcher;

    public POPPRelationEdgeCreationHandler() {
        super(handledElementTypeIds());
    }

    private static List<String> handledElementTypeIds() {
        return Arrays.stream(RelationType.values())
                .map(type -> POPPGModelType.getFromRelationType(type).orElseThrow().toString())
                .distinct()
                .toList();
    }

    @Override
    public void executeCreation(final CreateEdgeOperation operation) {
        POPPModel model = context.getOwnState().getPoppModel();

        TreeNode<?> source = model.findNode(operation.getSourceElementId());
        TreeNode<?> target = model.findNode(operation.getTargetElementId());
        if (source == null || target == null) {
            return;
        }

        RelationResult result = model.relate(source, target);
        if (result == RelationResult.NO_SUCH_RELATION_FOR_KINDS) {
            BigraphNotifications.notifyError(actionDispatcher, "There is no relation ship defined between the two node types.");
        }
    }

    @Override
    protected Optional<GEdge> createEdge(GModelElement source, GModelElement target, GModelState modelState) {
        return Optional.empty();
    }
}