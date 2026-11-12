package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.validation.ValidationIssue;

import java.util.List;

/** A {@link ValidationIssue} as the client receives it, with {@code code} and {@code severity} as enum names. */
public record ValidationIssueDto(String code, String severity, String message, List<String> nodeIds,
                                 List<String> elements, String pattern) {

    public static ValidationIssueDto of(ValidationIssue issue) {
        return new ValidationIssueDto(issue.code().name(), issue.severity().name(), issue.message(),
                List.copyOf(issue.nodeIds()), List.copyOf(issue.elements()), issue.pattern());
    }
}
