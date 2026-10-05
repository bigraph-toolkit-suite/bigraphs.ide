package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

/**
 * Create one per inspection or report and discard it.
 * Results are only valid until the model changes.
 */
public interface CoverageSession {
    Coverage coverage(TreeNode<?> node);

    CoverageReason explain(TreeNode<?> node);
}


