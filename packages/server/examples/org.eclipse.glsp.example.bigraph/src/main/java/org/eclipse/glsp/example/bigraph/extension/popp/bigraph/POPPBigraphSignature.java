package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.datatypes.FiniteOrdinal;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Relation;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder;

/**
 * The POPP-specific {@link DynamicSignature}, expressed as an enum of controls.
 *
 * <p>Every domain node is a node whose control is its kind ({@code Problem}, {@code Goal}, ...). The place graph
 * carries the hierarchies and decompositions: an n-ary AND/OR decomposition becomes a decomposition chain of binary gate nodes
 * under the decomposed node, with one gate control per kind and type ({@code GoalAnd}, {@code ProblemOr}, ...), so
 * a gate node says which tree it belongs to. The topmost gate node is the one directly under a domain node. Every
 * further gate node is under a gate node. Cross-tree relations are a pair of arity-1 stub
 * nodes (one per relation type and direction) under their endpoints, connected by an edge.
 *
 * <p>Coverage state is not part of this signature: the coverage analysis adds its marker controls on a derived copy
 * (see {@code CoverageSignature}).
 */
public enum POPPBigraphSignature {

    POPP("POPP", 0),

    // Domain nodes: one control per node kind
    PROBLEM("Problem", 0),
    GOAL("Goal", 0),
    CONSEQUENCE("Consequence", 0),
    SOLUTION("Solution", 0),
    SUCCESS_CRITERIA("SuccessCriteria", 0),
    SUCCESS_PROOF("SuccessProof", 0),

    // Gate nodes of the decomposition chains: one control per kind and decomposition type
    PROBLEM_AND("ProblemAnd", NodeKind.PROBLEM, DecompositionType.AND),
    PROBLEM_OR("ProblemOr", NodeKind.PROBLEM, DecompositionType.OR),
    GOAL_AND("GoalAnd", NodeKind.GOAL, DecompositionType.AND),
    GOAL_OR("GoalOr", NodeKind.GOAL, DecompositionType.OR),
    CONSEQUENCE_AND("ConsequenceAnd", NodeKind.CONSEQUENCE, DecompositionType.AND),
    CONSEQUENCE_OR("ConsequenceOr", NodeKind.CONSEQUENCE, DecompositionType.OR),
    SOLUTION_AND("SolutionAnd", NodeKind.SOLUTION, DecompositionType.AND),
    SOLUTION_OR("SolutionOr", NodeKind.SOLUTION, DecompositionType.OR),
    SUCCESS_CRITERIA_AND("SuccessCriteriaAnd", NodeKind.SUCCESS_CRITERIA, DecompositionType.AND),
    SUCCESS_CRITERIA_OR("SuccessCriteriaOr", NodeKind.SUCCESS_CRITERIA, DecompositionType.OR),
    SUCCESS_PROOF_AND("SuccessProofAnd", NodeKind.SUCCESS_PROOF, DecompositionType.AND),
    SUCCESS_PROOF_OR("SuccessProofOr", NodeKind.SUCCESS_PROOF, DecompositionType.OR),

    // Relation stubs: one per relation type and direction

    CAUSES_INCOMING("CausesIncoming", 1),
    CAUSES_OUTGOING("CausesOutgoing", 1),
    INVERTS_INCOMING("InvertsIncoming", 1),
    INVERTS_OUTGOING("InvertsOutgoing", 1),
    REALIZES_INCOMING("RealizesIncoming", 1),
    REALIZES_OUTGOING("RealizesOutgoing", 1),
    PRODUCES_INCOMING("ProducesIncoming", 1),
    PRODUCES_OUTGOING("ProducesOutgoing", 1),
    VALIDATES_INCOMING("ValidatesIncoming", 1),
    VALIDATES_OUTGOING("ValidatesOutgoing", 1);

    private final String controlName;
    private final int arity;
    /** For gate controls: the kind of the decomposed node and the type. {@code null} for every other control. */
    private final NodeKind gateKind;
    private final DecompositionType gateType;

    POPPBigraphSignature(String controlName, int arity) {
        this(controlName, arity, null, null);
    }

    POPPBigraphSignature(String controlName, NodeKind gateKind, DecompositionType gateType) {
        this(controlName, 0, gateKind, gateType);
    }

    POPPBigraphSignature(String controlName, int arity, NodeKind gateKind, DecompositionType gateType) {
        this.controlName = controlName;
        this.arity = arity;
        this.gateKind = gateKind;
        this.gateType = gateType;
    }

    public String controlName() {
        return controlName;
    }

    public int arity() {
        return arity;
    }

    @SuppressWarnings("unchecked")
    public boolean matches(BigraphEntity.NodeEntity<?> node) {
        if (node == null) {
            return false;
        }
        return ((BigraphEntity.NodeEntity<DynamicControl>) node)
                .getControl().getNamedType().stringValue().equals(controlName);
    }

    /** The control of {@code node}. {@code null} if it is not in this signature (e.g. a coverage marker). */
    public static POPPBigraphSignature forNode(BigraphEntity.NodeEntity<?> node) {
        for (POPPBigraphSignature control : values()) {
            if (control.matches(node)) {
                return control;
            }
        }
        return null;
    }

    /** The control with this name. {@code null} if it is not in this signature. */
    public static POPPBigraphSignature valueOfControl(String controlName) {
        for (POPPBigraphSignature c : values()) if (c.controlName.equals(controlName)) return c;
        return null;
    }

    /** A gate node ({@code GoalAnd}, {@code ProblemOr}, ...). */
    public static boolean isDecompControl(POPPBigraphSignature control) {
        return control != null && control.gateType != null;
    }

    /** The kind of the tree a gate control belongs to. Empty for every other control. */
    public static Optional<NodeKind> gateKindOf(POPPBigraphSignature control) {
        return control == null ? Optional.empty() : Optional.ofNullable(control.gateKind);
    }

    /** The gate control for a decomposition of a node of {@code kind}. {@code null} for {@code NONE}. */
    public static POPPBigraphSignature getGateControl(NodeKind kind, DecompositionType type) {
        if (type == DecompositionType.NONE) return null;
        for (POPPBigraphSignature c : values()) {
            if (c.gateKind == kind && c.gateType == type) return c;
        }
        throw new IllegalArgumentException("No gate control for " + kind + " " + type);
    }

    private static final Set<POPPBigraphSignature> KIND_CONTROLS = EnumSet.of(
            PROBLEM, GOAL, CONSEQUENCE, SOLUTION, SUCCESS_CRITERIA, SUCCESS_PROOF);

    /** A domain node's control: its kind. */
    public static boolean isKindControl(POPPBigraphSignature c) { return c != null && KIND_CONTROLS.contains(c); }

    /** A node of the decomposition tree: a domain node or a gate node. */
    public static boolean isTreeControl(POPPBigraphSignature c) { return isKindControl(c) || isDecompControl(c); }

    /** A relation stub (arity 1). */
    public static boolean isStubControl(POPPBigraphSignature c) { return c != null && c.arity == 1; }

    /** The control for the given {@link NodeKind}. */
    public static POPPBigraphSignature getByNodeKind(final NodeKind kind) {
        switch (kind) {
            case PROBLEM -> { return PROBLEM; }
            case GOAL -> { return GOAL; }
            case CONSEQUENCE -> { return CONSEQUENCE; }
            case SOLUTION -> { return SOLUTION; }
            case SUCCESS_CRITERIA -> { return SUCCESS_CRITERIA; }
            default -> { return SUCCESS_PROOF; }
        }
    }

    /** The node kind of a domain-node control. Throws for every other control. */
    public static NodeKind toNodeKind(POPPBigraphSignature control) {
        return switch (control) {
            case PROBLEM -> NodeKind.PROBLEM;
            case GOAL -> NodeKind.GOAL;
            case CONSEQUENCE -> NodeKind.CONSEQUENCE;
            case SOLUTION -> NodeKind.SOLUTION;
            case SUCCESS_CRITERIA -> NodeKind.SUCCESS_CRITERIA;
            case SUCCESS_PROOF -> NodeKind.SUCCESS_PROOF;
            default -> throw new IllegalArgumentException(control + " is not a domain node control");
        };
    }

    /** The decomposition type of a gate control. {@code NONE} for every other control. */
    public static DecompositionType decompositionTypeOf(POPPBigraphSignature control) {
        return control == null || control.gateType == null ? DecompositionType.NONE : control.gateType;
    }

    /**
     * The outgoing port-stub control attached at a relation's source node,
     * for the given {@link RelationType}.
     */
    public static POPPBigraphSignature outgoingStubFor(final RelationType type) {
        return valueOf(type.name() + "_OUTGOING");
    }

    /**
     * The incoming port-stub control attached at a relation's target node,
     * for the given {@link RelationType}.
     */
    public static POPPBigraphSignature incomingStubFor(final RelationType type) {
        return valueOf(type.name() + "_INCOMING");
    }

    /** The stub control to attach at {@code relation}'s source node. */
    public static POPPBigraphSignature getSourceStub(final Relation relation) {
        return outgoingStubFor(relation.type());
    }

    /** The stub control to attach at {@code relation}'s target node. */
    public static POPPBigraphSignature getTargetStub(final Relation relation) {
        return incomingStubFor(relation.type());
    }

    /** Builds the POPP signature from the constants of this enum (control name and arity each). */
    public static DynamicSignature create() {
        var builder = pureSignatureBuilder();
        for (POPPBigraphSignature control : values()) {
            builder.newControl()
                    .identifier(control.controlName())
                    .arity(FiniteOrdinal.ofInteger(control.arity()))
                    .assign();
        }
        return builder.create();
    }
}