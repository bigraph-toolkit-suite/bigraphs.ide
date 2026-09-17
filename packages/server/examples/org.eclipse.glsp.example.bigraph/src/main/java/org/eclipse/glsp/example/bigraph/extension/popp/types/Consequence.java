package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class Consequence extends TreeNode<Consequence> {
    public Consequence(String description, double x, double y) {
        super(description, x, y);
    }

    public Consequence(String id, String description, double x, double y) {
        super(id, description, x, y);
    }

    @Override
    protected Consequence self() {
        return this;
    }
}
