package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.Collection;

/**
 * Create one per inspection or report and discard it.
 * Results are only valid until the model changes.
 */
public interface CoverageSession {
    Coverage coverage(TreeNode<?> node);

    CoverageReason explain(TreeNode<?> node);

    /** Gaps among {@code scope} (e.g. all nodes, or one selected subtree). Not-COVERED nodes only. */
    CoverageGap.CoverageGaps gaps(Collection<? extends TreeNode<?>> scope);

    default CoverageGap.CoverageGaps gaps(POPPModel model) {
        return gaps(model.getAllNodes());
    }
}


