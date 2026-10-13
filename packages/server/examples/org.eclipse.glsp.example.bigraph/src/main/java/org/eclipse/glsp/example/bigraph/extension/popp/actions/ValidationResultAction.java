package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.validation.ValidationReport;
import org.eclipse.glsp.server.actions.Action;

/** Pushed to the client after every validation run; not a response to a particular request. */
public class ValidationResultAction extends Action {
    public static final String KIND = "popp.validationResult";
    private ValidationReport report;

    public ValidationResultAction() {
        super(KIND);
    }

    public ValidationResultAction(final ValidationReport report) {
        super(KIND);
        this.report = report;
    }

    public ValidationReport getReport() { return report; }
    public void setReport(final ValidationReport report) { this.report = report; }
}
