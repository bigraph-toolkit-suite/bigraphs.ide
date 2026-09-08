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

    /** A problem is solved once at least one of its linked goals is achieved. */
    public boolean addGoal(Goal goal) {
        return addCoverageLink(goal);
    }

    public boolean removeGoal(Goal goal) {
        return removeCoverageLink(goal);
    }
}
