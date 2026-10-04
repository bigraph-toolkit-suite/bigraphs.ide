package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelFactory;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.server.operations.CreateNodeOperation;

public class POPPCreateNodeOperationHandler extends AbstractPOPPActionHandler<CreateNodeOperation> {

    private final POPPGModelFactory factory = new POPPGModelFactory();

    @Override
    protected boolean applyMutation(CreateNodeOperation action) {
        String nodeTypeStr = null;
        if (action.getArgs() != null) {
            nodeTypeStr = action.getArgs().get("nodeType");
        }

        NodeKind nodeKind = NodeKind.fromString(nodeTypeStr)
                .orElseThrow(() -> new POPPValidationException("Invalid Node Type"));

        GPoint location = action.getLocation().orElse(factory.point(0d, 0d));

        POPPModel model = context.getOwnState().getPoppModel();
        model.createNode(nodeKind, "Double click to edit", location.getX(), location.getY());
        return true;
    }
}