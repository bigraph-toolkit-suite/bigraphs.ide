package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Relation;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.List;

/** Result of {@link CoverageSession#explain}: a reason tree that follows the evidence down to its end. */
public sealed interface CoverageReason {
    TreeNode<?> node();
    Coverage coverage();

    /** Covered by its kind alone, i.e. its kind is one of the mode's emitters ({@link CoverageMode#emitters()}). Always COVERED. */
    record Intrinsic(TreeNode<?> node, Coverage coverage) implements CoverageReason {
    }

    /**
     * AND/OR decomposition. When COVERED, {@code children} are only the contributing ones. Otherwise all
     * children are listed so the gaps underneath stay visible.
     */
    record Decomposition(TreeNode<?> node, Coverage coverage, DecompositionType type, List<CoverageReason> children)
            implements CoverageReason {
    }

    /**
     * Covered (or not) through a propagating relation. {@code coveringReason} explains the giving neighbour. When the
     * node is not covered at all, this is the first candidate relation, so the explanation still leads somewhere.
     */
    record Link(TreeNode<?> node, Coverage coverage, Relation relation, CoverageReason coveringReason)
            implements CoverageReason {
        public RelationType type() {
            return relation.type();
        }

        public TreeNode<?> coveringNode() {
            return coveringReason.node();
        }
    }

    /**
     * Nothing to follow: no children and no propagating relation. {@code gaps} lists the remedies, i.e. the relations
     * that could cover the node (empty for kinds that cannot be covered).
     */
    record NotCovered(TreeNode<?> node, List<CoverageGap.CoverageRemedy> gaps) implements CoverageReason {
        @Override
        public Coverage coverage() {
            return Coverage.UNCOVERED;
        }
    }
}

