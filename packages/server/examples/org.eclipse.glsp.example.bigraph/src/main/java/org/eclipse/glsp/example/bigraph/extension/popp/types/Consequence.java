package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class Consequence extends TreeNode<Consequence> {
    Consequence(String description, double x, double y) {
        super(NodeKind.CONSEQUENCE, description, x, y);
    }

    Consequence(String id, String description, double x, double y) {
        super(NodeKind.CONSEQUENCE, id, description, x, y);
    }

    @Override
    protected Consequence self() {
        return this;
    }
}
