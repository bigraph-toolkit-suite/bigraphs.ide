package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import org.eclipse.glsp.example.bigraph.extension.popp.actions.SwitchDecompositionTypeOperation;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

public class SwitchDecompositionTypeHandler extends AbstractPOPPActionHandler<SwitchDecompositionTypeOperation> {

    @Override
    protected void applyMutation(final SwitchDecompositionTypeOperation action) {
        POPPModel model = state().getPoppModel();

        TreeNode<?> node = model.findNode(action.getNodeId());
        if (node == null) {
            throw new POPPValidationException("No such node: " + action.getNodeId());
        }

        DecompositionType requested = action.getNewType();
        if (requested == DecompositionType.NONE) {
            return;
        }

        if (!node.setDecompositionType(requested)) {
            throw new POPPValidationException("Cannot switch decomposition type of '" + node.getId() + "'");
        }
    }
}
