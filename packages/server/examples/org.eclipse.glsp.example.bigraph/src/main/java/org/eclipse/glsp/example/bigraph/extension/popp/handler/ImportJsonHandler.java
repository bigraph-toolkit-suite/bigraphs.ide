package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import org.eclipse.glsp.example.bigraph.extension.popp.actions.ImportJsonOperation;
import org.eclipse.glsp.example.bigraph.extension.popp.io.POPPJson;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;

/**
 * Replaces the current POPP model with a POPP JSON document. The document is read and checked completely first, so an
 * invalid file changes nothing; the problems are reported (the first few) as an error notification.
 */
public class ImportJsonHandler extends AbstractPOPPActionHandler<ImportJsonOperation> {
    private static final int REPORTED_PROBLEMS = 5;

    @Override
    protected boolean applyMutation(final ImportJsonOperation action) {
        POPPJson.Document document;
        try {
            document = POPPJson.read(action.getJson() == null ? "" : action.getJson());
        } catch (POPPJson.InvalidDocumentException e) {
            var problems = e.problems();
            String shown = String.join("; ", problems.subList(0, Math.min(REPORTED_PROBLEMS, problems.size())));
            String more = problems.size() > REPORTED_PROBLEMS ? " (and " + (problems.size() - REPORTED_PROBLEMS) + " more)" : "";
            throw new POPPValidationException("Invalid POPP JSON: " + shown + more);
        }
        POPPJson.apply(document, state().getPoppModel());
        BigraphNotifications.notifyInfo(actionDispatcher, "Imported POPP model ("
                + state().getPoppModel().getAllNodes().size() + " nodes, "
                + state().getPoppModel().getRelations().all().size() + " relations)");
        return true;
    }
}
