package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class Problem extends TreeNode<Problem> {
    Problem(String description, double x, double y) {
        super(NodeKind.PROBLEM, description, x, y);
    }

    Problem(String id, String description, double x, double y) {
        super(NodeKind.PROBLEM, id, description, x, y);
    }

    @Override
    protected Problem self() {
        return this;
    }
}
