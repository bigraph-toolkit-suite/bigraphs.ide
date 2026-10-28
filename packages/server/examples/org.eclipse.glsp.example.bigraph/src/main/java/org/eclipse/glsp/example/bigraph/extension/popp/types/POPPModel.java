package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Represents the POPP model edited in the editor.
 * It stores all root nodes in {@link NodeRegistry}s of a certain node kind,
 * and acts as a facade for many operations like creating, deleting, decomposing, relating
 * and unrelating nodes. After these operations have been executed, the model also alerts its
 * subscribers about the changes. Many other operations can still be done directly on the {@link TreeNode}s
 * due to events being forwarded to the subscribers of the {@link POPPModel}.
 * The emitted events keep the GModel and the bigraph in sync through the subscribers
 * {@code POPPGModelSynchronizer} and {@code POPPBigraphSynchronizer} (package {@code integration}).
 */
public class POPPModel extends POPPEventEmitter implements POPPEventListener {
    private final Map<String, TreeNode<?>> nodeRegistry = new HashMap<>();

    private final NodeRegistry<Problem> problems = new NodeRegistry<>(Problem::new, Problem::new);
    private final NodeRegistry<Goal> goals = new NodeRegistry<>(Goal::new, Goal::new);
    private final NodeRegistry<Consequence> consequences = new NodeRegistry<>(Consequence::new, Consequence::new);
    private final NodeRegistry<Solution> solutions = new NodeRegistry<>(Solution::new, Solution::new);
    private final NodeRegistry<SuccessCriteria> successCriteria = new NodeRegistry<>(SuccessCriteria::new, SuccessCriteria::new);
    private final NodeRegistry<SuccessProof> successProofs = new NodeRegistry<>(SuccessProof::new, SuccessProof::new);

    private final RelationGraph relations = new RelationGraph();
    /** Bumped after every event that can change coverage. See {@link #publish}. */
    private final AtomicLong version = new AtomicLong();

    public POPPModel() {
        relations.addListener(this);
    }

    /** Creates a node of type N from its editor-facing attributes. Matches every TreeNode subtype's constructor. */
    @FunctionalInterface
    private interface NodeFactory<N extends TreeNode<N>> {
        N create(String description, double x, double y);
    }

    @FunctionalInterface
    private interface NodeFactoryWithId<N extends TreeNode<N>> {
        N create(String id, String description, double x, double y);
    }

    /** Owns everything specific to one node kind: its root list, creation, deletion and root-membership bookkeeping. */
    private final class NodeRegistry<N extends TreeNode<N>> {
        private final List<N> roots = new LinkedList<>();
        private final NodeFactory<N> factory;
        private final NodeFactoryWithId<N> restoreFactory;

        NodeRegistry(NodeFactory<N> factory, NodeFactoryWithId<N> restoreFactory) {
            this.factory = factory;
            this.restoreFactory = restoreFactory;
        }

        List<N> roots() {
            return roots;
        }

        List<N> all() {
            return collectAll(roots);
        }

        N create(String description, double x, double y) {
            N node = factory.create(description, x, y);
            addRoot(node); // Created with no parent
            nodeRegistry.put(node.getId(), node); // before the event, so listeners can look the node up
            publish(new POPPEvent.NodeCreated(node.getKind(), node));
            return node;
        }

        /** Like {@link #create}, but preserves a given id instead of generating one. */
        N restore(String id, String description, double x, double y) {
            N node = restoreFactory.create(id, description, x, y);
            addRoot(node); // Registered as a root until/unless a subsequent addChild reparents it
            nodeRegistry.put(node.getId(), node);
            publish(new POPPEvent.NodeCreated(node.getKind(), node));
            return node;
        }

        void delete(String id) {
            N node = all().stream()
                    .filter(n -> n.getId().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("No such node was found"));

            node.getParent().ifPresent(parent -> parent.removeChild(node));
            node.getChildren().forEach(child -> child.setParent(null));
            nodeRegistry.remove(node.getId());
            relations.incoming(node).forEach(relation -> unrelate(relation.source(), node));
            relations.outgoing(node).forEach(relation -> unrelate(node, relation.target()));
            removeRoot(node); // a deleted root must not stay listed (no-op for a former child, which is a root now)
            publish(new POPPEvent.NodeRemoved(node.getKind(), node));
        }

        void addRoot(N node) {
            if (node == null || roots.contains(node)) return;
            node.addListener(POPPModel.this);
            roots.add(node);
        }

        void removeRoot(N node) {
            if (node == null) return;
            node.removeListener(POPPModel.this);
            roots.remove(node);
        }
    }

    public RelationGraph getRelations() {
        return relations;
    }

    /** Changes with every model change that can affect coverage. Cached coverage sessions are valid for one version. */
    public long getVersion() {
        return version.get();
    }

    /**
     * Emits the event to all listeners, then bumps the version. The bump comes last so that listeners such as the
     * bigraph synchronizer have already applied the change when a cache sees the new version. Moves and description
     * edits do not affect coverage and leave the version alone.
     */
    private void publish(POPPEvent event) {
        try {
            emitEvent(event);
        } finally {
            if (!(event instanceof POPPEvent.NodeMoved || event instanceof POPPEvent.NodeDescriptionChanged)) {
                version.incrementAndGet();
            }
        }
    }

    /** Creates a typed cross-tree relation, rejecting node-kind combinations the POPP metamodel disallows. */
    public RelationResult relate(TreeNode<?> a, TreeNode<?> b) {
        return relations.relate(a, b);
    }

    /** Removes the relation between the two nodes, given in either order. */
    public RelationResult unrelate(TreeNode<?> a, TreeNode<?> b) {
        return relations.unrelate(a, b);
    }

    /**
     * Attaches {@code child} below {@code parent} when both are of the same kind. The entry point for callers that
     * only know the nodes as {@code TreeNode<?>} (e.g. the editor's connect tool): the exhaustive switch over the sealed
     * node types recovers the concrete type, and {@link Class#cast} checks the child at runtime, so no unchecked cast
     * is needed anywhere. Code that knows the types calls {@link TreeNode#addChild} directly.
     */
    public DecompositionResult decompose(TreeNode<?> parent, TreeNode<?> child) {
        if (parent == null || child == null) {
            return DecompositionResult.NULL_ARGUMENT;
        }
        // If language standard is updated to 21 replace with switch casting
        if (parent instanceof Problem p) return attach(p, child, Problem.class);
        if (parent instanceof Goal g) return attach(g, child, Goal.class);
        if (parent instanceof Consequence c) return attach(c, child, Consequence.class);
        if (parent instanceof Solution s) return attach(s, child, Solution.class);
        if (parent instanceof SuccessCriteria sc) return attach(sc, child, SuccessCriteria.class);
        if (parent instanceof SuccessProof sp) return attach(sp, child, SuccessProof.class);
        throw new IllegalStateException("Unhandled node type: " + parent.getClass().getName());
    }

    private static <N extends TreeNode<N>> DecompositionResult attach(N parent, TreeNode<?> child, Class<N> type) {
        if (!type.isInstance(child)) {
            return DecompositionResult.KIND_MISMATCH;
        }
        return parent.addChild(type.cast(child)) ? DecompositionResult.ATTACHED : DecompositionResult.REJECTED;
    }

    public TreeNode<?> findNode(String id) {
        return nodeRegistry.get(id);
    }

    /**
     * Deletes the node: it leaves its parent, its children become roots of their own, and its relations are removed
     * (each with its own event) before {@link POPPEvent.NodeRemoved}. Unknown ids are ignored.
     */
    public void deleteNode(String id) {
        TreeNode<?> node = findNode(id);
        if (node == null) {
            return;
        }
        switch (node.getKind()) {
            case PROBLEM -> problems.delete(id);
            case GOAL -> goals.delete(id);
            case CONSEQUENCE -> consequences.delete(id);
            case SOLUTION -> solutions.delete(id);
            case SUCCESS_CRITERIA -> successCriteria.delete(id);
            case SUCCESS_PROOF -> successProofs.delete(id);
        }
    }

    public TreeNode<?> createNode(NodeKind kind, String description, double x, double y) {
        return switch (kind) {
            case PROBLEM -> problems.create(description, x, y);
            case GOAL -> goals.create(description, x, y);
            case CONSEQUENCE -> consequences.create(description, x, y);
            case SOLUTION -> solutions.create(description, x, y);
            case SUCCESS_CRITERIA -> successCriteria.create(description, x, y);
            default -> successProofs.create(description, x, y);
        };
    }

    public TreeNode<?> restoreNode(NodeKind kind, String id, String description, double x, double y) {
        return switch (kind) {
            case PROBLEM -> problems.restore(id, description, x, y);
            case GOAL -> goals.restore(id, description, x, y);
            case CONSEQUENCE -> consequences.restore(id, description, x, y);
            case SOLUTION -> solutions.restore(id, description, x, y);
            case SUCCESS_CRITERIA -> successCriteria.restore(id, description, x, y);
            default -> successProofs.restore(id, description, x, y);
        };
    }

    public List<Problem> getProblemRoots() { return problems.roots(); }
    public List<Goal> getGoalRoots() { return goals.roots(); }
    public List<Consequence> getConsequenceRoots() { return consequences.roots(); }
    public List<Solution> getSolutionRoots() { return solutions.roots(); }
    public List<SuccessCriteria> getSuccessCriteriaRoots() { return successCriteria.roots(); }
    public List<SuccessProof> getSuccessProofRoots() { return successProofs.roots(); }

    public List<Problem> getAllProblems() { return problems.all(); }
    public List<Goal> getAllGoals() { return goals.all(); }
    public List<Consequence> getAllConsequences() { return consequences.all(); }
    public List<Solution> getAllSolutions() { return solutions.all(); }
    public List<SuccessCriteria> getAllSuccessCriteria() { return successCriteria.all(); }
    public List<SuccessProof> getAllSuccessProofs() { return successProofs.all(); }

    public Collection<TreeNode<?>> getAllNodes() {
        return nodeRegistry.values();
    }

    private static <N extends TreeNode<N>> List<N> collectAll(List<N> roots) {
        List<N> result = new ArrayList<>();
        Deque<N> pending = new ArrayDeque<>();
        for (N root : roots) {
            pending.push(root);
            while (!pending.isEmpty()) {
                N node = pending.pop();
                result.add(node);
                pending.addAll(node.getChildren());
            }
        }
        return result;
    }

    @Override
    public void onPOPPEvent(final POPPEvent event) {
        if (Objects.requireNonNull(event) instanceof POPPEvent.NodeChangedParent e) {
            handleParentChange(e);
        }
        publish(event);
    }

    private void handleParentChange(POPPEvent.NodeChangedParent event) {
        boolean becameRoot = event.oldParent() != null && event.newParent() == null;
        boolean lostRootStatus = event.oldParent() == null && event.newParent() != null;
        if (!becameRoot && !lostRootStatus) return;

        // If language standard is updated to 21 replace with switch casting
        TreeNode<?> node = event.node();
        if (node instanceof Problem r) {
            if (becameRoot) problems.addRoot(r); else problems.removeRoot(r);
        } else if (node instanceof Goal r) {
            if (becameRoot) goals.addRoot(r); else goals.removeRoot(r);
        } else if (node instanceof Consequence r) {
            if (becameRoot) consequences.addRoot(r); else consequences.removeRoot(r);
        } else if (node instanceof Solution r) {
            if (becameRoot) solutions.addRoot(r); else solutions.removeRoot(r);
        } else if (node instanceof SuccessCriteria r) {
            if (becameRoot) successCriteria.addRoot(r); else successCriteria.removeRoot(r);
        } else if (node instanceof SuccessProof r) {
            if (becameRoot) successProofs.addRoot(r); else successProofs.removeRoot(r);
        }
    }
}