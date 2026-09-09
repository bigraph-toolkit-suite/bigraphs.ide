package org.eclipse.glsp.example.bigraph.extension.popp.types;

/** Outcome of a {@link RelationGraph#relate}/{@link RelationGraph#unrelate} attempt. */
public enum RelationResult {
    CREATED,
    REMOVED,
    /** {@code relate} was a no-op: that exact relation already exists. */
    ALREADY_EXISTS,
    /** {@code unrelate} was a no-op: no such relation exists. */
    NOT_FOUND,
    NULL_ARGUMENT,
    SAME_NODE,
    /** Neither ordering of the two node kinds matches a relation type the POPP metamodel allows. */
    NO_SUCH_RELATION_FOR_KINDS;

    public boolean isSuccess() {
        return this == CREATED || this == REMOVED;
    }
}
