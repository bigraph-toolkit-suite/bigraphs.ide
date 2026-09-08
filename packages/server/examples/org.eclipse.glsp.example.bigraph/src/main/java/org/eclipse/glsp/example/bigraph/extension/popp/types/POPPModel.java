package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** A POPP model has at most one root per tree kind. */
public class POPPModel {
    private Problem problemRoot;
    private Goal goalRoot;
    private Solution solutionRoot;
    private Consequence consequenceRoot;
    private SuccessCriteria successCriteriaRoot;
    private SuccessProof successProofRoot;

    public Optional<Problem> getProblemRoot() {
        return Optional.ofNullable(problemRoot);
    }

    public void setProblemRoot(Problem problemRoot) {
        this.problemRoot = problemRoot;
    }

    public Optional<Goal> getGoalRoot() {
        return Optional.ofNullable(goalRoot);
    }

    public void setGoalRoot(Goal goalRoot) {
        this.goalRoot = goalRoot;
    }

    public Optional<Solution> getSolutionRoot() {
        return Optional.ofNullable(solutionRoot);
    }

    public void setSolutionRoot(Solution solutionRoot) {
        this.solutionRoot = solutionRoot;
    }

    public Optional<Consequence> getConsequenceRoot() {
        return Optional.ofNullable(consequenceRoot);
    }

    public void setConsequenceRoot(Consequence consequenceRoot) {
        this.consequenceRoot = consequenceRoot;
    }

    public Optional<SuccessCriteria> getSuccessCriteriaRoot() {
        return Optional.ofNullable(successCriteriaRoot);
    }

    public void setSuccessCriteriaRoot(SuccessCriteria successCriteriaRoot) {
        this.successCriteriaRoot = successCriteriaRoot;
    }

    public Optional<SuccessProof> getSuccessProofRoot() {
        return Optional.ofNullable(successProofRoot);
    }

    public void setSuccessProofRoot(SuccessProof successProofRoot) {
        this.successProofRoot = successProofRoot;
    }

    public List<Problem> getAllProblems() {
        return collectAll(problemRoot);
    }

    public List<Goal> getAllGoals() {
        return collectAll(goalRoot);
    }

    public List<Solution> getAllSolutions() {
        return collectAll(solutionRoot);
    }

    public List<Consequence> getAllConsequences() {
        return collectAll(consequenceRoot);
    }

    public List<SuccessCriteria> getAllSuccessCriteria() {
        return collectAll(successCriteriaRoot);
    }

    public List<SuccessProof> getAllSuccessProofs() {
        return collectAll(successProofRoot);
    }

    private static <N extends TreeNode<N>> List<N> collectAll(N root) {
        List<N> result = new ArrayList<>();
        if (root == null) {
            return result;
        }
        Deque<N> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            N node = pending.pop();
            result.add(node);
            pending.addAll(node.getChildren());
        }
        return result;
    }
}
