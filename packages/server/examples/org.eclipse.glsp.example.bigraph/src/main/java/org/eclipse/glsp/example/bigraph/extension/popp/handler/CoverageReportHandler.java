package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import com.google.inject.Inject;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.RequestCoverageReportAction;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.CoverageReportAction;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageMode;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageReportBuilder;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import java.util.List;

/** Read-only: answers with the coverage report for the requested mode. */
public class CoverageReportHandler extends AbstractActionHandler<RequestCoverageReportAction> {
    @Inject
    protected POPPExtensionContext context;

    @Override
    protected List<Action> executeAction(final RequestCoverageReportAction action) {
        CoverageMode mode = CoverageMode.fromString(action.getMode()).orElse(CoverageMode.PLANNING);
        var state = context.getOwnState();
        // Always answer: the client awaits this response. Without a POPP model (e.g. a plain bigraph) it is empty.
        CoverageReportBuilder.CoverageReport report = state.getPoppModel() == null
                ? new CoverageReportBuilder.CoverageReport(mode.name(), List.of(), java.util.Map.of())
                : CoverageReportBuilder.build(state.getPoppModel(), state.getAnalyzer(), mode);
        CoverageReportAction response = new CoverageReportAction(report);
        response.setResponseId(action.getRequestId());
        return List.of(response);
    }
}