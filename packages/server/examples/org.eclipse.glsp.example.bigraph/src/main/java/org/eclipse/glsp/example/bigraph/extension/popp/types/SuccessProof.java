package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class SuccessProof extends TreeNode<SuccessProof> {
    public SuccessProof() {
        super();
        setCovered(true);
    }

    public SuccessProof(String id) {
        super(id);
    }

    @Override
    protected SuccessProof self() {
        return this;
    }
}
