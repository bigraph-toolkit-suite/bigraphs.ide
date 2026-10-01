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

        System.out.println(String.join(" ", model.getAllProblems().stream().map(p -> p.getDescription()).toList()));
        assertEquals(Set.of(root, left, right, leftChild), Set.copyOf(model.getAllProblems()));
        assertEquals(4, model.getAllProblems().size());
    }

    @Test
    void delegatesRelationsAndCoverageToItsCollaborators() {
        POPPModel model = new POPPModel();
        SuccessProof proof = new SuccessProof("proof", 0, 0);
        SuccessCriteria criterion = new SuccessCriteria("criterion", 0, 0);

        assertEquals(RelationResult.CREATED, model.relate(proof, criterion));
        assertTrue(model.getCoverage().isCovered(criterion));
        assertEquals(RelationResult.REMOVED, model.unrelate(criterion, proof));
        assertFalse(model.getCoverage().isCovered(criterion));
        assertTrue(model.getRelations().all().isEmpty());
    }

    @Test
    void creationAndDeletionEventsAreDispatched() {
        POPPModel model = new POPPModel();
        AtomicBoolean created = new AtomicBoolean(false);
        AtomicBoolean deleted = new AtomicBoolean(false);
        model.addListener((event -> {
            switch (event) {
                case POPPEvent.NodeCreated e -> {created.set(true);}
                case POPPEvent.NodeRemoved e -> {deleted.set(true);}
                default -> {}
            }
        }));

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
}