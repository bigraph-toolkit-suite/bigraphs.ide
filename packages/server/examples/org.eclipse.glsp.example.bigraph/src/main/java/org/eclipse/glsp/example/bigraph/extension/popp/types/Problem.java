package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class Problem extends TreeNode<Problem> {
    public Problem(String description, double x, double y) {
        super(description, x, y);
    }

    public Problem(String id, String description, double x, double y) {
        super(id, description, x, y);
    }

    @Override
    protected Problem self() {
        return this;
    }
}
