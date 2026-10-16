package org.eclipse.glsp.example.bigraph.extension.popp.types;

/** Outcome of {@link POPPModel#decompose}, the decomposition counterpart of {@link RelationResult}. */
public enum DecompositionResult {
    ATTACHED,
    NULL_ARGUMENT,
    /** Parent and child are of different kinds: trees are homogeneous, so this would be a relation, not a decomposition. */
    KIND_MISMATCH,
    /**
     * Same kind, but {@link TreeNode#addChild} refused: the same node, a child or other descendant already, or an
     * ancestor other than the direct parent (a cycle). The direct parent is not refused: the two are flipped.
     */
    REJECTED;

    public boolean isSuccess() {
        return this == ATTACHED;
    }
}
