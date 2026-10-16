package org.eclipse.glsp.example.bigraph.extension.popp.types;

import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEventEmitter;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEventListener;

import java.util.*;

/**
 * A node of one of the six homogeneous POPP trees (CRTP: {@code T} is the concrete node type, so children and parent
 * are statically of the same kind). Sealed, so that code dispatching over the node kinds (e.g.
 * {@link POPPModel#decompose}) is checked for exhaustiveness by the compiler.
 * <p>
 * Every node forwards the events of its children to its own listeners, so a listener on a root (the
 * {@link POPPModel}) hears about the whole tree. Invariant: the decomposition type is {@code NONE} exactly while the
 * node has no children. The first {@link #addChild} sets {@code AND} before the child is attached, so that listeners
 * see the decomposition before the child's parent change.
 */
public abstract sealed class TreeNode<T extends TreeNode<T>> extends POPPEventEmitter implements POPPEventListener
        permits Problem, Goal, Consequence, Solution, SuccessCriteria, SuccessProof {
    private final String id;
    private final NodeKind kind;
    private String description;
    private double x;
    private double y;
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

    /** Hook for node types with extra state to persist (see {@code POPPExtensionMeta}); no node type uses it yet. */
    public Map<String, String> exportProperties() {
        return Map.of();
    }

    /** Counterpart of {@link #exportProperties()}, called when a saved model is loaded. */
    public void importProperties(Map<String, String> properties) {}

    public Optional<T> getParent() {
        return Optional.ofNullable(parent);
    }

    public List<T> getChildren() {
        return List.copyOf(children);
    }

    /**
     * Attaches {@code child} below this node and returns whether it did. Moves the child away from a previous parent;
     * if {@code child} is this node's own parent, the two are flipped (this node first becomes a root). Refuses
     * {@code null}, this node itself, an existing child or other descendant, and any other ancestor (a cycle). The
     * first child gives this node the default decomposition type {@code AND}.
     */
    public boolean addChild(T child) {
        if (child == null || child == self() || children.contains(child)) {
            return false;
        }
        if (child.isChildOf(self()) || (self().isChildOf(child) && child != parent)) {
            return false;
        }

        if (child == parent) { // flip: this node first becomes a root, so no cycle arises
            detach();
        }

        if (decompositionType == DecompositionType.NONE) {
            // first child: AND is the default decomposition (set before the child is added, see the class comment)
            changeDecompositionType(DecompositionType.AND);
        }

        T oldParent = child.parent;
        if (oldParent != null) {
            oldParent.children.remove(child);
        }
        child.parent = self();
        // one event, through the old parent (or, for a root, straight to the model); this node does not listen yet
        child.emitParentChange(oldParent, self());
        children.add(child);
        child.addListener(this);

        if (oldParent != null) {
            oldParent.afterChildDetached(child);
        }
        return true;
    }

    /** Detaches {@code child}, which becomes a root; the last child takes the decomposition type with it. */
    public boolean removeChild(T child) {
        if (child == null || !children.contains(child)) {
            return false;
        }
        child.detach();
        return true;
    }

    /**
     * Makes this node a root. The event goes out after the old parent's child list is updated but while the old
     * parent still forwards this node's events (and before {@link #parent} is cleared), so it reaches the model.
     */
    void detach() { // package-private: called on a child, typed as T
        T oldParent = parent;
        if (oldParent == null) {
            return;
        }
        oldParent.children.remove(self());
        emitParentChange(oldParent, null);
        parent = null;
        oldParent.afterChildDetached(self());
    }

    void emitParentChange(T oldParent, T newParent) {
        emitEvent(new POPPEvent.NodeChangedParent(kind, self(), oldParent, newParent));
    }

    /**
     * Bookkeeping once {@code child} has left {@link #children} and its parent-change event is out: stop forwarding
     * its events, and drop the decomposition if it was the last child (counterpart of the AND fallback in
     * {@link #addChild}: a node has a decomposition type exactly while it has children).
     */
    void afterChildDetached(T child) { // package-private: called on the old parent, typed as T
        child.removeListener(this);
        if (children.isEmpty() && decompositionType != DecompositionType.NONE) {
            changeDecompositionType(DecompositionType.NONE);
        }
    }

    /**
     * Moves this node below {@code parent} (as {@code parent.addChild(this)}), or makes it a root for {@code null}
     * (as {@code removeChild} on the old parent); either way exactly one {@link POPPEvent.NodeChangedParent} is
     * emitted. Refuses this node itself and its own descendants; setting the current parent again is a successful
     * no-op.
     */
    public boolean setParent(T parent) {
        if (parent == null) {
            detach();
            return true;
        }
        if (parent == self() || parent.isChildOf(self())) {
            return false;
        }
        return this.parent == parent || parent.addChild(self());
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

    /** Number of levels of the subtree rooted here (1 for a leaf). */
    public int getHeight(){
        return 1 + children.stream().mapToInt(TreeNode::getHeight).max().orElse(0);
    }

    /** Number of leaves of the subtree rooted here. */
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
     * Sets how the children combine. A node has a decomposition type exactly while it has children, so this refuses
     * {@code NONE} while there are children and {@code AND}/{@code OR} while there are none ({@link #addChild} and
     * {@link #removeChild} switch between {@code NONE} and {@code AND} themselves). Setting the current type again is
     * a successful no-op without an event.
     */
    public boolean setDecompositionType(DecompositionType decompositionType) {
        if (decompositionType == null || (decompositionType == DecompositionType.NONE) != children.isEmpty()) {
            return false;
        }
        if (decompositionType != this.decompositionType) {
            changeDecompositionType(decompositionType);
        }
        return true;
    }

    private void changeDecompositionType(DecompositionType decompositionType) {
        POPPEvent event = new POPPEvent.NodeDecompositionTypeChanged(kind, self(), this.decompositionType);
        this.decompositionType = decompositionType;
        emitEvent(event);
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

    @Override
    public void onPOPPEvent(POPPEvent event) {
        emitEvent(event);
    }
}
