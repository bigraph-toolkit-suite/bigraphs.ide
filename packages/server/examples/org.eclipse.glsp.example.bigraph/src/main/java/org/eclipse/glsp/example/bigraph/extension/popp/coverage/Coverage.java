package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/** Ordered {@code UNCOVERED < PARTIAL < COVERED}, so {@link #best} is just a max. */
public enum Coverage {
    UNCOVERED,
    PARTIAL,
    COVERED;

    public static Coverage best(Coverage first, Coverage second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    /**
     * Whether a node of this kind can be covered through a relation at all, i.e. whether the metamodel has a
     * propagating kind pair towards its kind. False only for solutions (always covered by themselves), so they are
     * left out of reports and gap lists. Consequences are coverable, through the problems that cause them.
     */
    public static boolean isCoverable(TreeNode<?> node) {
        return isCoverable(node.getKind());
    }

    /** See {@link #isCoverable(TreeNode)}. */
    public static boolean isCoverable(NodeKind kind) {
        return !CoverageGap.remediesFor(kind).isEmpty();
    }

    /** The kinds {@link #isCoverable(NodeKind)} holds for: problems, goals, success criteria and success proofs. */
    public static final Set<NodeKind> COVERABLE_KINDS = Set.copyOf(Arrays.stream(NodeKind.values())
            .filter(Coverage::isCoverable).collect(() -> EnumSet.noneOf(NodeKind.class), Set::add, Set::addAll));
}
