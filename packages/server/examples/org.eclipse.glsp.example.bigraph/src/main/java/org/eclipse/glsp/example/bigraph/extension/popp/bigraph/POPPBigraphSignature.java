package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.datatypes.FiniteOrdinal;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Relation;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;

import static org.bigraphs.framework.core.factory.BigraphFactory.kindBuilder;
import static org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder;

/**
 * Initial POPP-specific {@link DynamicSignature}, expressed as an enum of controls.
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
public enum POPPBigraphSignature {

    POPP("POPP", 0),

    PROBLEM("Problem", 0),
    GOAL("Goal", 0),
    CONSEQUENCE("Consequence", 0),
    SOLUTION("Solution", 0),
    SUCCESS_CRITERIA("SuccessCriteria", 0),
    SUCCESS_PROOF("SuccessProof", 0),

    AND_DECOMP("AndDecomp", 0),
    OR_DECOMP("OrDecomp", 0),

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

    POPPBigraphSignature(String controlName, int arity) {
        this.controlName = controlName;
        this.arity = arity;
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

    /** The control for the given {@link DecompositionType} */
    public static POPPBigraphSignature getByDecompositionType(final DecompositionType decompositionType) {
        switch (decompositionType) {
            case OR -> { return OR_DECOMP; }
            case AND -> { return AND_DECOMP; }
            default -> { return null; }
        }
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