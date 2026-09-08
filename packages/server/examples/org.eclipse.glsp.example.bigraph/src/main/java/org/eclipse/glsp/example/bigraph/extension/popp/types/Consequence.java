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

    /** A consequence is resolved once at least one of its linked success criteria is met. */
    public boolean addSuccessCriteria(SuccessCriteria criteria) {
        return addCoverageLink(criteria);
    }

    public boolean removeSuccessCriteria(SuccessCriteria criteria) {
        return removeCoverageLink(criteria);
    }
}
