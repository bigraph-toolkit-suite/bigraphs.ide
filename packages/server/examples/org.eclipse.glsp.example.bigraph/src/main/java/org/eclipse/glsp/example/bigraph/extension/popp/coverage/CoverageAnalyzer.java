package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.Collection;

public interface CoverageAnalyzer {
    CoverageSession session(CoverageMode mode);

    default Coverage coverage(TreeNode<?> node, CoverageMode mode) {
        return session(mode).coverage(node);
    }

    default boolean isCovered(TreeNode<?> node, CoverageMode mode) {
        return coverage(node, mode) == Coverage.COVERED;
    }

    default CoverageReason explain(TreeNode<?> node, CoverageMode mode) {
        return session(mode).explain(node);
    }

    default CoverageGap.CoverageGaps gaps(Collection<? extends TreeNode<?>> scope, CoverageMode mode) {
        return session(mode).gaps(scope);
    }

    default CoverageGap.CoverageGaps gaps(POPPModel model, CoverageMode mode) {
        return session(mode).gaps(model.getAllNodes());
    }
}