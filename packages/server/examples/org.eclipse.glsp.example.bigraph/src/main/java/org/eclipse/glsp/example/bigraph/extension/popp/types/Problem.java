package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class Problem extends TreeNode<Problem> {
    public Problem() {
        super();
    }

    public Problem(String id) {
        super(id);
    }

    @Override
    protected Problem self() {
        return this;
    }
}
