package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;


/**
 * The POPP model edited in the editor: every node by id, the roots of every kind ({@link NodeRegistry}) and the
 * relations. The operations that concern more than one node go through it (creating, deleting, decomposing, relating
 * and unrelating nodes), operations on a single node (e.g. its description) are called on the {@link TreeNode}. It
 * forwards the events of every node and of the relation graph to its subscribers, which keep the GModel and the bigraph
 * in sync ({@code POPPGModelSynchronizer} and {@code POPPBigraphSynchronizer}, package {@code sync}).
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
    /** Bumped after every structural event. See {@link #publish}. */
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

    /** Changes with every structural change, e.g. to tell which model a validation report is about. */
    public long getVersion() {
        return version.get();
    }

    /**
     * Emits the event to all listeners, then bumps the version. The bump comes last so that listeners such as the
     * bigraph synchronizer have already applied the change when a reader sees the new version. Moves and description
     * edits are not structural and leave the version alone.
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
    public RelationResult relate(TreeNode<?> first, TreeNode<?> second) {
        return relations.relate(first, second);
    }

    /** Removes the relation between the two nodes, given in either order. */
    public RelationResult unrelate(TreeNode<?> first, TreeNode<?> second) {
        return relations.unrelate(first, second);
    }

    /**
     * Attaches {@code child} below {@code parent} when both are of the same kind. The entry point for callers that
     * only know the nodes as {@code TreeNode<?>} (e.g. the editor's connect tool): the dispatch over the node types
     * recovers the concrete type, and {@link Class#cast} checks the child at runtime, so no unchecked cast is needed.
     * Code that knows the types calls {@link TreeNode#addChild} directly.
     */
    public DecompositionResult decompose(TreeNode<?> parent, TreeNode<?> child) {
        if (parent == null || child == null) {
            return DecompositionResult.NULL_ARGUMENT;
        }
        // a pattern switch over the sealed types (Java 21, the project is on 17) would be checked for exhaustiveness
        if (parent instanceof Problem problem) return attach(problem, child, Problem.class);
        if (parent instanceof Goal goal) return attach(goal, child, Goal.class);
        if (parent instanceof Consequence consequence) return attach(consequence, child, Consequence.class);
        if (parent instanceof Solution solution) return attach(solution, child, Solution.class);
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
        if (Objects.requireNonNull(event) instanceof POPPEvent.NodeChangedParent parentChanged) {
            handleParentChange(parentChanged);
        }
        publish(event);
    }

    private void handleParentChange(POPPEvent.NodeChangedParent event) {
        boolean becameRoot = event.oldParent() != null && event.newParent() == null;
        boolean lostRootStatus = event.oldParent() == null && event.newParent() != null;
        if (!becameRoot && !lostRootStatus) return;

        // a pattern switch over the sealed types (Java 21, the project is on 17) would be checked for exhaustiveness
        TreeNode<?> node = event.node();
        if (node instanceof Problem problem) {
            if (becameRoot) problems.addRoot(problem); else problems.removeRoot(problem);
        } else if (node instanceof Goal goal) {
            if (becameRoot) goals.addRoot(goal); else goals.removeRoot(goal);
        } else if (node instanceof Consequence consequence) {
            if (becameRoot) consequences.addRoot(consequence); else consequences.removeRoot(consequence);
        } else if (node instanceof Solution solution) {
            if (becameRoot) solutions.addRoot(solution); else solutions.removeRoot(solution);
        } else if (node instanceof SuccessCriteria criterion) {
            if (becameRoot) successCriteria.addRoot(criterion); else successCriteria.removeRoot(criterion);
        } else if (node instanceof SuccessProof proof) {
            if (becameRoot) successProofs.addRoot(proof); else successProofs.removeRoot(proof);
        }
    }
}