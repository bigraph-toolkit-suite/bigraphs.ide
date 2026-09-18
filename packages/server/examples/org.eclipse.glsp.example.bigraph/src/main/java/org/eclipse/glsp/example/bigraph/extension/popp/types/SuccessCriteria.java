package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class SuccessCriteria extends TreeNode<SuccessCriteria> {
    SuccessCriteria(String description, double x, double y) {
        super(NodeKind.SUCCESS_CRITERIA, description, x, y);
    }

    SuccessCriteria(String id, String description, double x, double y) {
        super(NodeKind.SUCCESS_CRITERIA, id, description, x, y);
    }

    @Override
    protected SuccessCriteria self() {
        return this;
    }
}
