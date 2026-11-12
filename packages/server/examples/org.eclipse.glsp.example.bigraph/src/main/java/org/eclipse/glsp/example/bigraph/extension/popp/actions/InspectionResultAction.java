package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.InspectionResult;
import org.eclipse.glsp.server.actions.ResponseAction;

/** Answer to {@link RequestInspectionAction}: the {@link InspectionResult} of the requested node. */
public class InspectionResultAction extends ResponseAction {
    public static final String KIND = "popp.inspectionResult";

    private InspectionResult result;

    public InspectionResultAction() {
        super(KIND);
    }

    public InspectionResultAction(final InspectionResult result) {
        super(KIND);
        this.result = result;
    }

    public InspectionResult getResult() { return result; }
}