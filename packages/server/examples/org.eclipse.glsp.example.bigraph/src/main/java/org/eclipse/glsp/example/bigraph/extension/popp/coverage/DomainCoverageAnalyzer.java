package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.*;
import java.util.function.Predicate;

/**
 * Three-valued coverage over a {@link RelationGraph}. Stateless; all memoization lives in the
 * short-lived {@link CoverageSession}. The dependency graph is acyclic by construction (see MetamodelTest),
 * the visiting guard is only a last-resort tripwire for metamodel changes.
 */
public final class DomainCoverageAnalyzer implements CoverageAnalyzer {

    private record Candidate(Relation relation, TreeNode<?> node) {}

    private final RelationGraph relations;

    public DomainCoverageAnalyzer(RelationGraph relations) {
        this.relations = relations;
    }

    @Override
    public CoverageSession session(CoverageMode mode) {
        return new Session(mode);
    }

    /** Whether a node of this kind has anything to be covered by; Consequence and Solution have no incoming evidence. */
    public static boolean isCoverable(TreeNode<?> node) {
        return !gapsFor(node.getKind()).isEmpty();
    }

    /** All the ways a node of the given kind could be covered through a relation, derived from the metamodel. */
    public static List<CoverageGap> gapsFor(NodeKind kind) {
        List<CoverageGap> gaps = new ArrayList<>();
        for (RelationType type : RelationType.values()) {
            for (RelationType.NodeTypePair pair : type.getSupportedNodeTypePair()) {
                switch (type.getCoveragePropagation()) {
                    case FORWARD -> {
                        if (pair.to().equals(kind)) gaps.add(new CoverageGap(type, pair.from(), true));
                    }
                    case BACKWARD -> {
                        if (pair.from().equals(kind)) gaps.add(new CoverageGap(type, pair.to(), false));
                    }
                    default -> {
                    }
                }
            }
        }
        return gaps;
    }

    private List<Candidate> candidates(TreeNode<?> node) {
        List<Candidate> out = new ArrayList<>();
        for (Relation r : relations.incoming(node)) {
            if (r.type().getCoveragePropagation() == RelationType.CoveragePropagation.FORWARD) {
                out.add(new Candidate(r, r.source()));
            }
        }
        for (Relation r : relations.outgoing(node)) {
            if (r.type().getCoveragePropagation() == RelationType.CoveragePropagation.BACKWARD) {
                out.add(new Candidate(r, r.target()));
            }
        }
        return out;
    }

    private static Coverage aggregate(DecompositionType type, List<Coverage> children) {
        boolean all = children.stream().allMatch(c -> c == Coverage.COVERED);
        boolean any = all || children.stream().anyMatch(c -> c == Coverage.COVERED);
        boolean some = children.stream().anyMatch(c -> c != Coverage.UNCOVERED);
        return switch (type) {
            case AND -> all ? Coverage.COVERED : some ? Coverage.PARTIAL : Coverage.UNCOVERED;
            case OR -> any ? Coverage.COVERED : some ? Coverage.PARTIAL : Coverage.UNCOVERED;
            case NONE -> Coverage.UNCOVERED;
        };
    }

    private final class Session implements CoverageSession {
        private final CoverageMode mode;
        private final Map<TreeNode<?>, Coverage> memo = new HashMap<>();
        private final Set<TreeNode<?>> visiting = new HashSet<>();

        Session(CoverageMode mode) {
            this.mode = mode;
        }

        @Override
        public Coverage coverage(TreeNode<?> node) {
            Coverage cached = memo.get(node);
            if (cached != null) {
                return cached;
            }
            if (!visiting.add(node)) {
                throw new IllegalStateException("Cyclic coverage dependency detected at " + node);
            }
            try {
                Coverage result = compute(node);
                memo.put(node, result);
                return result;
            } finally {
                visiting.remove(node);
            }
        }

        /** Covered with no further evidence: an inherently-evidential kind, or a planned item while in PLANNING mode. */
        private boolean isIntrinsicallyCovered(TreeNode<?> node) {
            return node.isIntrinsicallyCovered()
                    || (mode == CoverageMode.PLANNING && node.getKind().isCoveredWhenPlanned());
        }

        private Coverage compute(TreeNode<?> node) {
            if (isIntrinsicallyCovered(node)) {
                return Coverage.COVERED;
            }
            Coverage result = Coverage.UNCOVERED;
            List<? extends TreeNode<?>> children = node.getChildren();
            if (!children.isEmpty()) {
                result = aggregate(node.getDecompositionType(), children.stream().map(this::coverage).toList());
            }
            for (Candidate candidate : candidates(node)) {
                result = Coverage.best(result, coverage(candidate.node()));
                if (result == Coverage.COVERED) {
                    break;
                }
            }
            return result;
        }

        @Override
        public CoverageReason explain(TreeNode<?> node) {
            if (isIntrinsicallyCovered(node)) {
                return new CoverageReason.Intrinsic(node, Coverage.COVERED);
            }

            List<? extends TreeNode<?>> children = node.getChildren();
            Coverage decomposition = children.isEmpty() ? Coverage.UNCOVERED
                    : aggregate(node.getDecompositionType(), children.stream().map(this::coverage).toList());

            Candidate best = null;
            Coverage bestCoverage = Coverage.UNCOVERED;
            for (Candidate candidate : candidates(node)) {
                Coverage c = coverage(candidate.node());
                if (best == null || c.compareTo(bestCoverage) > 0) {
                    best = candidate;
                    bestCoverage = c;
                }
            }

            if (!children.isEmpty() && decomposition.compareTo(bestCoverage) >= 0) {
                Predicate<TreeNode<?>> isCovered = n -> coverage(n) == Coverage.COVERED;
                List<? extends TreeNode<?>> shown = decomposition == Coverage.COVERED
                        ? node.getDecompositionType().contributingChildren(children, isCovered)
                        : children;
                return new CoverageReason.Decomposition(node, decomposition, node.getDecompositionType(),
                        shown.stream().map(this::explain).toList());
            }
            if (best != null) {
                return new CoverageReason.Link(node, bestCoverage, best.relation(), explain(best.node()));
            }
            return new CoverageReason.NotCovered(node, gapsFor(node.getKind()));
        }
    }
}