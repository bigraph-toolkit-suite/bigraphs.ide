package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelFactory;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.server.features.directediting.ApplyLabelEditOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EditDescriptionNodeHandler extends AbstractPOPPActionHandler<ApplyLabelEditOperation> {
    private final static Logger LOGGER = LoggerFactory.getLogger(EditDescriptionNodeHandler.class);
    private final POPPGModelFactory factory = new POPPGModelFactory();

    @Override
    protected boolean applyMutation(ApplyLabelEditOperation action) {
        String labelId = action.getLabelId();
        if (!factory.isDescriptionId(labelId)) {
            return false;
        }
        TreeNode<?> node = context.getOwnState().getPoppModel().findNode(factory.toNodeId(labelId));
        if (node == null) throw new POPPValidationException("Not not found");
        node.setDescription(action.getText());
        return true;
    }
}
