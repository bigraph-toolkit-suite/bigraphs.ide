package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

public interface CoverageAnalyzer {
    public boolean isCovered(TreeNode<?> node);

    /**
     * Explains why {@code node} is (or isn't) covered as a small, on-demand reason tree. Only the
     * nodes that actually contribute to the result are visited (e.g. a single satisfying relation,
     * or only the children an OR-decomposition needed), so this stays cheap even for interactive use.
     */
    public CoverageReason explain(TreeNode<?> node);
}
