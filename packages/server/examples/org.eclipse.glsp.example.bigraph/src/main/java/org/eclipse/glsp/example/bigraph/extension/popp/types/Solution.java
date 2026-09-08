package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class Solution extends TreeNode<Solution> {
    public Solution() {
        super();
    }

    public Solution(String id) {
        super(id);
    }

    @Override
    protected Solution self() {
        return this;
    }
}
