package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.Set;

/** Semantic edge kinds between POPP tree nodes, independent of the tree hierarchy. */
public enum RelationType {
    /** Problem to Consequence, and Goal to SuccessCriteria: achieving the goal causes the criterion. */
    CAUSES(CoveragePropagation.BACKWARD,
        new NodeTypePair(NodeKind.PROBLEM, NodeKind.CONSEQUENCE), new NodeTypePair(NodeKind.GOAL, NodeKind.SUCCESS_CRITERIA)),
    /** Problem to Goal: the derived, inverted counterpart. */
    INVERTS(CoveragePropagation.BACKWARD,
        new NodeTypePair(NodeKind.PROBLEM, NodeKind.GOAL)),
    /** Solution to Goal, the goal means relationship; provenance only, does not imply coverage. */
    REALIZES(CoveragePropagation.NONE, new NodeTypePair(NodeKind.SOLUTION, NodeKind.GOAL)),
    /** Solution to SuccessProof, produced once the solution is tested. */
    PRODUCES(CoveragePropagation.FORWARD, new NodeTypePair(NodeKind.SOLUTION, NodeKind.SUCCESS_PROOF)),
    /** SuccessProof to SuccessCriteria, the proof confirms the criterion. */
    VALIDATES(CoveragePropagation.FORWARD, new NodeTypePair(NodeKind.SUCCESS_PROOF, NodeKind.SUCCESS_CRITERIA));

    /** Direction coverage flows relative to how a relation is stored as source to target. */
    public enum CoveragePropagation {
        NONE, FORWARD, BACKWARD
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

