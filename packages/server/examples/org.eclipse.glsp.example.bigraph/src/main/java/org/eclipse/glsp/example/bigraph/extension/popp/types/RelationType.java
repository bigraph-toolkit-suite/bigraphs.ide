package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.Set;

/** Semantic edge kinds between POPP tree nodes, independent of the tree hierarchy. */
public enum RelationType {
    /** Solution to SuccessProof, produced once the solution is tested. */
    PRODUCES(CoveragePropagation.FORWARD, new NodeTypePair(NodeKind.SOLUTION, NodeKind.SUCCESS_PROOF)),
    /** SuccessProof to SuccessCriteria, the proof confirms the criterion. */
    VALIDATES(CoveragePropagation.FORWARD, new NodeTypePair(NodeKind.SUCCESS_PROOF, NodeKind.SUCCESS_CRITERIA)),
    /** Problem to Consequence (the problem causes it), and Goal to SuccessCriteria (achieving the goal causes it). */
    CAUSES(CoveragePropagation.BACKWARD,
        new NodeTypePair(NodeKind.PROBLEM, NodeKind.CONSEQUENCE), new NodeTypePair(NodeKind.GOAL, NodeKind.SUCCESS_CRITERIA)),
    /** Problem to Goal: the goal is the problem inverted into a desired state. */
    INVERTS(CoveragePropagation.BACKWARD,
        new NodeTypePair(NodeKind.PROBLEM, NodeKind.GOAL)),
    /** Solution to Goal: the solution is a means to the goal. Provenance only, does not propagate coverage. */
    REALIZES(CoveragePropagation.NONE, new NodeTypePair(NodeKind.SOLUTION, NodeKind.GOAL));


    /**
     * Direction coverage flows, relative to the stored {@code source -> target}: FORWARD covers the target from the
     * source, BACKWARD the source from the target, NONE neither.
     */
    public enum CoveragePropagation {
        NONE, FORWARD, BACKWARD;

        public boolean hasNoCoveragePropagation() {
            return this == NONE;
        }
    }

    /** An ordered pair of node kinds a relation type canonically applies to, as {@code from -> to}. */
    public record NodeTypePair(NodeKind from, NodeKind to) {
    }

    private final CoveragePropagation coveragePropagation;
    private final Set<NodeTypePair> supportedNodeTypePair;

    RelationType(CoveragePropagation coveragePropagation, NodeTypePair... supportedNodeTypePair) {
        this.coveragePropagation = coveragePropagation;
        this.supportedNodeTypePair = Set.of(supportedNodeTypePair);
    }

    public CoveragePropagation getCoveragePropagation() {
        return coveragePropagation;
    }

    /** The endpoint kind pairs this relation type canonically applies to, as {@code from -> to}. */
    public Set<NodeTypePair> getSupportedNodeTypePair() {
        return supportedNodeTypePair;
    }
}

