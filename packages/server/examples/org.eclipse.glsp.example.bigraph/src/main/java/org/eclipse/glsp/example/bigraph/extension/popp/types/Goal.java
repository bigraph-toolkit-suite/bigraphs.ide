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

    /** A goal is achieved once at least one of its linked solutions is implemented (goal-means relationship). */
    public boolean addSolution(Solution solution) {
        return addCoverageLink(solution);
    }

    public boolean removeSolution(Solution solution) {
        return removeCoverageLink(solution);
    }
}
