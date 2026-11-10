package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class POPPModelTest {

    /** The nodes of one kind, each root followed by its subtree (the order the model keeps its roots in). */
    private static List<TreeNode<?>> nodesOf(POPPModel model, NodeKind kind) {
        List<? extends TreeNode<?>> roots = switch (kind) {
            case PROBLEM -> model.getProblemRoots();
            case GOAL -> model.getGoalRoots();
            case CONSEQUENCE -> model.getConsequenceRoots();
            case SOLUTION -> model.getSolutionRoots();
            case SUCCESS_CRITERIA -> model.getSuccessCriteriaRoots();
            case SUCCESS_PROOF -> model.getSuccessProofRoots();
        };
        List<TreeNode<?>> nodes = new ArrayList<>();
        for (TreeNode<?> root : roots) {
            Deque<TreeNode<?>> pending = new ArrayDeque<>(List.of(root));
            while (!pending.isEmpty()) {
                TreeNode<?> node = pending.pop();
                nodes.add(node);
                node.getChildren().forEach(pending::push);
            }
        }
        return nodes;
    }


    @Test
    void storesTypedRootsAndCollectsTheirDescendants() {
        POPPModel model = new POPPModel();
        Problem root = (Problem) model.createNode(NodeKind.PROBLEM, "root", 0, 0);
        Problem child = new Problem("child", 0, 0);
        Problem grandchild = new Problem("grandchild", 0, 0);
        root.addChild(child);
        child.addChild(grandchild);

        assertEquals(root, model.getProblemRoots().getFirst());
        assertEquals(3, nodesOf(model, NodeKind.PROBLEM).size());
        assertEquals(root, nodesOf(model, NodeKind.PROBLEM).getFirst());
        assertTrue(nodesOf(model, NodeKind.GOAL).isEmpty());
    }

    @Test
    void collectsDescendantsIndependentlyForEveryTreeKind() {
        POPPModel model = new POPPModel();
        Goal goalRoot = (Goal) model.createNode(NodeKind.GOAL , "goal-root", 0, 0);
        Solution solutionRoot = (Solution) model.createNode(NodeKind.SOLUTION, "solution-root", 0, 0);
        Consequence consequenceRoot = (Consequence) model.createNode(NodeKind.CONSEQUENCE, "consequence-root", 0, 0);
        SuccessCriteria successCriteriaRoot = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "sc-root", 0, 0);
        SuccessProof successProofRoot = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF, "proof-root", 0, 0);

        assertEquals(goalRoot, model.getGoalRoots().getFirst());
        assertEquals(List.of(goalRoot), nodesOf(model, NodeKind.GOAL));
        assertEquals(solutionRoot, model.getSolutionRoots().getFirst());
        assertEquals(List.of(solutionRoot), nodesOf(model, NodeKind.SOLUTION));
        assertEquals(consequenceRoot, model.getConsequenceRoots().getFirst());
        assertEquals(List.of(consequenceRoot), nodesOf(model, NodeKind.CONSEQUENCE));
        assertEquals(successCriteriaRoot, model.getSuccessCriteriaRoots().getFirst());
        assertEquals(List.of(successCriteriaRoot), nodesOf(model, NodeKind.SUCCESS_CRITERIA));
        assertEquals(successProofRoot, model.getSuccessProofRoots().getFirst());
        assertEquals(List.of(successProofRoot), nodesOf(model, NodeKind.SUCCESS_PROOF));

        assertTrue(model.getProblemRoots().isEmpty());
        assertTrue(nodesOf(model, NodeKind.PROBLEM).isEmpty());
    }

    @Test
    void collectsAllDescendantsAcrossMultipleBranches() {
        POPPModel model = new POPPModel();
        Problem root = (Problem) model.createNode(NodeKind.PROBLEM, "root", 0, 0);
        Problem left = (Problem) model.createNode(NodeKind.PROBLEM, "left", 0, 0);
        Problem right = (Problem) model.createNode(NodeKind.PROBLEM, "right", 0, 0);
        Problem leftChild = (Problem) model.createNode(NodeKind.PROBLEM, "left-child", 0, 0);
        root.addChild(left);
        root.addChild(right);
        left.addChild(leftChild);

        System.out.println(String.join(" ", nodesOf(model, NodeKind.PROBLEM).stream().map(TreeNode::getDescription).toList()));
        assertEquals(Set.of(root, left, right, leftChild), Set.copyOf(nodesOf(model, NodeKind.PROBLEM)));
        assertEquals(4, nodesOf(model, NodeKind.PROBLEM).size());
    }

    @Test
    void delegatesRelationsToItsRelationGraphAndBumpsTheVersion() {
        POPPModel model = new POPPModel();
        SuccessProof proof = new SuccessProof("proof", 0, 0);
        SuccessCriteria criterion = new SuccessCriteria("criterion", 0, 0);
        long initial = model.getVersion();

        assertEquals(RelationResult.CREATED, model.relate(proof, criterion));
        assertEquals(1, model.getRelations().all().size());
        long afterRelate = model.getVersion();
        assertTrue(afterRelate > initial, "a relation can change coverage, so cached sessions must be invalidated");

        assertEquals(RelationResult.REMOVED, model.unrelate(criterion, proof));
        assertTrue(model.getRelations().all().isEmpty());
        assertTrue(model.getVersion() > afterRelate);
    }

    @Test
    void creationAndDeletionEventsAreDispatched() {
        POPPModel model = new POPPModel();
        AtomicBoolean created = new AtomicBoolean(false);
        AtomicBoolean deleted = new AtomicBoolean(false);
        model.addListener(event -> {
            if (event instanceof POPPEvent.NodeCreated) created.set(true);
            else if (event instanceof POPPEvent.NodeRemoved) deleted.set(true);
        });

        assertFalse(created.get());
        assertFalse(deleted.get());

        Problem problem = (Problem) model.createNode(NodeKind.PROBLEM, "problem", 0, 0);
        assertTrue(created.get());
        assertFalse(deleted.get());

        model.deleteNode(problem.getId());
        assertTrue(created.get());
        assertTrue(deleted.get());
    }

    @Test
    void treeNodeEventsAreForwarded(){
        POPPModel model = new POPPModel();
        AtomicBoolean renamed = new AtomicBoolean(false);
        model.addListener((event -> {
            if (event instanceof POPPEvent.NodeDescriptionChanged) renamed.set(true);
        }));

        Problem problem = (Problem) model.createNode(NodeKind.PROBLEM, "problem", 0, 0);
        assertFalse(renamed.get());

        problem.setDescription("renamed");

        assertTrue(renamed.get());
    }

    @Test
    void rootsAreRemovedWhenParentAssigned(){
        POPPModel model = new POPPModel();
        Problem root = (Problem) model.createNode(NodeKind.PROBLEM, "root", 0, 0);
        Problem child = (Problem) model.createNode(NodeKind.PROBLEM, "child", 0, 0);

        assertEquals(2, model.getProblemRoots().size());
        root.addChild(child);
        assertEquals(1, model.getProblemRoots().size());
        root.removeChild(child);
        assertEquals(2, model.getProblemRoots().size());
    }

    @Test
    void decomposeAttachesSameKindOnlyAndReportsWhy() {
        POPPModel model = new POPPModel();
        TreeNode<?> problem = model.createNode(NodeKind.PROBLEM, "p", 0, 0);
        TreeNode<?> subProblem = model.createNode(NodeKind.PROBLEM, "sub", 0, 0);
        TreeNode<?> goal = model.createNode(NodeKind.GOAL, "g", 0, 0);

        assertEquals(DecompositionResult.KIND_MISMATCH, model.decompose(problem, goal));
        assertEquals(DecompositionResult.NULL_ARGUMENT, model.decompose(problem, null));
        assertEquals(DecompositionResult.ATTACHED, model.decompose(problem, subProblem));
        assertEquals(DecompositionResult.REJECTED, model.decompose(problem, subProblem), "already a child");
        TreeNode<?> leaf = model.createNode(NodeKind.PROBLEM, "leaf", 0, 0);
        assertEquals(DecompositionResult.ATTACHED, model.decompose(subProblem, leaf));
        assertEquals(DecompositionResult.REJECTED, model.decompose(leaf, problem), "would close a cycle");
        assertEquals(List.of(subProblem), problem.getChildren());
        assertTrue(goal.getChildren().isEmpty());
    }

    @Test
    void aCreatedNodeCanBeLookedUpWhileItsEventIsHandled() {
        POPPModel model = new POPPModel();
        AtomicBoolean found = new AtomicBoolean();
        model.addListener(e -> {
            if (e instanceof POPPEvent.NodeCreated created) found.set(model.findNode(created.node().getId()) == created.node());
        });
        model.createNode(NodeKind.GOAL, "goal", 0, 0);
        assertTrue(found.get());
    }

    @Test
    void deletingANodeAnnouncesEachOrphanedChildOnce() {
        POPPModel model = new POPPModel();
        TreeNode<?> parent = model.createNode(NodeKind.GOAL, "parent", 0, 0);
        TreeNode<?> first = model.createNode(NodeKind.GOAL, "a", 0, 0);
        TreeNode<?> second = model.createNode(NodeKind.GOAL, "b", 0, 0);
        model.decompose(parent, first);
        model.decompose(parent, second);
        List<POPPEvent.NodeChangedParent> changes = new java.util.ArrayList<>();
        model.addListener(e -> { if (e instanceof POPPEvent.NodeChangedParent parentChanged) changes.add(parentChanged); });

        model.deleteNode(parent.getId());

        assertEquals(2, changes.size(), changes::toString);
        assertTrue(changes.stream().allMatch(c -> c.oldParent() == parent && c.newParent() == null));
        assertEquals(Set.of(first, second), Set.copyOf(model.getGoalRoots()));
    }

    @Test
    void aDeletedRootIsNoLongerListed() {
        POPPModel model = new POPPModel();
        TreeNode<?> goal = model.createNode(NodeKind.GOAL, "goal", 0, 0);
        TreeNode<?> other = model.createNode(NodeKind.GOAL, "other", 0, 0);

        model.deleteNode(goal.getId());

        assertEquals(List.of(other), model.getGoalRoots());
        assertEquals(List.of(other), nodesOf(model, NodeKind.GOAL));
        assertNull(model.findNode(goal.getId()));
        AtomicBoolean heard = new AtomicBoolean();
        model.addListener(e -> heard.set(true));
        goal.move(1, 1);
        assertFalse(heard.get(), "the model stopped listening to the deleted node");
    }
}
