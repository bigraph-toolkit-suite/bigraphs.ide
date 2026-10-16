package org.eclipse.glsp.example.bigraph.extension.popp.types;

/** Represents a goal/objective of the POPP model */
public final class Goal extends TreeNode<Goal> {
    Goal(String description, double x, double y) {
        super(NodeKind.GOAL, description, x, y);
    }

    Goal(String id, String description, double x, double y) {
        super(NodeKind.GOAL, id, description, x, y);
    }

    @Override
    protected Goal self() {
        return this;
    }
}
