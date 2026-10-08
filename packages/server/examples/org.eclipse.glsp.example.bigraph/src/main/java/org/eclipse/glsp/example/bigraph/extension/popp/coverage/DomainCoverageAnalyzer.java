package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.*;

/**
 * Three-valued coverage over a {@link RelationGraph}. Stateless; all memoization lives in the
 * short-lived {@link CoverageSession}.
 */
public final class DomainCoverageAnalyzer implements CoverageAnalyzer {

    private final RelationGraph relations;

    public DomainCoverageAnalyzer(RelationGraph relations) {
        this.relations = relations;
    }

    @Override
    public CoverageSession session(CoverageMode mode) {
        return new Session(mode, relations);
    }

    private final class Session extends AbstractCoverageSession {
        private final CoverageMode mode;
        private final Map<TreeNode<?>, Coverage> memo = new HashMap<>();
        private final Set<TreeNode<?>> visiting = new HashSet<>();

        Session(CoverageMode mode, RelationGraph relations) {
            super(mode, relations);
            this.mode = mode;
        }

        private boolean isIntrinsicallyCovered(TreeNode<?> node) {
            return node.getKind().isIntrinsicallyCovered(mode);
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
                return decompositionReason(node, decomposition);
            }
            if (best != null) {
                return linkReason(node, bestCoverage, best.relation(), best.node());
            }
            return new CoverageReason.NotCovered(node, CoverageGap.remediesFor(node.getKind()));
        }
    }
}