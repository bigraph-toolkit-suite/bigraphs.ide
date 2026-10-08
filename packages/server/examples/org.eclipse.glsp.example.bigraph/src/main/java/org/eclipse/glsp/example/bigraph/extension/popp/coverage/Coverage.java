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

    /** Whether a node of this kind has anything to be covered by. */
    public static boolean isCoverable(TreeNode<?> node) {
        return !CoverageGap.remediesFor(node.getKind()).isEmpty();
    }
}
