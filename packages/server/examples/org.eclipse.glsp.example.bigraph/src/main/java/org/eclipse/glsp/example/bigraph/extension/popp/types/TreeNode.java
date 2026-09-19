package org.eclipse.glsp.example.bigraph.extension.popp.types;

import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEventEmitter;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEventListener;

import java.util.*;

public abstract class TreeNode<T extends TreeNode<T>> implements POPPEventListener, POPPEventEmitter {
    private final List<POPPEventListener> listeners = new LinkedList<>();
    private final String id;
    private final NodeKind kind;
    private String description;
    private double x;
    private double y;
    private boolean explicitlyCovered = false;
    private DecompositionType decompositionType = DecompositionType.NONE;
    protected T parent;
    protected final List<T> children = new ArrayList<>();

    protected TreeNode(NodeKind kind, String description, double x, double y) {
        this(kind, UUID.randomUUID().toString(), description, x, y);
    }

    protected TreeNode(NodeKind kind, String id, String description, double x, double y) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        this.id = id;
        this.description = Objects.requireNonNull(description);
        this.x = x;
        this.y = y;
        this.kind = kind;
    }

    public NodeKind getKind() {
        return kind;
    }

    protected abstract T self();

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        POPPEvent event = new POPPEvent.NodeDescriptionChanged(kind, self(), this.description);
        this.description = description;
        emitEvent(event);
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public void move(double x, double y) {
        POPPEvent event = new POPPEvent.NodeMoved(kind, self(), this.x, this.y);
        this.x = x;
        this.y = y;
        emitEvent(event);
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

        if (decompositionType == DecompositionType.NONE){
            /** Fallback {@link DecompositionType} value. */
            setDecompositionType(DecompositionType.AND);
        }

        child.getParent().ifPresent(oldParent -> {
            if (oldParent != self()) {
                oldParent.children.remove(child);
            }
        });

        child.setParent(self());
        children.add(child);
        child.addListener(this);
        return true;
    }

    public boolean removeChild(T child) {
        if (child == null || !children.contains(child)) {
            return false;
        }
        children.remove(child);
        child.setParent(null);
        child.removeListener(this);
        return true;
    }

    public boolean setParent(T parent) {
        if (parent == self() || (parent != null && parent.isChildOf(self()))) {
            return false;
        }
        if (this.parent == parent) {
            return true;
        }

        TreeNode<T> oldParent = this.parent;
        POPPEvent event = new POPPEvent.NodeChangeParent(kind, self(), oldParent, parent);

        if (oldParent != null) {
            if (parent == null) emitEvent(event);
            oldParent.removeChild(self());
        }

        this.parent = parent;
        if (parent != null && !parent.children.contains(self())) {
            parent.children.add(self());
        }

        if (oldParent == null || parent != null) {
            emitEvent(event);
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

    public void setExplicitlyCovered(boolean covered) {
        this.explicitlyCovered = covered;
    }

    public boolean setDecompositionType(DecompositionType decompositionType) {
        if (decompositionType == DecompositionType.NONE && !children.isEmpty()){
            return false;
        }

        POPPEvent event = new POPPEvent.NodeDecompositionTypeChanged(kind, self(), this.decompositionType);
        this.decompositionType = decompositionType;
        emitEvent(event);
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

    public List<POPPEventListener> getListeners() {
        return listeners;
    }

    @Override
    public void onPOPPEvent(POPPEvent event) {
        emitEvent(event);
    }

    private void emitEvent(POPPEvent event){
        new ArrayList<>(listeners).forEach(listener -> listener.onPOPPEvent(event));
    }
}
