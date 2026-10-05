package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

public interface CoverageAnalyzer {
    CoverageSession session(CoverageMode mode);

    default Coverage coverage(TreeNode<?> node, CoverageMode mode) {
        return session(mode).coverage(node);
    }

    /** Fully covered by verified proofs only (the original semantics). */
    default boolean isCovered(TreeNode<?> node) {
        return isCovered(node, CoverageMode.VERIFY);
    }

    default boolean isCovered(TreeNode<?> node, CoverageMode mode) {
        return coverage(node, mode) == Coverage.COVERED;
    }

    default CoverageReason explain(TreeNode<?> node) {
        return explain(node, CoverageMode.VERIFY);
    }

    default CoverageReason explain(TreeNode<?> node, CoverageMode mode) {
        return session(mode).explain(node);
    }
}