package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class POPPModelTest {

    @Test
    void storesTypedRootsAndCollectsTheirDescendants() {
        POPPModel model = new POPPModel();
        Problem root = (Problem) model.createNode(NodeKind.PROBLEM, "root", 0, 0);
        Problem child = new Problem("child", 0, 0);
        Problem grandchild = new Problem("grandchild", 0, 0);
        root.addChild(child);
        child.addChild(grandchild);

        assertEquals(root, model.getProblemRoots().getFirst());
        assertEquals(3, model.getAllProblems().size());
        assertEquals(root, model.getAllProblems().getFirst());
        assertTrue(model.getAllGoals().isEmpty());
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
        assertEquals(List.of(goalRoot), model.getAllGoals());
        assertEquals(solutionRoot, model.getSolutionRoots().getFirst());
        assertEquals(List.of(solutionRoot), model.getAllSolutions());
        assertEquals(consequenceRoot, model.getConsequenceRoots().getFirst());
        assertEquals(List.of(consequenceRoot), model.getAllConsequences());
        assertEquals(successCriteriaRoot, model.getSuccessCriteriaRoots().getFirst());
        assertEquals(List.of(successCriteriaRoot), model.getAllSuccessCriteria());
        assertEquals(successProofRoot, model.getSuccessProofRoots().getFirst());
        assertEquals(List.of(successProofRoot), model.getAllSuccessProofs());

        assertTrue(model.getProblemRoots().isEmpty());
        assertTrue(model.getAllProblems().isEmpty());
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

        System.out.println(String.join(" ", model.getAllProblems().stream().map(TreeNode::getDescription).toList()));
        assertEquals(Set.of(root, left, right, leftChild), Set.copyOf(model.getAllProblems()));
        assertEquals(4, model.getAllProblems().size());
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
            if (e instanceof POPPEvent.NodeCreated c) found.set(model.findNode(c.node().getId()) == c.node());
        });
        model.createNode(NodeKind.GOAL, "goal", 0, 0);
        assertTrue(found.get());
    }

    @Test
    void deletingANodeAnnouncesEachOrphanedChildOnce() {
        POPPModel model = new POPPModel();
        TreeNode<?> parent = model.createNode(NodeKind.GOAL, "parent", 0, 0);
        TreeNode<?> a = model.createNode(NodeKind.GOAL, "a", 0, 0);
        TreeNode<?> b = model.createNode(NodeKind.GOAL, "b", 0, 0);
        model.decompose(parent, a);
        model.decompose(parent, b);
        List<POPPEvent.NodeChangedParent> changes = new java.util.ArrayList<>();
        model.addListener(e -> { if (e instanceof POPPEvent.NodeChangedParent c) changes.add(c); });

        model.deleteNode(parent.getId());

        assertEquals(2, changes.size(), changes::toString);
        assertTrue(changes.stream().allMatch(c -> c.oldParent() == parent && c.newParent() == null));
        assertEquals(Set.of(a, b), Set.copyOf(model.getGoalRoots()));
    }

    @Test
    void aDeletedRootIsNoLongerListed() {
        POPPModel model = new POPPModel();
        TreeNode<?> goal = model.createNode(NodeKind.GOAL, "goal", 0, 0);
        TreeNode<?> other = model.createNode(NodeKind.GOAL, "other", 0, 0);

        model.deleteNode(goal.getId());

        assertEquals(List.of(other), model.getGoalRoots());
        assertEquals(List.of(other), model.getAllGoals());
        assertNull(model.findNode(goal.getId()));
        AtomicBoolean heard = new AtomicBoolean();
        model.addListener(e -> heard.set(true));
        goal.move(1, 1);
        assertFalse(heard.get(), "the model stopped listening to the deleted node");
    }
}
