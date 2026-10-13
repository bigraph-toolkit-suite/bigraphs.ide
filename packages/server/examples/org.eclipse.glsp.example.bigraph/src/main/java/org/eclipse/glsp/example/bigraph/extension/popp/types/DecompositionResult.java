package org.eclipse.glsp.example.bigraph.extension.popp.types;

/** Outcome of {@link POPPModel#decompose}, the decomposition counterpart of {@link RelationResult}. */
public enum DecompositionResult {
    ATTACHED,
    NULL_ARGUMENT,
    /** Parent and child are of different kinds: trees are homogeneous, so this would be a relation, not a decomposition. */
    KIND_MISMATCH,
    /** Same kind, but {@link TreeNode#addChild} refused: the same node, already a child, or the edge would close a cycle. */
    REJECTED;

    public boolean isSuccess() {
        return this == ATTACHED;
    }
}
