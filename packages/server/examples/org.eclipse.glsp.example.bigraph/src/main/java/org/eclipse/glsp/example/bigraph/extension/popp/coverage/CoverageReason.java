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

    /** Inherently covered kind (solution, criteria); COVERED unless it is only planned in VERIFY mode. */
    record Intrinsic(TreeNode<?> node, Coverage coverage) implements CoverageReason {
    }

    /**
     * AND/OR decomposition. When COVERED, {@code children} are only the contributing ones; otherwise all
     * children are listed so the gaps underneath stay visible.
     */
    record Decomposition(TreeNode<?> node, Coverage coverage, DecompositionType type, List<CoverageReason> children)
            implements CoverageReason {
    }

    /** Best propagating relation to a neighbour; {@code coveringReason} explains that neighbour. */
    record Link(TreeNode<?> node, Coverage coverage, Relation relation, CoverageReason coveringReason)
            implements CoverageReason {
        public RelationType type() {
            return relation.type();
        }

        public TreeNode<?> coveringNode() {
            return coveringReason.node();
        }
    }

    /** Nothing to follow: no children and no propagating relation. {@code gaps} says what could fix that. */
    record NotCovered(TreeNode<?> node, List<CoverageGap> gaps) implements CoverageReason {
        @Override
        public Coverage coverage() {
            return Coverage.UNCOVERED;
        }
    }
}

