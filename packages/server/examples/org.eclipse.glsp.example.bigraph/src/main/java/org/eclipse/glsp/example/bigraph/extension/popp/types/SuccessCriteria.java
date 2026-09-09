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
}
