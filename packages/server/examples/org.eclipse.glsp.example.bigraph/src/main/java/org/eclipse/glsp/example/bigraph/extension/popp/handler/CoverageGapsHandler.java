package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import com.google.inject.Inject;
import org.eclipse.glsp.example.bigraph.extension.popp.state.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.CoverageGapsAction;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.RequestCoverageGapsAction;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.Coverage;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageGap;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageMode;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import java.util.List;
import java.util.stream.Collectors;

/** Read-only: every node of a coverable kind that is not covered, for the "highlight uncovered" toggle. */
public class CoverageGapsHandler extends AbstractActionHandler<RequestCoverageGapsAction> {
    @Inject
    protected POPPExtensionContext context;

    @Override
    protected List<Action> executeAction(final RequestCoverageGapsAction action) {
        CoverageMode mode = CoverageMode.fromString(action.getMode()).orElse(CoverageMode.PLANNING);
        POPPModel model = context.getOwnState().getPoppModel();
        List<CoverageGapsAction.Gap> gaps = model == null ? List.of()
                : context.getOwnState().getAnalyzer().gaps(model, mode).gaps().stream()
                .filter(g -> mode.gapKinds().contains(g.node().getKind())) // only kinds between emitters and targets
                .map(CoverageGapsHandler::toGap)
                .toList();

        CoverageGapsAction response = new CoverageGapsAction(mode.name(), gaps);
        response.setResponseId(action.getRequestId());
        return List.of(response);
    }

    private static CoverageGapsAction.Gap toGap(CoverageGap gap) {
        TreeNode<?> node = gap.node();
        String kind = node.getKind().getName();
        String label = node.getDescription() == null || node.getDescription().isBlank()
                ? kind : kind + " \"" + node.getDescription().strip() + "\"";
        String message = gap.missing().stream().map(CoverageGap.CoverageRemedy::describe)
                .collect(Collectors.joining(" or "));
        return new CoverageGapsAction.Gap(node.getId(), label, gap.coverage().name(), gap.rootCause(), message);
    }
}
