package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class SuccessProof extends TreeNode<SuccessProof> {
    SuccessProof(String description, double x, double y) {
        super(NodeKind.SUCCESS_PROOF, description, x, y);
    }

    SuccessProof(String id, String description, double x, double y) {
        super(NodeKind.SUCCESS_PROOF, id, description, x, y);
    }

    @Override
    protected SuccessProof self() {
        return this;
    }
}
