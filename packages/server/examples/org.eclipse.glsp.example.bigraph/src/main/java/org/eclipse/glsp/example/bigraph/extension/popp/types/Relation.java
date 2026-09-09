package org.eclipse.glsp.example.bigraph.extension.popp.types;

/** A single typed, directed edge between two POPP nodes, e.g. a proof validating a success criterion. */
public record Relation(TreeNode<?> source, RelationType type, TreeNode<?> target) {
    public Relation {
        if (source == null || type == null || target == null) {
            throw new IllegalArgumentException("source, type and target must not be null");
        }
        if (source == target) {
            throw new IllegalArgumentException("source and target must differ");
        }
    }
}
