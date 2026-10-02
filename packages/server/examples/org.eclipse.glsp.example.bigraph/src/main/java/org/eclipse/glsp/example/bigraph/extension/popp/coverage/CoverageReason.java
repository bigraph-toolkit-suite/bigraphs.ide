package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.List;

/** Result of {@link CoverageAnalyzer#explain}, used to drive the inspect/highlight mode. */
public sealed interface CoverageReason {

    /** The node this reason explains the coverage of. */
    TreeNode<?> node();

    /** Node is covered because its {@link NodeKind} is inherently covered (e.g. proof). */
    record Explicit(TreeNode<?> node) implements CoverageReason {
    }

    /** Node is covered because its AND/OR decomposition over {@code children} is satisfied. */
    record Decomposition(TreeNode<?> node, DecompositionType type, List<CoverageReason> children) implements CoverageReason {
    }

    /** Node is covered because a {@code type} relation connects it to the already-covered {@code coveringNode}. */
    record Link(TreeNode<?> node, RelationType type, TreeNode<?> coveringNode, CoverageReason coveringReason) implements CoverageReason {
    }

    /** Node is not covered. */
    record NotCovered(TreeNode<?> node) implements CoverageReason {
    }
}
