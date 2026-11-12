package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.validation.ValidationReport;
import org.eclipse.glsp.server.actions.Action;

/**
 * Pushed to the client after every validation run that was not overtaken by a newer one, not as the answer to a
 * request.
 */
public class ValidationResultAction extends Action {
    public static final String KIND = "popp.validationResult";

    private ValidationReportDto report;

    public ValidationResultAction() {
        super(KIND);
    }

    public ValidationResultAction(final ValidationReport report) {
        super(KIND);
        this.report = ValidationReportDto.of(report);
    }

    public ValidationReportDto getReport() { return report; }
}
