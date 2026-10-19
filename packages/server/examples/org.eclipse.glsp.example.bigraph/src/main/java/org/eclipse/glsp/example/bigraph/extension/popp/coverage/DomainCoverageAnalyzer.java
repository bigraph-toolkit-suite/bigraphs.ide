package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.*;

/**
 * Three-valued coverage computed directly on the domain model (trees and {@link RelationGraph}), without the bigraph.
 * The analyzer keeps no results; memoization lives in the {@link CoverageSession}. A node's coverage is the best of its
 * decomposition and its candidate relations; on a tie the explanation prefers the decomposition. The metamodel's
 * relations cannot form a dependency cycle, so the cycle check is only a safeguard.
 */
public final class DomainCoverageAnalyzer implements CoverageAnalyzer {

    private final CoverageInput input;

    public DomainCoverageAnalyzer(CoverageInput input) {
        this.input = input;
    }

    @Override
    public CoverageSession session(CoverageMode mode) {
        POPPModel model = input.model();
        if (model == null) throw new IllegalStateException("No POPP model loaded");
        return new Session(mode, model.getRelations());
    }

    private final class Session extends AbstractCoverageSession {
        /** Coverage of every node computed so far in this session. */
        private final Map<TreeNode<?>, Coverage> memo = new HashMap<>();
        private final Set<TreeNode<?>> visiting = new HashSet<>();

        Session(CoverageMode mode, RelationGraph relations) {
            super(mode, relations);
        }

        private boolean isIntrinsicallyCovered(TreeNode<?> node) {
            return mode.isIntrinsic(node.getKind());
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