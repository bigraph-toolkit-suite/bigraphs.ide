package org.eclipse.glsp.example.bigraph.extension.popp.types;

/** Represents a success criteria of the POPP model */
public final class SuccessCriteria extends TreeNode<SuccessCriteria> {
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
