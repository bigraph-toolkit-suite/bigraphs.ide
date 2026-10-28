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

    public static Coverage best(Coverage a, Coverage b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * Whether a node of this kind can be covered through a relation at all, i.e. whether the metamodel has a
     * propagating relation towards its kind. False for consequences (never covered) and for solutions (always
     * covered by themselves), so both are left out of reports and gap lists.
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
