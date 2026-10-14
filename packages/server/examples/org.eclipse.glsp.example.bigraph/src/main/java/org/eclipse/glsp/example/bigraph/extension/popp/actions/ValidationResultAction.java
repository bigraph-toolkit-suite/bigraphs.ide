package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.validation.ValidationIssue;
import org.eclipse.glsp.example.bigraph.extension.popp.validation.ValidationReport;
import org.eclipse.glsp.server.actions.Action;

import java.util.List;

/** Pushed to the client after every validation run; not a response to a particular request */
public class ValidationResultAction extends Action {
    public static final String KIND = "popp.validationResult";

    /** {@link ValidationIssue} with {@code code} and {@code severity} as enum names. */
    public record Issue(String code, String severity, String message, List<String> nodeIds,
                        List<String> elements, String pattern) {
        static Issue of(ValidationIssue i) {
            return new Issue(i.code().name(), i.severity().name(), i.message(), List.copyOf(i.nodeIds()),
                    List.copyOf(i.elements()), i.pattern());
        }
    }

    public record Report(long modelVersion, boolean valid, int errors, int warnings, List<Issue> issues,
                         long durationMs, int patternsMatched, int patternsSkipped) {
        static Report of(ValidationReport r) {
            return new Report(r.modelVersion(), r.valid(), r.errors(), r.warnings(),
                    r.issues().stream().map(Issue::of).toList(),
                    r.durationMs(), r.patternsMatched(), r.patternsSkipped());
        }
    }

    private Report report;

    public ValidationResultAction() {
        super(KIND);
    }

    public ValidationResultAction(final ValidationReport report) {
        super(KIND);
        this.report = Report.of(report);
    }

    public Report getReport() { return report; }
    public void setReport(final Report report) { this.report = report; }
}
