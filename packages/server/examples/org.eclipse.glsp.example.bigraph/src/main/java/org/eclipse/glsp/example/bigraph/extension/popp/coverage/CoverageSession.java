package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.Collection;

/**
 * The coverage of one model revision in one mode. Results are only valid until the model changes;
 * {@link CachingCoverageAnalyzer} reuses a session until then and never across a change. Not thread-safe.
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


