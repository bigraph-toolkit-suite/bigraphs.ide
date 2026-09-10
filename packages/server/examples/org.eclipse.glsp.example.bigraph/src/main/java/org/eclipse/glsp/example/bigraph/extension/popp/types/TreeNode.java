package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pure tree hierarchy plus the facts a modeler sets directly; coverage is computed externally by {@link CoverageAnalyzer}. */
public abstract class TreeNode<T extends TreeNode<T>> {
    private final String id;
    private boolean explicitlyCovered = false;
    private DecompositionType decompositionType = DecompositionType.NONE;
    protected T parent;
    protected final List<T> children = new ArrayList<>();

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
            }
        });

        child.parent = self();
        children.add(child);
        return true;
    }

    public boolean removeChild(T child) {
        if (child == null || !children.contains(child)) {
            return false;
        }
        children.remove(child);
        child.parent = null;
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
        }

        this.parent = parent;
        if (parent != null && !parent.children.contains(self())) {
            parent.children.add(self());
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

    /** The explicit coverage fact set directly on this node (e.g. a confirmed proof), independent of any relation. */
    public boolean isExplicitlyCovered() {
        return explicitlyCovered;
    }

    public void setCovered(boolean covered) {
        this.explicitlyCovered = covered;
    }

    public boolean setDecompositionType(DecompositionType decompositionType) {
        if (decompositionType == DecompositionType.NONE && !children.isEmpty()){
            return false;
        }

        this.decompositionType = decompositionType;
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
