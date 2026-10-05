package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import com.google.inject.Inject;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.InspectionResultAction;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.RequestInspectionAction;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageMode;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelFactory;
import org.eclipse.glsp.example.bigraph.extension.popp.inspection.InspectionResult;
import org.eclipse.glsp.example.bigraph.extension.popp.inspection.InspectionTracer;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import java.util.List;

/** Read-only: answers with a response action and never touches the model, the GModel or the dirty state. */
public class InspectionActionHandler extends AbstractActionHandler<RequestInspectionAction> {
    @Inject
    protected POPPExtensionContext context;

    private final InspectionTracer tracer = new InspectionTracer(new POPPGModelFactory());

    @Override
    protected List<Action> executeAction(final RequestInspectionAction action) {
        CoverageMode mode = CoverageMode.fromString(action.getMode()).orElse(CoverageMode.PLANNING);
        POPPModel model = context.getOwnState().getPoppModel();
        TreeNode<?> node = model == null ? null : model.findNode(action.getNodeId());

        InspectionResult result = node == null
                ? InspectionResult.unknown(action.getNodeId(), mode.name())
                : tracer.trace(model, node, mode);

        InspectionResultAction response = new InspectionResultAction(result);
        response.setResponseId(action.getRequestId());
        return List.of(response);
    }
}