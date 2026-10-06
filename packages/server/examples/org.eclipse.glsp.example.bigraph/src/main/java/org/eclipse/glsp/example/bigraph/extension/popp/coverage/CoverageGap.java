package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.List;

/**
 * @param rootCause true if nothing is left that could still contribute coverage (no children, no
 *                  candidate links that are themselves uncovered): this is the actionable part
 * @param missing   the ways this node could be covered; only filled for root causes
 */
public record CoverageGap(TreeNode<?> node, Coverage coverage, boolean rootCause, List<CoverageGap.CoverageRemedy> missing) {
    /**
     * Everything a "highlight coverage gaps" action needs, computed from one {@link CoverageSession}.
     */
    public record CoverageGaps(List<CoverageGap> gaps) {
        public List<CoverageGap> rootCauses() {
            return gaps.stream().filter(CoverageGap::rootCause).toList();
        }

        /**
         * Non-root gaps: nodes that are UNCOVERED/PARTIAL only because something beneath or behind them is.
         */
        public List<CoverageGap> affected() {
            return gaps.stream().filter(g -> !g.rootCause()).toList();
        }

        public boolean isEmpty() {
            return gaps.isEmpty();
        }
    }

    /**
     * One way a node could still become covered: a missing neighbour of {@code missingKind} connected via {@code type}.
     */
    public record CoverageRemedy(RelationType type, NodeKind missingKind, boolean missingIsSource) {
        public String describe() {
            return "needs a " + missingKind.getName() + " (" + type.name().toLowerCase() + ")";
        }
    }
}
