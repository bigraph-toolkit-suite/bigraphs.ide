package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public abstract class TreeNode<T extends TreeNode<T>> {
    private final String id;
    private boolean explicitCoverage = false;
    private DecompositionType decompositionType = DecompositionType.NONE;
    protected T parent;
    protected final List<T> children = new ArrayList<>();

    /** Other nodes (in this or another tree) whose coverage makes this node covered. */
    private final Set<TreeNode<?>> coverageLinks = new LinkedHashSet<>();
    /** Reverse edges: nodes whose cached coverage must be invalidated when this node changes. */
    private final Set<TreeNode<?>> dependents = new LinkedHashSet<>();
    /** Null means "dirty", must be recomputed on next {@link #isCovered()} call. */
    private Boolean cachedCovered;
    private boolean computingCoverage;

    protected TreeNode() {
        this(UUID.randomUUID().toString());
    }

    protected TreeNode(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        this.id = id;
    }

    protected abstract T self();

    public String getId() {
        return id;
    }

    public Optional<T> getParent() {
        return Optional.ofNullable(parent);
    }

    public List<T> getChildren() {
        return List.copyOf(children);
    }

    public boolean addChild(T child) {
        if (child == null || child == self() || child == parent || children.contains(child)) {
            return false;
        }
        if (child.isChildOf(self()) || self().isChildOf(child)) {
            return false;
        }

        child.getParent().ifPresent(oldParent -> {
            if (oldParent != self()) {
                oldParent.children.remove(child);
                child.dependents.remove(oldParent);
                oldParent.invalidate();
            }
        });

        child.parent = self();
        children.add(child);
        child.dependents.add(self());
        invalidate();
        return true;
    }

    public boolean removeChild(T child) {
        if (child == null || !children.contains(child)) {
            return false;
        }
        children.remove(child);
        child.parent = null;
        child.dependents.remove(self());
        invalidate();
        return true;
    }

    public boolean setParent(T parent) {
        if (parent == self()) {
            return false;
        }
        if (parent != null && parent.isChildOf(self())) {
            return false;
        }
        if (this.parent == parent) {
            return true;
        }

        if (this.parent != null) {
            this.parent.children.remove(self());
            dependents.remove(this.parent);
            this.parent.invalidate();
        }

        this.parent = parent;
        if (parent != null) {
            if (!parent.children.contains(self())) {
                parent.children.add(self());
            }
            dependents.add(parent);
            parent.invalidate();
        }
        return true;
    }

    public boolean isChildOf(T potentialParent) {
        T cursor = getParent().orElse(null);
        while (cursor != null) {
            if (cursor == potentialParent) {
                return true;
            }
            cursor = cursor.getParent().orElse(null);
        }
        return false;
    }

    public int getHeight(){
        return 1 + children.stream().mapToInt(TreeNode::getHeight).max().orElse(0);
    }

    public int getWidth(){ 
        if (children.isEmpty()) {
            return 1;
        } else {
            return children.stream().mapToInt(TreeNode::getWidth).sum();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TreeNode<?> other)) {
            return false;
        }
        return getClass().equals(other.getClass()) && id.equals(other.id);
    }

    /**
     * Whether this node is covered, i.e. its explicit coverage flag is set, its decomposition
     * requirement is satisfied by its children, or one of its cross-tree {@link #getCoverageLinks()}
     * targets is covered. The result is cached until a relevant change invalidates it.
     */
    public final boolean isCovered() {
        if (cachedCovered != null) {
            return cachedCovered;
        }
        if (computingCoverage) {
            throw new IllegalStateException("Cyclic coverage dependency detected at " + this);
        }
        computingCoverage = true;
        try {
            cachedCovered = computeCoverage();
        } finally {
            computingCoverage = false;
        }
        return cachedCovered;
    }

    private boolean computeCoverage() {
        if (explicitCoverage) {
            return true;
        }
        if (decompositionType != DecompositionType.NONE && !children.isEmpty()) {
            boolean byDecomposition = decompositionType.requiresAllChildrenCovered()
                ? children.stream().allMatch(TreeNode::isCovered)
                : children.stream().anyMatch(TreeNode::isCovered);
            if (byDecomposition) {
                return true;
            }
        }
        return coverageLinks.stream().anyMatch(TreeNode::isCovered);
    }

    public void setCovered(boolean covered) {
        if (this.explicitCoverage != covered) {
            this.explicitCoverage = covered;
            invalidate();
        }
    }

    /** Clears the cached coverage result and propagates the invalidation to all dependents. */
    private void invalidate() {
        if (cachedCovered == null) {
            return;
        }
        cachedCovered = null;
        for (TreeNode<?> dependent : dependents) {
            dependent.invalidate();
        }
    }

    /**
     * Registers a cross-tree coverage dependency: this node becomes covered if {@code target} is
     * covered. Subclasses should expose type-safe wrappers (e.g. {@code Problem#addGoal}).
     */
    protected final boolean addCoverageLink(TreeNode<?> target) {
        if (target == null || target == this || coverageLinks.contains(target)) {
            return false;
        }
        coverageLinks.add(target);
        target.dependents.add(this);
        invalidate();
        return true;
    }

    protected final boolean removeCoverageLink(TreeNode<?> target) {
        if (target == null || !coverageLinks.remove(target)) {
            return false;
        }
        target.dependents.remove(this);
        invalidate();
        return true;
    }

    public final Set<TreeNode<?>> getCoverageLinks() {
        return Set.copyOf(coverageLinks);
    }

    /**
     * Explains why this node is (or isn't) covered as a small, on-demand reason tree. Only the
     * nodes that actually contribute to the result are visited (e.g. a single satisfying link, or
     * only the children an OR-decomposition needed), so this stays cheap even for interactive use.
     */
    public final CoverageReason explainCoverage() {
        if (explicitCoverage) {
            return new CoverageReason.Explicit(this);
        }
        if (decompositionType != DecompositionType.NONE && !children.isEmpty()) {
            boolean requiresAll = decompositionType.requiresAllChildrenCovered();
            List<T> contributing = requiresAll ? children : children.stream().filter(TreeNode::isCovered).toList();
            boolean satisfies = requiresAll ? contributing.stream().allMatch(TreeNode::isCovered) : !contributing.isEmpty();
            if (satisfies) {
                List<CoverageReason> reasons = contributing.stream().map(TreeNode::explainCoverage).toList();
                return new CoverageReason.Decomposition(this, decompositionType, reasons);
            }
        }
        for (TreeNode<?> link : coverageLinks) {
            if (link.isCovered()) {
                return new CoverageReason.Link(this, link, link.explainCoverage());
            }
        }
        return new CoverageReason.NotCovered(this);
    }

    public boolean setDecompositionType(DecompositionType decompositionType) {
        if (decompositionType == DecompositionType.NONE && !children.isEmpty()){
            return false;
        }

        this.decompositionType = decompositionType;
        invalidate();
        return true;
    }

    public DecompositionType getDecompositionType() {
        return decompositionType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), id);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[id=" + id + "]";
    }
}