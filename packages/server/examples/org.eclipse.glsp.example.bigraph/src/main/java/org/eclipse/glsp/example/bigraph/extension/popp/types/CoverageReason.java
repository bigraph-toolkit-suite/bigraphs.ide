package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.List;

/** Result of {@link TreeNode#explainCoverage()}, used to drive the inspect/highlight mode. */
public sealed interface CoverageReason {

    /** The node this reason explains the coverage of. */
    TreeNode<?> node();

    /** Node is covered because its explicit coverage flag was set directly (e.g. a confirmed proof). */
    record Explicit(TreeNode<?> node) implements CoverageReason {
    }

    /** Node is covered because its AND/OR decomposition over {@code children} is satisfied. */
    record Decomposition(TreeNode<?> node, DecompositionType type, List<CoverageReason> children) implements CoverageReason {
    }

    /** Node is covered because a cross-tree coverage link to {@code target} is covered. */
    record Link(TreeNode<?> node, TreeNode<?> target, CoverageReason targetReason) implements CoverageReason {
    }

    /** Node is not covered. */
    record NotCovered(TreeNode<?> node) implements CoverageReason {
    }
}
