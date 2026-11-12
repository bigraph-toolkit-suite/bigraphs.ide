package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageGap;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.stream.Collectors;

/**
 * A {@link CoverageGap} as the client receives it, in {@link CoverageGapsAction}.
 *
 * @param nodeId    POPP node id (= GModel id)
 * @param label     kind and description, e.g. {@code goal "Ship v2"}
 * @param coverage  {@code UNCOVERED} or {@code PARTIAL}
 * @param rootCause nothing below or behind the node is itself not covered: this is where to act
 * @param message   what is missing (root causes only, otherwise empty), e.g.
 *                  {@code needs a success criteria (causes)}, several joined with " or "
 */
public record CoverageGapDto(String nodeId, String label, String coverage, boolean rootCause, String message) {

    public static CoverageGapDto of(CoverageGap gap) {
        TreeNode<?> node = gap.node();
        String kind = node.getKind().getName();
        String description = node.getDescription();
        String label = description == null || description.isBlank() ? kind : kind + " \"" + description.strip() + "\"";
        String message = gap.missing().stream().map(CoverageGap.CoverageRemedy::describe)
                .collect(Collectors.joining(" or "));
        return new CoverageGapDto(node.getId(), label, gap.coverage().name(), gap.rootCause(), message);
    }
}
