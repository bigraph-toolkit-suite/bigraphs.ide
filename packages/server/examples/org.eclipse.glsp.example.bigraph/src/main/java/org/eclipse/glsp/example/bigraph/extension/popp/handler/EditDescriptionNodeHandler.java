package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.server.features.directediting.ApplyLabelEditOperation;

public class EditDescriptionNodeHandler extends AbstractPOPPActionHandler<ApplyLabelEditOperation> {
    @Override
    protected void applyMutation(ApplyLabelEditOperation action) {
        String labelId = action.getLabelId();
        String suffix = "_description";
        if (!labelId.endsWith(suffix)) {
            return;
        }
        String nodeId = labelId.substring(0, labelId.length() - suffix.length());

        TreeNode<?> node = context.getOwnState().getPoppModel().findNode(nodeId);
        if (node == null) throw new POPPValidationException("Not not found");
        node.setDescription(action.getText());
    }
}
