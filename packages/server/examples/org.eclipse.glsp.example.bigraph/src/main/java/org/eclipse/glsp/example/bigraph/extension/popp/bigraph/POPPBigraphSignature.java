package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.datatypes.FiniteOrdinal;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder;

/**
 * Initial POPP-specific {@link DynamicSignature}.
 *
 * <p>The model uses the place graph for hierarchies and decomposition. For
 * simplicity and to avoid ambiguous n-ary coverage semantics, AND-decompositions
 * are normalized to binary subtrees. OR-decompositions can also be represented
 * in the same binary shape, but they do not require it semantically: a single
 * covered child is sufficient for the parent to become covered.
 *
 * <p>Cross-tree traceability is encoded via explicit trace controls, and state
 * is represented with marker controls such as {@code Covered} and
 * {@code Satisfied} rather than by changing the node control itself.
 */
public final class POPPBigraphSignature {

    public static final String CTRL_PROBLEM = "Problem";
    public static final String CTRL_GOAL = "Goal";
    public static final String CTRL_CONSEQUENCE = "Consequence";
    public static final String CTRL_SOLUTION = "Solution";
    public static final String CTRL_SUCCESS_CRITERIA = "SuccessCriteria";
    public static final String CTRL_SUCCESS_PROOF = "SuccessProof";

    public static final String CTRL_AND_DECOMP = "AndDecomp";
    public static final String CTRL_OR_DECOMP = "OrDecomp";

    public static final String CTRL_PORT_CONNECT_PROBLEM_GOAL = "PortConnectProblemGoal";
    public static final String CTRL_PORT_CONNECT_GOAL_SOLUTION = "PortConnectGoalSolution";
    public static final String CTRL_PORT_CONNECT_GOAL_SUCCESS_CRITERIA = "PortConnectSuccessCriteria";
    public static final String CTRL_PORT_CONNECT_PROBLEM_CONSEQUENCE = "PortConnectProblemConsequence";
    public static final String CTRL_PORT_CONNECT_SUCCESS_CRITERIA_SUCCESS_PROOF = "PortConnectSuccessCriteriaSuccessProof";
    public static final String CTRL_PORT_CONNECT_SOLUTION_SUCCESS_PROOF = "PortConnectSolutionSuccessProof";

    private POPPBigraphSignature() {
    }

    /**
     * Creates the initial canonical POPP signature.
     *
     * <p>AND decomposition is normalized to a binary subtree so the coverage rule
     * can be expressed as "left covered and right covered implies parent
     * covered". OR decomposition may also use the same binary shape for
     * simplicity, but semantically it can also be represented by a local rule
     * saying that any covered child suffices.
     */
    public static DynamicSignature create() {
        return pureSignatureBuilder()
                .newControl().identifier(CTRL_PROBLEM).arity(FiniteOrdinal.ofInteger(0)).assign()
                .newControl().identifier(CTRL_GOAL).arity(FiniteOrdinal.ofInteger(0)).assign()
                .newControl().identifier(CTRL_CONSEQUENCE).arity(FiniteOrdinal.ofInteger(0)).assign()
                .newControl().identifier(CTRL_SOLUTION).arity(FiniteOrdinal.ofInteger(0)).assign()
                .newControl().identifier(CTRL_SUCCESS_CRITERIA).arity(FiniteOrdinal.ofInteger(0)).assign()
                .newControl().identifier(CTRL_SUCCESS_PROOF).arity(FiniteOrdinal.ofInteger(0)).assign()

                .newControl().identifier(CTRL_AND_DECOMP).arity(FiniteOrdinal.ofInteger(0)).assign()
                .newControl().identifier(CTRL_OR_DECOMP).arity(FiniteOrdinal.ofInteger(0)).assign()

                .newControl().identifier(CTRL_PORT_CONNECT_PROBLEM_GOAL).arity(FiniteOrdinal.ofInteger(1)).assign()
                .newControl().identifier(CTRL_PORT_CONNECT_GOAL_SOLUTION).arity(FiniteOrdinal.ofInteger(1)).assign()
                .newControl().identifier(CTRL_PORT_CONNECT_GOAL_SUCCESS_CRITERIA).arity(FiniteOrdinal.ofInteger(1)).assign()
                .newControl().identifier(CTRL_PORT_CONNECT_PROBLEM_CONSEQUENCE).arity(FiniteOrdinal.ofInteger(1)).assign()
                .newControl().identifier(CTRL_PORT_CONNECT_SUCCESS_CRITERIA_SUCCESS_PROOF).arity(FiniteOrdinal.ofInteger(1)).assign()
                .create();
    }
}
