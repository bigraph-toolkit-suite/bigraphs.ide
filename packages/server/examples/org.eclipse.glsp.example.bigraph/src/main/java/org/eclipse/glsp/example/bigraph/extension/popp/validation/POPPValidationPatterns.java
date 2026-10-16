package org.eclipse.glsp.example.bigraph.extension.popp.validation;

import org.bigraphs.framework.core.exceptions.InvalidReactionRuleException;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;

import java.util.*;
import java.util.function.Function;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;

/**
 * The structural well-formedness conditions of the POPP encoding, each as a pattern whose occurrence is a defect.
 *
 * <p>The patterns rely on two properties of bigraph matching: a redex node without a site only matches an agent node
 * with exactly the redex node's content (so {@code AndDecomp} without children matches only an empty chain node), and a
 * closed edge of the redex only matches an agent edge with exactly the same points (so a stub on a one-point edge
 * matches only a stub without partner). Siblings of a redex root belong to the context, so a single-node redex
 * matches the node wherever it is; anchoring in the {@code POPP} container restricts a pattern to the top level.</p>
 */
public final class POPPValidationPatterns {

    /** The signature the patterns and the validator's snapshots are built over. */
    public static final DynamicSignature SIGNATURE = POPPBigraphSignature.create();

    private static final List<POPPBigraphSignature> KINDS = List.of(
            POPPBigraphSignature.PROBLEM, POPPBigraphSignature.GOAL, POPPBigraphSignature.CONSEQUENCE,
            POPPBigraphSignature.SOLUTION, POPPBigraphSignature.SUCCESS_CRITERIA, POPPBigraphSignature.SUCCESS_PROOF);
    private static final List<POPPBigraphSignature> CHAINS = List.of(
            POPPBigraphSignature.AND_DECOMP, POPPBigraphSignature.OR_DECOMP);
    private static final List<POPPBigraphSignature> STUBS = Arrays.stream(POPPBigraphSignature.values())
            .filter(POPPBigraphSignature::isStubControl).toList();

    private static final List<ValidationPattern> ALL = build();

    private POPPValidationPatterns() {}

    public static List<ValidationPattern> all() {
        return ALL;
    }

    // -- pattern tree --------------------------------------------------------------------------------------------

    /** A redex node: control, name, optional link ({@code OPEN} outer name or {@code CLOSED} edge), children. */
    private record Node(String control, String name, Link link, List<Node> children) {
        static final Node SITE = new Node(null, null, null, List.of());

        boolean isSite() { return control == null; }
    }

    private enum Link { OPEN, CLOSED }

    private static Node node(POPPBigraphSignature c, String name, Node... children) {
        return new Node(c.controlName(), name, null, List.of(children));
    }

    private static Node stub(POPPBigraphSignature c, String name, Link link) {
        return new Node(c.controlName(), name, link, List.of());
    }

    // -- the conditions --------------------------------------------------------------------------------------------

    private static List<ValidationPattern> build() {
        List<ValidationPattern> out = new ArrayList<>();
        POPPBigraphSignature popp = POPPBigraphSignature.POPP;

        // A chain node directly in the container: decomposition structure that belongs to no decomposed node.
        for (POPPBigraphSignature chain : CHAINS) {
            out.add(pattern("chain-in-container-" + chain.controlName(), ValidationIssue.Code.CHAIN_OUTSIDE_DECOMPOSITION,
                    node(popp, "container", node(chain, "chain", Node.SITE), Node.SITE),
                    List.of("chain"),
                    s -> chain.controlName() + " node directly in the POPP container, not under a decomposed node",
                    false));
        }

        // An empty chain node: a decomposition type without decomposed children.
        for (POPPBigraphSignature chain : CHAINS) {
            String type = chain == POPPBigraphSignature.AND_DECOMP ? "AND" : "OR";
            out.add(pattern("empty-" + chain.controlName(), ValidationIssue.Code.EMPTY_DECOMPOSITION,
                    node(chain, "chain"),
                    List.of("chain"),
                    s -> s.getFirst() + " has an " + type + " decomposition without children",
                    false));
        }

        for (POPPBigraphSignature stub : STUBS) {
            // A stub whose edge has no second point: one end of a relation is missing.
            out.add(pattern("dangling-" + stub.controlName(), ValidationIssue.Code.DANGLING_STUB,
                    stub(stub, "stub", Link.CLOSED),
                    List.of("stub"),
                    s -> s.getFirst() + ": " + stub.controlName() + " stub without a partner (relation end missing)",
                    false));
            // Stubs belong directly inside domain nodes, never in the container or in a chain node.
            out.add(pattern("stub-in-container-" + stub.controlName(), ValidationIssue.Code.MISPLACED_STUB,
                    node(popp, "container", stub(stub, "stub", Link.OPEN), Node.SITE),
                    List.of("stub"),
                    s -> stub.controlName() + " stub directly in the POPP container, not in a node",
                    false));
            for (POPPBigraphSignature chain : CHAINS) {
                out.add(pattern("stub-in-" + chain.controlName() + "-" + stub.controlName(), ValidationIssue.Code.MISPLACED_STUB,
                        node(chain, "chain", stub(stub, "stub", Link.OPEN), Node.SITE),
                        List.of("stub"),
                        s -> s.getFirst() + ": " + stub.controlName() + " stub inside a decomposition node instead of the node itself",
                        false));
            }
        }

        // A domain node directly inside another one: a child attached without the decomposition chain.
        for (POPPBigraphSignature parent : KINDS) {
            for (POPPBigraphSignature child : KINDS) {
                out.add(pattern("direct-" + parent.controlName() + "-" + child.controlName(),
                        ValidationIssue.Code.CHILD_WITHOUT_DECOMPOSITION,
                        node(parent, "parent", node(child, "child", Node.SITE), Node.SITE),
                        List.of("parent", "child"),
                        s -> s.get(1) + " is attached directly to " + s.getFirst()
                                + (parent == child ? " without a decomposition node" : " (a different kind)"),
                        false));
            }
        }

        // Two roots of the same kind: the model has more than one tree of that kind. The redex is one root, so it has
        // one occurrence per root (a redex with two roots would have one per ordered pair); the defect is two or more.
        for (POPPBigraphSignature kind : KINDS) {
            out.add(pattern("trees-" + kind.controlName(), ValidationIssue.Code.MULTIPLE_TREES,
                    node(popp, "container", node(kind, "root", Node.SITE), Node.SITE),
                    List.of("root"),
                    s -> s.size() + " separate " + kind.controlName() + " trees (" + String.join(", ", s)
                            + "); connect them into one tree",
                    true, 2));
        }
        return List.copyOf(out);
    }

    private static ValidationPattern pattern(String name, ValidationIssue.Code code, Node root, List<String> subjects,
                                             Function<List<String>, String> message, boolean aggregate) {
        return pattern(name, code, root, subjects, message, aggregate, 1);
    }

    private static ValidationPattern pattern(String name, ValidationIssue.Code code, Node root, List<String> subjects,
                                             Function<List<String>, String> message, boolean aggregate,
                                             int minOccurrences) {
        PureBigraph redex = redex(root);
        Map<String, Integer> required = new HashMap<>(census(root));
        // n occurrences need n nodes of every control below the redex root (the root itself, e.g. the container, is
        // shared by all occurrences)
        required.replaceAll((control, count) -> control.equals(root.control()) ? count : count * minOccurrences);
        try {
            return new ValidationPattern(name, code, new ParametricReactionRule<>(redex, redex),
                    Map.copyOf(required), subjects, message, aggregate, minOccurrences);
        } catch (InvalidReactionRuleException e) {
            throw new IllegalStateException("Invalid validation pattern " + name, e);
        }
    }

    private static PureBigraph redex(Node root) {
        PureBigraphMutable bigraph = pureBuilder(SIGNATURE).createMutable();
        emit(bigraph, bigraph.addRoot(), root);
        return bigraph;
    }

    private static void emit(PureBigraphMutable bigraph, BigraphEntity<?> parent, Node node) {
        if (node.isSite()) {
            bigraph.addSite(parent);
            return;
        }
        var bigraphNode = bigraph.addNode(parent, SIGNATURE.getControlByName(node.control()), node.name());
        if (node.link() == Link.CLOSED) {
            bigraph.connectNodeToLink(bigraphNode, bigraph.addEdge(node.name() + "-edge"));
        } else if (node.link() == Link.OPEN) {
            bigraph.connectNodeToLink(bigraphNode, bigraph.addOuterName(node.name() + "-link"));
        }
        node.children().forEach(child -> emit(bigraph, bigraphNode, child));
    }

    /** How many nodes of each control the redex needs. */
    private static Map<String, Integer> census(Node root) {
        Map<String, Integer> counts = new HashMap<>();
        Deque<Node> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            Node node = todo.pop();
            if (node.isSite()) continue;
            counts.merge(node.control(), 1, Integer::sum);
            todo.addAll(node.children());
        }
        return Map.copyOf(counts);
    }
}
