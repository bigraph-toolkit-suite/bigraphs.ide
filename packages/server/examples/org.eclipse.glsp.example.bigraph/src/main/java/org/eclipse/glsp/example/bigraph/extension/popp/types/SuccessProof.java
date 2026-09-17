package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class SuccessProof extends TreeNode<SuccessProof> {
    public SuccessProof(String description, double x, double y) {
        super(description, x, y);
        setExplicitlyCovered(true);
    }

    public SuccessProof(String id, String description, double x, double y) {
        super(id, description, x, y);
        setExplicitlyCovered(true);
    }

    @Override
    protected SuccessProof self() {
        return this;
    }
}
