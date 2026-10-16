package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import org.eclipse.glsp.example.bigraph.extension.popp.actions.StartDescriptionEditAction;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelFactory;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.server.operations.CreateNodeOperation;

/** Creates a node of the kind in the operation's {@code nodeType} argument at the requested location. */
public class CreateNodeOperationHandler extends AbstractPOPPActionHandler<CreateNodeOperation> {

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
        TreeNode<?> node = model.createNode(nodeKind, "Double click to edit", location.getX(), location.getY());
        // Open the description editor once the client has the node (after the update this mutation triggers)
        actionDispatcher.dispatchAfterNextUpdate(new StartDescriptionEditAction(factory.toDescriptionId(node)));
        return true;
    }
}