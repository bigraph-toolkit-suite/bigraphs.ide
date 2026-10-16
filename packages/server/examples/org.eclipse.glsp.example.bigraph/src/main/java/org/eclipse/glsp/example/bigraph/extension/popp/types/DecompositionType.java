package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.List;
import java.util.function.Predicate;

/**
 * How a node's children combine: all of them (AND), any of them (OR), or {@code NONE} while the node has no children.
 * The three-valued aggregation is {@code AbstractCoverageSession#aggregate}.
 */
public enum DecompositionType {
    NONE {
        @Override
        public List<? extends TreeNode<?>> contributingChildren(List<? extends TreeNode<?>> children,
                Predicate<TreeNode<?>> isCovered) {
            return List.of();
        }
    },
    AND {
        @Override
        public List<? extends TreeNode<?>> contributingChildren(List<? extends TreeNode<?>> children,
                Predicate<TreeNode<?>> isCovered) {
            return children;
        }
    },
    OR {
        @Override
        public List<? extends TreeNode<?>> contributingChildren(List<? extends TreeNode<?>> children,
                Predicate<TreeNode<?>> isCovered) {
            return children.stream().filter(isCovered).toList();
        }
    };

    /** The children a COVERED decomposition is explained by: all for AND, the covered ones for OR, none for NONE. */
    public abstract List<? extends TreeNode<?>> contributingChildren(List<? extends TreeNode<?>> children,
            Predicate<TreeNode<?>> isCovered);
}
