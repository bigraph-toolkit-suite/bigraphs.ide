package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Semantic edge kinds between POPP tree nodes, independent of the tree hierarchy. A relation type lists the node kind
 * pairs it connects, and each pair states how coverage propagates over it: the same type can propagate differently for
 * different pairs, e.g. {@link #CAUSES} covers a consequence from its problem, but a goal from its success criterion.
 * The pairs of all types are disjoint, so a pair of node kinds determines both the type and the propagation.
 */
public enum RelationType {
    /** Solution to SuccessProof, produced once the solution is tested. */
    PRODUCES(supports(NodeKind.SOLUTION, NodeKind.SUCCESS_PROOF, CoveragePropagation.FORWARD)),
    /** SuccessProof to SuccessCriteria, the proof confirms the criterion. */
    VALIDATES(supports(NodeKind.SUCCESS_PROOF, NodeKind.SUCCESS_CRITERIA, CoveragePropagation.FORWARD)),
    /**
     * Problem to Consequence (the problem causes it, and once the problem is solved, so is its consequence), and Goal
     * to SuccessCriteria (achieving the goal causes it, and a met criterion shows that the goal is achieved).
     */
    CAUSES(supports(NodeKind.PROBLEM, NodeKind.CONSEQUENCE, CoveragePropagation.FORWARD),
        supports(NodeKind.GOAL, NodeKind.SUCCESS_CRITERIA, CoveragePropagation.BACKWARD)),
    /**
     * Problem to Goal (the goal is the problem inverted into a desired state, and an achieved goal solves the problem),
     * and Consequence to SuccessCriteria (the criterion is the consequence inverted into an observable state, which is
     * provenance only and does not propagate coverage).
     */
    INVERTS(supports(NodeKind.PROBLEM, NodeKind.GOAL, CoveragePropagation.BACKWARD),
        supports(NodeKind.CONSEQUENCE, NodeKind.SUCCESS_CRITERIA, CoveragePropagation.NONE)),
    /** Solution to Goal: the solution is a means to the goal. Provenance only, does not propagate coverage. */
    REALIZES(supports(NodeKind.SOLUTION, NodeKind.GOAL, CoveragePropagation.NONE));


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

    /** A node kind pair a relation type supports, together with the coverage propagation over it. */
    private record SupportedPair(NodeTypePair kinds, CoveragePropagation propagation) {
    }

    private static SupportedPair supports(NodeKind from, NodeKind to, CoveragePropagation propagation) {
        return new SupportedPair(new NodeTypePair(from, to), propagation);
    }

    /** In declaration order, so everything derived from the pairs (e.g. the coverage rules) has a stable order. */
    private final Map<NodeTypePair, CoveragePropagation> propagationByPair;

    RelationType(SupportedPair... supportedPairs) {
        Map<NodeTypePair, CoveragePropagation> pairs = new LinkedHashMap<>();
        for (SupportedPair supported : supportedPairs) pairs.put(supported.kinds(), supported.propagation());
        this.propagationByPair = Collections.unmodifiableMap(pairs);
    }

    /** The endpoint kind pairs this relation type canonically applies to, as {@code from -> to}, in declaration order. */
    public Set<NodeTypePair> getSupportedNodeTypePair() {
        return propagationByPair.keySet();
    }

    /**
     * How coverage propagates over a relation of this type from a {@code pair.from()} node to a {@code pair.to()} node.
     *
     * @throws IllegalArgumentException if this type does not support the pair
     */
    public CoveragePropagation getCoveragePropagation(NodeTypePair pair) {
        CoveragePropagation propagation = propagationByPair.get(pair);
        if (propagation == null) {
            throw new IllegalArgumentException(name() + " does not connect " + pair.from() + " to " + pair.to());
        }
        return propagation;
    }

    /** How coverage propagates over a relation of this type from a {@code from} node to a {@code to} node. */
    public CoveragePropagation getCoveragePropagation(NodeKind from, NodeKind to) {
        return getCoveragePropagation(new NodeTypePair(from, to));
    }
}
