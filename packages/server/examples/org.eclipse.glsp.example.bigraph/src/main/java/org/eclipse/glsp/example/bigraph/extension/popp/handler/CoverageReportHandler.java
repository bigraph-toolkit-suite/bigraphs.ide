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

public class CoverageReportHandler extends AbstractActionHandler<RequestCoverageReportAction> {
    @Inject
    protected POPPExtensionContext context;

    @Override
    protected List<Action> executeAction(final RequestCoverageReportAction action) {
        CoverageMode mode = CoverageMode.fromString(action.getMode()).orElse(CoverageMode.PLANNING);
        POPPModel model = context.getOwnState().getPoppModel();

        CoverageReportAction response = new CoverageReportAction(CoverageReportBuilder.build(model, mode));
        response.setResponseId(action.getRequestId());
        return List.of(response);
    }
}