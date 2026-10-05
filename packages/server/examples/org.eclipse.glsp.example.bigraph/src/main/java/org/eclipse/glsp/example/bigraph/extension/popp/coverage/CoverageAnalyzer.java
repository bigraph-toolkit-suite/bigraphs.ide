package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

public interface CoverageAnalyzer {
    CoverageSession session(CoverageMode mode);

    default Coverage coverage(TreeNode<?> node, CoverageMode mode) {
        return session(mode).coverage(node);
    }

    /** Fully covered by verified proofs only (the original semantics). */
    default boolean isCovered(TreeNode<?> node) {
        return coverage(node, CoverageMode.VERIFIED) == Coverage.COVERED;
    }

    default CoverageReason explain(TreeNode<?> node) {
        return explain(node, CoverageMode.VERIFIED);
    }

    default CoverageReason explain(TreeNode<?> node, CoverageMode mode) {
        return session(mode).explain(node);
    }
}