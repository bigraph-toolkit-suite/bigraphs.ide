package org.eclipse.glsp.example.bigraph.extension.popp.types;

public class Solution extends TreeNode<Solution> {
    Solution(String description, double x, double y) {
        super(NodeKind.SOLUTION, description, x, y);
    }

    Solution(String id, String description, double x, double y) {
        super(NodeKind.SOLUTION, id, description, x, y);
    }

    @Override
    protected Solution self() {
        return this;
    }
}
