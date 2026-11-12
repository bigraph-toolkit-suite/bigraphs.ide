package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import com.google.inject.Inject;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.CoverageGapDto;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.CoverageGapsAction;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.RequestCoverageGapsAction;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageMode;
import org.eclipse.glsp.example.bigraph.extension.popp.state.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import java.util.List;

/** Read-only: the nodes not covered in a mode, of the kinds it reports gaps for (the "highlight uncovered" toggle). */
public class CoverageGapsHandler extends AbstractActionHandler<RequestCoverageGapsAction> {
    @Inject
    protected POPPExtensionContext context;

    @Override
    protected List<Action> executeAction(final RequestCoverageGapsAction action) {
        CoverageMode mode = CoverageMode.fromString(action.getMode()).orElse(CoverageMode.PLANNING);
        POPPModel model = context.getOwnState().getPoppModel();
        List<CoverageGapDto> gaps = model == null ? List.of()
                : context.getOwnState().getAnalyzer().gaps(model, mode).stream()
                .filter(gap -> mode.gapKinds().contains(gap.node().getKind()))
                .map(CoverageGapDto::of)
                .toList();

        CoverageGapsAction response = new CoverageGapsAction(mode.name(), gaps);
        response.setResponseId(action.getRequestId());
        return List.of(response);
    }
}
