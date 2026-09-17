package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class Solution extends TreeNode<Solution> {
    public Solution(String description, double x, double y) {
        super(description, x, y);
    }

    public Solution(String id, String description, double x, double y) {
        super(id, description, x, y);
    }

    @Override
    protected Solution self() {
        return this;
    }
}
