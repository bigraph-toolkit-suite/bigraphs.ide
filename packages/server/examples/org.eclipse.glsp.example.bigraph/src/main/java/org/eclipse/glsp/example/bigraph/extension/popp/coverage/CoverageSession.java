package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.Collection;
import java.util.List;

/**
 * The coverage of one model revision in one mode. Results are only valid until the model changes.
 * {@link IncrementalRewritingCoverageAnalyzer} reuses a session until then and never across a change. Not thread-safe.
 */
public interface CoverageSession {
    Coverage coverage(TreeNode<?> node);

    CoverageReason explain(TreeNode<?> node);

    /** The nodes of {@code scope} that are not covered. */
    List<CoverageGap> gaps(Collection<? extends TreeNode<?>> scope);
}


