package org.eclipse.glsp.example.bigraph.extension.popp.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class POPPModelTest {

    @Test
    void storesTypedRootsAndCollectsTheirDescendants() {
        POPPModel model = new POPPModel();
        Problem root = new Problem("root");
        Problem child = new Problem("child");
        Problem grandchild = new Problem("grandchild");
        root.addChild(child);
        child.addChild(grandchild);

        model.setProblemRoot(root);

        assertEquals(root, model.getProblemRoot().orElseThrow());
        assertEquals(3, model.getAllProblems().size());
        assertEquals(root, model.getAllProblems().get(0));
        assertTrue(model.getAllGoals().isEmpty());
    }

    @Test
    void collectsDescendantsIndependentlyForEveryTreeKind() {
        POPPModel model = new POPPModel();
        Goal goalRoot = new Goal("goal-root");
        Solution solutionRoot = new Solution("solution-root");
        Consequence consequenceRoot = new Consequence("consequence-root");
        SuccessCriteria successCriteriaRoot = new SuccessCriteria("sc-root");
        SuccessProof successProofRoot = new SuccessProof("proof-root");

        model.setGoalRoot(goalRoot);
        model.setSolutionRoot(solutionRoot);
        model.setConsequenceRoot(consequenceRoot);
        model.setSuccessCriteriaRoot(successCriteriaRoot);
        model.setSuccessProofRoot(successProofRoot);

        assertEquals(goalRoot, model.getGoalRoot().orElseThrow());
        assertEquals(List.of(goalRoot), model.getAllGoals());
        assertEquals(solutionRoot, model.getSolutionRoot().orElseThrow());
        assertEquals(List.of(solutionRoot), model.getAllSolutions());
        assertEquals(consequenceRoot, model.getConsequenceRoot().orElseThrow());
        assertEquals(List.of(consequenceRoot), model.getAllConsequences());
        assertEquals(successCriteriaRoot, model.getSuccessCriteriaRoot().orElseThrow());
        assertEquals(List.of(successCriteriaRoot), model.getAllSuccessCriteria());
        assertEquals(successProofRoot, model.getSuccessProofRoot().orElseThrow());
        assertEquals(List.of(successProofRoot), model.getAllSuccessProofs());

        assertTrue(model.getProblemRoot().isEmpty());
        assertTrue(model.getAllProblems().isEmpty());
    }

    @Test
    void collectsAllDescendantsAcrossMultipleBranches() {
        POPPModel model = new POPPModel();
        Problem root = new Problem("root");
        Problem left = new Problem("left");
        Problem right = new Problem("right");
        Problem leftChild = new Problem("left-child");
        root.addChild(left);
        root.addChild(right);
        left.addChild(leftChild);

        model.setProblemRoot(root);

        assertEquals(Set.of(root, left, right, leftChild), Set.copyOf(model.getAllProblems()));
        assertEquals(4, model.getAllProblems().size());
    }

    @Test
    void delegatesRelationsAndCoverageToItsCollaborators() {
        POPPModel model = new POPPModel();
        SuccessProof proof = new SuccessProof("proof");
        SuccessCriteria criterion = new SuccessCriteria("criterion");

        assertEquals(RelationResult.CREATED, model.relate(proof, criterion));
        assertTrue(model.getCoverage().isCovered(criterion));
        assertEquals(RelationResult.REMOVED, model.unrelate(criterion, proof));
        assertFalse(model.getCoverage().isCovered(criterion));
        assertTrue(model.getRelations().all().isEmpty());
    }
}