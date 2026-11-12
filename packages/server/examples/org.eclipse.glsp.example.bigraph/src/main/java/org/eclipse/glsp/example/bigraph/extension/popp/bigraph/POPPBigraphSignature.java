package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.datatypes.FiniteOrdinal;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder;

/**
 * The POPP signature, derived from the metamodel ({@link NodeKind}, {@link DecompositionType}, {@link RelationType}):
 * <ul>
 *   <li>the {@code POPP} container, which holds every tree,</li>
 *   <li>a domain control per node kind ({@code Problem}, {@code Goal}, ...),</li>
 *   <li>a gate control per kind and decomposition type ({@code GoalAnd}, {@code ProblemOr}, ...): an n-ary
 *       decomposition becomes a chain of binary gate nodes under the decomposed node, and the control says which tree
 *       a gate node belongs to. The topmost gate node is the one directly under a domain node,</li>
 *   <li>two stub controls of arity 1 per relation type ({@code CausesOutgoing} under the source,
 *       {@code CausesIncoming} under the target), linked by the relation's edge.</li>
 * </ul>
 * A new node kind or relation type therefore needs no change here. The control names are stored in the bigraph files.
 * The coverage analysis adds its markers on a copy of this signature ({@code CoverageSignature}).
 */
public final class POPPBigraphSignature {

    public enum Role { CONTAINER, DOMAIN, GATE, STUB }

    /**
     * One control.
     *
     * @param kind     the node kind of a domain control, the kind of the tree of a gate control, otherwise {@code null}
     * @param gateType the decomposition type of a gate control, otherwise {@code null}
     * @param relation the relation type of a stub control, otherwise {@code null}
     * @param outgoing for a stub control: whether it is attached at the source of the relation
     */
    public record Control(String controlName, int arity, Role role, NodeKind kind, DecompositionType gateType,
                          RelationType relation, boolean outgoing) {

        public boolean isDomain() { return role == Role.DOMAIN; }
        public boolean isGate() { return role == Role.GATE; }
        public boolean isStub() { return role == Role.STUB; }
        /** A node of a decomposition tree: a domain node or a gate node. */
        public boolean isTree() { return isDomain() || isGate(); }

        /** The kind of the tree a domain or gate node belongs to. Empty for the container and stubs. */
        public Optional<NodeKind> treeKind() { return Optional.ofNullable(kind); }

        /** The decomposition type of a gate control, {@code NONE} for every other control. */
        public DecompositionType decompositionType() { return gateType == null ? DecompositionType.NONE : gateType; }

        public boolean matches(BigraphEntity.NodeEntity<?> node) {
            return node != null && node.getControl().getNamedType().stringValue().equals(controlName);
        }

        /** The control as a constant name, e.g. {@code CAUSES_OUTGOING}. Stub ids start with it. */
        @Override
        public String toString() {
            return constantName(role, kind, gateType, relation, outgoing);
        }
    }

    public static final Control CONTAINER = new Control("POPP", 0, Role.CONTAINER, null, null, null, false);

    private static final List<DecompositionType> GATE_TYPES = List.of(DecompositionType.AND, DecompositionType.OR);
    private static final Map<String, Control> BY_NAME = derive();

    private POPPBigraphSignature() {}

    private static Map<String, Control> derive() {
        List<Control> controls = new ArrayList<>();
        controls.add(CONTAINER);
        for (NodeKind kind : NodeKind.values()) controls.add(control(0, Role.DOMAIN, kind, null, null, false));
        for (NodeKind kind : NodeKind.values()) {
            for (DecompositionType type : GATE_TYPES) controls.add(control(0, Role.GATE, kind, type, null, false));
        }
        for (RelationType type : RelationType.values()) {
            controls.add(control(1, Role.STUB, null, null, type, false));
            controls.add(control(1, Role.STUB, null, null, type, true));
        }
        Map<String, Control> byName = new LinkedHashMap<>();
        for (Control control : controls) byName.put(control.controlName(), control);
        return byName;
    }

    private static String constantName(Role role, NodeKind kind, DecompositionType gateType, RelationType relation,
                                       boolean outgoing) {
        return switch (role) {
            case CONTAINER -> "POPP";
            case DOMAIN -> kind.name();
            case GATE -> kind.name() + "_" + gateType.name();
            case STUB -> relation.name() + (outgoing ? "_OUTGOING" : "_INCOMING");
        };
    }

    /** A control named after its constant name in camel case, e.g. {@code SUCCESS_CRITERIA_AND -> SuccessCriteriaAnd}. */
    private static Control control(int arity, Role role, NodeKind kind, DecompositionType gateType,
                                   RelationType relation, boolean outgoing) {
        String name = Arrays.stream(constantName(role, kind, gateType, relation, outgoing).split("_"))
                .map(word -> word.charAt(0) + word.substring(1).toLowerCase())
                .collect(Collectors.joining());
        return new Control(name, arity, role, kind, gateType, relation, outgoing);
    }

    /** Every control, in a fixed order: the container, the domain controls, the gate controls, the stubs. */
    public static List<Control> controls() {
        return List.copyOf(BY_NAME.values());
    }

    /** The control of {@code node}. Empty if it is not in this signature (e.g. a coverage marker). */
    public static Optional<Control> forNode(BigraphEntity.NodeEntity<?> node) {
        return Optional.ofNullable(BY_NAME.get(node.getControl().getNamedType().stringValue()));
    }

    /** The domain control of {@code kind}. */
    public static Control of(NodeKind kind) {
        return find(control -> control.isDomain() && control.kind() == kind);
    }

    /** The gate control for a decomposition of type {@code type} (AND or OR) of a node of {@code kind}. */
    public static Control gate(NodeKind kind, DecompositionType type) {
        return find(control -> control.isGate() && control.kind() == kind && control.gateType() == type);
    }

    /** The stub control at the source ({@code outgoing}) or at the target of a relation of {@code type}. */
    public static Control stub(RelationType type, boolean outgoing) {
        return find(control -> control.isStub() && control.relation() == type && control.outgoing() == outgoing);
    }

    private static Control find(Predicate<Control> condition) {
        return BY_NAME.values().stream().filter(condition).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such POPP control"));
    }

    /** Builds the POPP signature from {@link #controls()}. */
    public static DynamicSignature create() {
        var builder = pureSignatureBuilder();
        for (Control control : controls()) {
            builder.newControl().identifier(control.controlName()).arity(FiniteOrdinal.ofInteger(control.arity())).assign();
        }
        return builder.create();
    }
}
