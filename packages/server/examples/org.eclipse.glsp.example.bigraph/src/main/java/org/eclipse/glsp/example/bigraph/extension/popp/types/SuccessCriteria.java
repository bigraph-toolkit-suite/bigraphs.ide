package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class SuccessCriteria extends TreeNode<SuccessCriteria> {
    public SuccessCriteria(String description, double x, double y) {
        super(description, x, y);
    }

    public SuccessCriteria(String id, String description, double x, double y) {
        super(id, description, x, y);
    }

    @Override
    protected SuccessCriteria self() {
        return this;
    }
}
