package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import com.google.inject.Inject;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.RequestTraceabilityReportAction;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.TraceabilityReportAction;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageMode;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.TraceabilityReportBuilder;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import java.util.List;

public class TraceabilityReportHandler extends AbstractActionHandler<RequestTraceabilityReportAction> {
    @Inject
    protected POPPExtensionContext context;

    @Override
    protected List<Action> executeAction(final RequestTraceabilityReportAction action) {
        CoverageMode mode = CoverageMode.fromString(action.getMode()).orElse(CoverageMode.PLANNED);
        POPPModel model = context.getOwnState().getPoppModel();

        TraceabilityReportAction response = new TraceabilityReportAction(TraceabilityReportBuilder.build(model, mode));
        response.setResponseId(action.getRequestId());
        return List.of(response);
    }
}