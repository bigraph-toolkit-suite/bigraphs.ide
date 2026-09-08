package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class SuccessCriteria extends TreeNode<SuccessCriteria> {
    public SuccessCriteria() {
        super();
    }

    public SuccessCriteria(String id) {
        super(id);
    }

    @Override
    protected SuccessCriteria self() {
        return this;
    }

    /** A success criteria is met once at least one of its linked proofs confirms it. */
    public boolean addSuccessProof(SuccessProof proof) {
        return addCoverageLink(proof);
    }

    public boolean removeSuccessProof(SuccessProof proof) {
        return removeCoverageLink(proof);
    }
}
