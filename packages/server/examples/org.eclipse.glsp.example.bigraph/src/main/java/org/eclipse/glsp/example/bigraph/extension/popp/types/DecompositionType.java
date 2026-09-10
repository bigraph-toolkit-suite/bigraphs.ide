package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.List;
import java.util.function.Predicate;

public enum DecompositionType {
    NONE {
        @Override
        public boolean isSatisfied(List<? extends TreeNode<?>> children, Predicate<TreeNode<?>> isCovered) {
            return false;
        }

        @Override
        public List<? extends TreeNode<?>> contributingChildren(List<? extends TreeNode<?>> children,
                Predicate<TreeNode<?>> isCovered) {
            return List.of();
        }
    },
    AND {
        @Override
        public boolean isSatisfied(List<? extends TreeNode<?>> children, Predicate<TreeNode<?>> isCovered) {
            return children.stream().allMatch(isCovered);
        }

        @Override
        public List<? extends TreeNode<?>> contributingChildren(List<? extends TreeNode<?>> children,
                Predicate<TreeNode<?>> isCovered) {
            return children;
        }
    },
    OR {
        @Override
        public boolean isSatisfied(List<? extends TreeNode<?>> children, Predicate<TreeNode<?>> isCovered) {
            return children.stream().anyMatch(isCovered);
        }

        @Override
        public List<? extends TreeNode<?>> contributingChildren(List<? extends TreeNode<?>> children,
                Predicate<TreeNode<?>> isCovered) {
            return children.stream().filter(isCovered).toList();
        }
    };

    public abstract boolean isSatisfied(List<? extends TreeNode<?>> children, Predicate<TreeNode<?>> isCovered);

    public abstract List<? extends TreeNode<?>> contributingChildren(List<? extends TreeNode<?>> children,
            Predicate<TreeNode<?>> isCovered);
}
