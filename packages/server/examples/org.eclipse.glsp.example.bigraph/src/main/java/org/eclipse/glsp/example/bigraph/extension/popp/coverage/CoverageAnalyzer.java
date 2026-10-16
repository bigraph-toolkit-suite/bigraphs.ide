package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.Collection;

/** Strategy pattern for switching between implementations or optimizations of the coverage algorithm. */
public interface CoverageAnalyzer {
    /**
     * Evaluates the analyzer's model (from its {@link CoverageInput}) in {@code mode}. The session is a snapshot of
     * that model revision: valid until the next edit, never updated. Every other method here delegates to it.
     *
     * @throws IllegalStateException if no model is loaded
     */
    CoverageSession session(CoverageMode mode);

    /** What is the coverage ({@link Coverage}) of a given node? */
    default Coverage coverage(TreeNode<?> node, CoverageMode mode) {
        return session(mode).coverage(node);
    }

    default boolean isCovered(TreeNode<?> node, CoverageMode mode) {
        return coverage(node, mode) == Coverage.COVERED;
    }

    /** Why is a given node covered (or not)? */
    default CoverageReason explain(TreeNode<?> node, CoverageMode mode) {
        return session(mode).explain(node);
    }

    /** What nodes are lacking coverage? */
    default CoverageGap.CoverageGaps gaps(Collection<? extends TreeNode<?>> scope, CoverageMode mode) {
        return session(mode).gaps(scope);
    }

    /** Gaps among all nodes of {@code model}, which must be the model this analyzer evaluates. */
    default CoverageGap.CoverageGaps gaps(POPPModel model, CoverageMode mode) {
        return session(mode).gaps(model.getAllNodes());
    }
}