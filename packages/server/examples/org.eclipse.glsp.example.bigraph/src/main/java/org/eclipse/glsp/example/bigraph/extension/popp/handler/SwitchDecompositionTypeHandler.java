package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import com.google.inject.Inject;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.SwitchDecompositionTypeOperation;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;

import java.util.List;

public class SwitchDecompositionTypeHandler extends AbstractActionHandler<SwitchDecompositionTypeOperation> {
    @Inject
    protected POPPExtensionContext context;

    @Override
    public List<Action> executeAction(final SwitchDecompositionTypeOperation operation) {
        POPPModel model = context.getOwnState().getPoppModel();

        TreeNode<?> node = model.findNode(operation.getNodeId());
        if (node == null) {
            return List.of();
        }

        DecompositionType requested = operation.getNewType();

        if (requested == DecompositionType.NONE) {
            return List.of();
        }

        node.setDecompositionType(requested);
        return List.of(new UpdateModelAction(context.getGModelRoot()));
    }
}
