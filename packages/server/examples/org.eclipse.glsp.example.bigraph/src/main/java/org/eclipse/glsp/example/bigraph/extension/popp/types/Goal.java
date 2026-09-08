package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class Goal extends TreeNode<Goal> {
    public Goal() {
        super();
    }

    public Goal(String id) {
        super(id);
    }

    @Override
    protected Goal self() {
        return this;
    }
}
