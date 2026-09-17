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
        Problem root = new Problem("root", 0, 0);
        Problem child = new Problem("child", 0, 0);
        Problem grandchild = new Problem("grandchild", 0, 0);
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
        Goal goalRoot = new Goal("goal-root", 0, 0);
        Solution solutionRoot = new Solution("solution-root", 0, 0);
        Consequence consequenceRoot = new Consequence("consequence-root", 0, 0);
        SuccessCriteria successCriteriaRoot = new SuccessCriteria("sc-root", 0, 0);
        SuccessProof successProofRoot = new SuccessProof("proof-root", 0, 0);

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
        Problem root = new Problem("root", 0, 0);
        Problem left = new Problem("left", 0, 0);
        Problem right = new Problem("right", 0, 0);
        Problem leftChild = new Problem("left-child", 0, 0);
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
        SuccessProof proof = new SuccessProof("proof", 0, 0);
        SuccessCriteria criterion = new SuccessCriteria("criterion", 0, 0);

        assertEquals(RelationResult.CREATED, model.relate(proof, criterion));
        assertTrue(model.getCoverage().isCovered(criterion));
        assertEquals(RelationResult.REMOVED, model.unrelate(criterion, proof));
        assertFalse(model.getCoverage().isCovered(criterion));
        assertTrue(model.getRelations().all().isEmpty());
    }
}