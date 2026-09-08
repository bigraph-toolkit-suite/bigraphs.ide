package org.eclipse.glsp.example.bigraph.extension.popp.types;

public enum DecompositionType {
    NONE(false),
    AND(true),
    OR(false);

    private final boolean requiresAllChildrenCovered;

    private DecompositionType(boolean requiresAllChildrenCovered){
        this.requiresAllChildrenCovered = requiresAllChildrenCovered;
    }

    public boolean requiresAllChildrenCovered() {
        return requiresAllChildrenCovered;
    }
}
