package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.validation.ValidationIssue;
import org.eclipse.glsp.example.bigraph.extension.popp.validation.ValidationReport;
import org.eclipse.glsp.server.actions.Action;

import java.util.List;

/**
 * Pushed to the client after every validation run that was not overtaken by a newer one. Not a response to a
 * particular request. The nested records are plain-string DTOs because LSP4J's Gson, which carries actions to the
 * client, serializes enums as ordinals.
 */
public class ValidationResultAction extends Action {
    public static final String KIND = "popp.validationResult";

    /** {@link ValidationIssue} with {@code code} and {@code severity} as enum names. */
    public record Issue(String code, String severity, String message, List<String> nodeIds,
                        List<String> elements, String pattern) {
        static Issue of(ValidationIssue issue) {
            return new Issue(issue.code().name(), issue.severity().name(), issue.message(), List.copyOf(issue.nodeIds()),
                    List.copyOf(issue.elements()), issue.pattern());
        }
    }

    public record Report(long modelVersion, boolean valid, int errors, int warnings, List<Issue> issues,
                         long durationMs, int patternsMatched, int patternsSkipped) {
        static Report of(ValidationReport report) {
            return new Report(report.modelVersion(), report.valid(), report.errors(), report.warnings(),
                    report.issues().stream().map(Issue::of).toList(),
                    report.durationMs(), report.patternsMatched(), report.patternsSkipped());
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
