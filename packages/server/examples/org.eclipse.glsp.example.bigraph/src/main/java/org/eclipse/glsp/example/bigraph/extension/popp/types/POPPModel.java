package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.*;

import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEventEmitter;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEventListener;

public class POPPModel implements POPPEventListener, POPPEventEmitter {

    /** Creates a node of type N from its editor-facing attributes; matches every TreeNode subtype's constructor. */
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
            emitEvent(new POPPEvent.NodeCreated(node.getKind(), node));
            nodeRegistry.put(node.getId(), node);
            return node;
        }

        /** Like {@link #create}, but preserves a given id instead of generating one. */
        N restore(String id, String description, double x, double y) {
            N node = restoreFactory.create(id, description, x, y);
            addRoot(node); // Registered as a root until/unless a subsequent addChild reparents it
            emitEvent(new POPPEvent.NodeCreated(node.getKind(), node));
            nodeRegistry.put(node.getId(), node);
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
            emitEvent(new POPPEvent.NodeRemoved(node.getKind(), node));
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

    private final Map<String, TreeNode<?>> nodeRegistry = new HashMap<>();

    private final NodeRegistry<Problem> problems = new NodeRegistry<>(Problem::new, Problem::new);
    private final NodeRegistry<Goal> goals = new NodeRegistry<>(Goal::new, Goal::new);
    private final NodeRegistry<Consequence> consequences = new NodeRegistry<>(Consequence::new, Consequence::new);
    private final NodeRegistry<Solution> solutions = new NodeRegistry<>(Solution::new, Solution::new);
    private final NodeRegistry<SuccessCriteria> successCriteria = new NodeRegistry<>(SuccessCriteria::new, SuccessCriteria::new);
    private final NodeRegistry<SuccessProof> successProofs = new NodeRegistry<>(SuccessProof::new, SuccessProof::new);

    private final RelationGraph relations = new RelationGraph();
    private final CoverageAnalyzer coverage = new CoverageAnalyzer(relations);
    private final List<POPPEventListener> listeners = new ArrayList<>();

    public RelationGraph getRelations() {
        return relations;
    }

    public CoverageAnalyzer getCoverage() {
        return coverage;
    }

    /** Creates a typed cross-tree relation, rejecting node-kind combinations the POPP metamodel disallows. */
    public RelationResult relate(TreeNode<?> a, TreeNode<?> b) {
        RelationResult result = relations.relate(a, b);
        if (result == RelationResult.CREATED) {
            findRelation(a, b).ifPresent(
                    relation -> emitEvent(new POPPEvent.RelationCreated(relation)));
        }
        return result;
    }

    public RelationResult unrelate(TreeNode<?> a, TreeNode<?> b) {
        Relation existing = findRelation(a, b).orElse(null);
        RelationResult result = relations.unrelate(a, b);
        if (result == RelationResult.REMOVED && existing != null) {
            emitEvent(new POPPEvent.RelationRemoved(existing.source(), existing.type(), existing.target()));
        }
        return result;
    }

    private Optional<Relation> findRelation(TreeNode<?> a, TreeNode<?> b) {
        if (a == null || b == null) {
            return Optional.empty();
        }
        return relations.outgoing(a).stream().filter(relation -> relation.target() == b || relation.source() == b)
                .findFirst();
    }

    public TreeNode<?> findNode(String id) {
        return nodeRegistry.get(id);
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

    public void addProblemRoot(Problem root) { problems.addRoot(root); }
    public void addGoalRoot(Goal root) { goals.addRoot(root); }
    public void addConsequenceRoot(Consequence root) { consequences.addRoot(root); }
    public void addSolutionRoot(Solution root) { solutions.addRoot(root); }
    public void addSuccessCriteriaRoot(SuccessCriteria root) { successCriteria.addRoot(root); }
    public void addSuccessProofRoot(SuccessProof root) { successProofs.addRoot(root); }

    public void removeProblemRoot(Problem root) { problems.removeRoot(root); }
    public void removeGoalRoot(Goal root) { goals.removeRoot(root); }
    public void removeConsequenceRoot(Consequence root) { consequences.removeRoot(root); }
    public void removeSolutionRoot(Solution root) { solutions.removeRoot(root); }
    public void removeSuccessCriteriaRoot(SuccessCriteria root) { successCriteria.removeRoot(root); }
    public void removeSuccessProofRoot(SuccessProof root) { successProofs.removeRoot(root); }

    public List<Problem> getAllProblems() { return problems.all(); }
    public List<Goal> getAllGoals() { return goals.all(); }
    public List<Consequence> getAllConsequences() { return consequences.all(); }
    public List<Solution> getAllSolutions() { return solutions.all(); }
    public List<SuccessCriteria> getAllSuccessCriteria() { return successCriteria.all(); }
    public List<SuccessProof> getAllSuccessProofs() { return successProofs.all(); }

    public Problem createProblem(String description, double x, double y) { return problems.create(description, x, y); }
    public Goal createGoal(String description, double x, double y) { return goals.create(description, x, y); }
    public Consequence createConsequence(String description, double x, double y) { return consequences.create(description, x, y); }
    public Solution createSolution(String description, double x, double y) { return solutions.create(description, x, y); }
    public SuccessCriteria createSuccessCriteria(String description, double x, double y) { return successCriteria.create(description, x, y); }
    public SuccessProof createSuccessProof(String description, double x, double y) { return successProofs.create(description, x, y); }

    public void deleteProblem(String id) { problems.delete(id); }
    public void deleteGoal(String id) { goals.delete(id); }
    public void deleteConsequence(String id) { consequences.delete(id); }
    public void deleteSolution(String id) { solutions.delete(id); }
    public void deleteSuccessCriteria(String id) { successCriteria.delete(id); }
    public void deleteSuccessProof(String id) { successProofs.delete(id); }

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

    public List<POPPEventListener> getListeners() {
        return listeners;
    }

    @Override
    public void onPOPPEvent(final POPPEvent event) {
        if (Objects.requireNonNull(event) instanceof POPPEvent.NodeChangedParent e) {
            handleParentChange(e);
        }
        emitEvent(event);
    }

    private void handleParentChange(POPPEvent.NodeChangedParent event) {
        boolean becameRoot = event.oldParent() != null && event.newParent() == null;
        boolean lostRootStatus = event.oldParent() == null && event.newParent() != null;
        if (!becameRoot && !lostRootStatus) return;

        switch (event.node()) {
            case Problem r -> { if (becameRoot) problems.addRoot(r); else problems.removeRoot(r); }
            case Goal r -> { if (becameRoot) goals.addRoot(r); else goals.removeRoot(r); }
            case Consequence r -> { if (becameRoot) consequences.addRoot(r); else consequences.removeRoot(r); }
            case Solution r -> { if (becameRoot) solutions.addRoot(r); else solutions.removeRoot(r); }
            case SuccessCriteria r -> { if (becameRoot) successCriteria.addRoot(r); else successCriteria.removeRoot(r); }
            case SuccessProof r -> { if (becameRoot) successProofs.addRoot(r); else successProofs.removeRoot(r); }
            default -> {}
        }
    }

    private void emitEvent(final POPPEvent event) {
        listeners.forEach(listener -> listener.onPOPPEvent(event));
    }
}