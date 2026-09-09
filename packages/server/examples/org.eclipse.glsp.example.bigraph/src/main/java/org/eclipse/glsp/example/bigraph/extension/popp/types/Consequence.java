package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class Consequence extends TreeNode<Consequence> {
    public Consequence() {
        super();
    }

    public Consequence(String id) {
        super(id);
    }

    @Override
    protected Consequence self() {
        return this;
    }
}
