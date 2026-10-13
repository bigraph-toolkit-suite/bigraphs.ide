package org.eclipse.glsp.example.bigraph.extension.popp.validation;

import java.util.List;

/**
 * Result of one validation run.
 *
 * @param modelVersion    {@code POPPModel#getVersion()} of the snapshot that was validated
 * @param valid           no issue of severity {@code ERROR}
 * @param errors          number of {@code ERROR} issues
 * @param warnings        number of {@code WARNING} issues
 * @param issues          all issues, errors first
 * @param durationMs      wall time of the matching (without the snapshot)
 * @param patternsMatched patterns handed to the matcher
 * @param patternsSkipped patterns skipped because the snapshot lacks the controls they need (census)
 */
public record ValidationReport(long modelVersion, boolean valid, int errors, int warnings, List<ValidationIssue> issues,
                               long durationMs, int patternsMatched, int patternsSkipped) {

    public static ValidationReport of(long modelVersion, List<ValidationIssue> issues, long durationMs,
                                      int matched, int skipped) {
        List<ValidationIssue> sorted = issues.stream()
                .sorted((a, b) -> a.severity().compareTo(b.severity()))
                .toList();
        int errors = (int) sorted.stream().filter(i -> i.severity() == ValidationIssue.Severity.ERROR).count();
        return new ValidationReport(modelVersion, errors == 0, errors, sorted.size() - errors, sorted,
                durationMs, matched, skipped);
    }
}
