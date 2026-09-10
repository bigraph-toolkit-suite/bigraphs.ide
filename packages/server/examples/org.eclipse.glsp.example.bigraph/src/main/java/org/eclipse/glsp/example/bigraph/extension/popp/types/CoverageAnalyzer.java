package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Computes and explains coverage over a {@link RelationGraph}, without caching results. */
public final class CoverageAnalyzer {

    /** A neighbor reachable via a coverage-propagating relation, and the relation type used to reach it. */
    private record CoverageCandidate(RelationType type, TreeNode<?> node) {
    }

    private final RelationGraph relations;

    public CoverageAnalyzer(RelationGraph relations) {
        this.relations = relations;
    }

    private List<CoverageCandidate> coverageCandidates(TreeNode<?> node) {
        List<CoverageCandidate> candidates = new ArrayList<>();
        for (Relation relation : relations.incoming(node)) {
            if (relation.type().getCoveragePropagation() == RelationType.CoveragePropagation.FORWARD) {
                candidates.add(new CoverageCandidate(relation.type(), relation.source()));
            }
        }
        for (Relation relation : relations.outgoing(node)) {
            if (relation.type().getCoveragePropagation() == RelationType.CoveragePropagation.BACKWARD) {
                candidates.add(new CoverageCandidate(relation.type(), relation.target()));
            }
        }
        return candidates;
    }

    public boolean isCovered(TreeNode<?> node) {
        return isCovered(node, new HashSet<>());
    }

    private boolean isCovered(TreeNode<?> node, Set<TreeNode<?>> visiting) {
        if (!visiting.add(node)) {
            throw new IllegalStateException("Cyclic coverage dependency detected at " + node);
        }
        try {
            if (node.isExplicitlyCovered()) {
                return true;
            }
            DecompositionType type = node.getDecompositionType();
            List<? extends TreeNode<?>> children = node.getChildren();
            if (!children.isEmpty()) {
                if (type.isSatisfied(children, child -> isCovered(child, visiting))) {
                    return true;
                }
            }
            return coverageCandidates(node).stream().anyMatch(candidate -> isCovered(candidate.node(), visiting));
        } finally {
            visiting.remove(node);
        }
    }

    /**
     * Explains why {@code node} is (or isn't) covered as a small, on-demand reason tree. Only the
     * nodes that actually contribute to the result are visited (e.g. a single satisfying relation,
     * or only the children an OR-decomposition needed), so this stays cheap even for interactive use.
     */
    public CoverageReason explain(TreeNode<?> node) {
        if (node.isExplicitlyCovered()) {
            return new CoverageReason.Explicit(node);
        }
        DecompositionType type = node.getDecompositionType();
        List<? extends TreeNode<?>> children = node.getChildren();
        if (!children.isEmpty()) {
            List<? extends TreeNode<?>> contributing = type.contributingChildren(children, this::isCovered);
            if (type.isSatisfied(children, this::isCovered)) {
                List<CoverageReason> reasons = contributing.stream().map(this::explain).toList();
                return new CoverageReason.Decomposition(node, type, reasons);
            }
        }
        for (CoverageCandidate candidate : coverageCandidates(node)) {
            if (isCovered(candidate.node())) {
                return new CoverageReason.Link(node, candidate.type(), candidate.node(), explain(candidate.node()));
            }
        }
        return new CoverageReason.NotCovered(node);
    }
}

