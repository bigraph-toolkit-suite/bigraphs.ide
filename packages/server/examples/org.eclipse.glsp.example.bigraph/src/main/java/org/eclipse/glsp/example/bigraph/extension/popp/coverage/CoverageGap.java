package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A node that is not COVERED.
 *
 * @param node      the node
 * @param coverage  UNCOVERED or PARTIAL
 * @param rootCause true if no child and no candidate relation partner of the node is itself not covered. For a node
 *                  that is not covered this means it has neither: nothing below or behind it has to be fixed first,
 *                  so this is where to act
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
     * One way a node could still become covered: a missing neighbour of {@code missingKind} connected via {@code type},
     * as the relation's source if {@code missingIsSource}.
     */
    public record CoverageRemedy(RelationType type, NodeKind missingKind, boolean missingIsSource) {
        /** E.g. "needs a success proof (validates)". */
        public String describe() {
            return "needs a " + missingKind.getName() + " (" + type.name().toLowerCase() + ")";
        }
    }

    /** All the ways a node of the given kind could be covered through a relation, derived from the metamodel. */
    public static List<CoverageGap.CoverageRemedy> remediesFor(NodeKind kind) {
        List<CoverageGap.CoverageRemedy> remedies = new ArrayList<>();
        for (RelationType type : RelationType.values()) {
            for (RelationType.NodeTypePair pair : type.getSupportedNodeTypePair()) {
                switch (type.getCoveragePropagation()) {
                    case FORWARD -> {
                        if (pair.to().equals(kind)) {
                            remedies.add(new CoverageGap.CoverageRemedy(type, pair.from(), true));
                        }
                    }
                    case BACKWARD -> {
                        if (pair.from().equals(kind)) {
                            remedies.add(new CoverageGap.CoverageRemedy(type, pair.to(), false));
                        }
                    }
                    default -> {}
                }
            }
        }
        return remedies;
    }
}
