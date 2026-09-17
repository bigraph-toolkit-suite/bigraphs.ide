package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class Goal extends TreeNode<Goal> {
    public Goal(String description, double x, double y) {
        super(description, x, y);
    }

    public Goal(String id, String description, double x, double y) {
        super(id, description, x, y);
    }

    @Override
    protected Goal self() {
        return this;
    }
}
