package org.eclipse.glsp.example.bigraph.extension.popp.types;

/** Semantic edge kinds between POPP tree nodes, independent of the tree hierarchy. */
public enum RelationType {
    /** Problem to Consequence, and Goal to SuccessCriteria: achieving the goal causes the criterion. */
    CAUSES(CoveragePropagation.BACKWARD),
    /** Problem to Goal, and Consequence to SuccessCriteria: the derived, inverted counterpart. */
    INVERTS(CoveragePropagation.BACKWARD),
    /** Solution to Goal, the goal means relationship; provenance only, does not imply coverage. */
    REALIZES(CoveragePropagation.NONE),
    /** Solution to SuccessProof, produced once the solution is tested; provenance only. */
    PRODUCES(CoveragePropagation.NONE),
    /** SuccessProof to SuccessCriteria, the proof confirms the criterion. */
    VALIDATES(CoveragePropagation.FORWARD);

    /** Direction coverage flows relative to how a relation is stored as source to target. */
    public enum CoveragePropagation {
        NONE, FORWARD, BACKWARD
    }

    private final CoveragePropagation coveragePropagation;

    RelationType(CoveragePropagation coveragePropagation) {
        this.coveragePropagation = coveragePropagation;
    }

    public CoveragePropagation getCoveragePropagation() {
        return coveragePropagation;
    }
}

