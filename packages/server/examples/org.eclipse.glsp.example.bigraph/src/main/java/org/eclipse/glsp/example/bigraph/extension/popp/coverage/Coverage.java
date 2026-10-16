package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

/** Ordered UNCOVERED &lt; PARTIAL &lt; COVERED, so {@link #best} is just a max. */
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
        return !CoverageGap.remediesFor(node.getKind()).isEmpty();
    }
}
