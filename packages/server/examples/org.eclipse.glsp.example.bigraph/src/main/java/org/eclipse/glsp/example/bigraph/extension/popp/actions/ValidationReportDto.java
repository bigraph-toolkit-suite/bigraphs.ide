package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.validation.ValidationReport;

import java.util.List;

/** A {@link ValidationReport} as the client receives it, in {@link ValidationResultAction}. */
public record ValidationReportDto(long modelVersion, boolean valid, int errors, int warnings,
                                  List<ValidationIssueDto> issues, long durationMs, int patternsMatched,
                                  int patternsSkipped) {

    public static ValidationReportDto of(ValidationReport report) {
        return new ValidationReportDto(report.modelVersion(), report.valid(), report.errors(), report.warnings(),
                report.issues().stream().map(ValidationIssueDto::of).toList(),
                report.durationMs(), report.patternsMatched(), report.patternsSkipped());
    }
}
