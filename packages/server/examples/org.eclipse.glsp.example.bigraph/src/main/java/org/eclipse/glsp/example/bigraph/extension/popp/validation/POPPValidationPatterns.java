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
    private record N(String control, String name, Link link, List<N> kids) {
        static final N SITE = new N(null, null, null, List.of());

        boolean isSite() { return control == null; }
    }

    private enum Link { OPEN, CLOSED }

    private static N n(POPPBigraphSignature c, String name, N... kids) {
        return new N(c.controlName(), name, null, List.of(kids));
    }

    private static N stub(POPPBigraphSignature c, String name, Link link) {
        return new N(c.controlName(), name, link, List.of());
    }

    // -- the conditions --------------------------------------------------------------------------------------------

    private static List<ValidationPattern> build() {
        List<ValidationPattern> out = new ArrayList<>();
        POPPBigraphSignature popp = POPPBigraphSignature.POPP;

        // A chain node directly in the container: decomposition structure that belongs to no decomposed node.
        for (POPPBigraphSignature chain : CHAINS) {
            out.add(pattern("chain-in-container-" + chain.controlName(), ValidationIssue.Code.CHAIN_OUTSIDE_DECOMPOSITION,
                    n(popp, "container", n(chain, "chain", N.SITE), N.SITE),
                    List.of("chain"),
                    s -> chain.controlName() + " node directly in the POPP container, not under a decomposed node",
                    false));
        }

        // An empty chain node: a decomposition type without decomposed children.
        for (POPPBigraphSignature chain : CHAINS) {
            String type = chain == POPPBigraphSignature.AND_DECOMP ? "AND" : "OR";
            out.add(pattern("empty-" + chain.controlName(), ValidationIssue.Code.EMPTY_DECOMPOSITION,
                    n(chain, "chain"),
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
                    n(popp, "container", stub(stub, "stub", Link.OPEN), N.SITE),
                    List.of("stub"),
                    s -> stub.controlName() + " stub directly in the POPP container, not in a node",
                    false));
            for (POPPBigraphSignature chain : CHAINS) {
                out.add(pattern("stub-in-" + chain.controlName() + "-" + stub.controlName(), ValidationIssue.Code.MISPLACED_STUB,
                        n(chain, "chain", stub(stub, "stub", Link.OPEN), N.SITE),
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
                        n(parent, "parent", n(child, "child", N.SITE), N.SITE),
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
                    n(popp, "container", n(kind, "root", N.SITE), N.SITE),
                    List.of("root"),
                    s -> s.size() + " separate " + kind.controlName() + " trees (" + String.join(", ", s)
                            + "); connect them into one tree",
                    true, 2));
        }
        return List.copyOf(out);
    }

    private static ValidationPattern pattern(String name, ValidationIssue.Code code, N root, List<String> subjects,
                                             Function<List<String>, String> message, boolean aggregate) {
        return pattern(name, code, root, subjects, message, aggregate, 1);
    }

    private static ValidationPattern pattern(String name, ValidationIssue.Code code, N root, List<String> subjects,
                                             Function<List<String>, String> message, boolean aggregate,
                                             int minOccurrences) {
        PureBigraph redex = redex(root);
        Map<String, Integer> required = new HashMap<>(census(root));
        // n occurrences of a one-node pattern need n nodes of its control
        required.replaceAll((control, count) -> control.equals(root.control()) ? count : count * minOccurrences);
        try {
            return new ValidationPattern(name, code, new ParametricReactionRule<>(redex, redex),
                    Map.copyOf(required), subjects, message, aggregate, minOccurrences);
        } catch (InvalidReactionRuleException e) {
            throw new IllegalStateException("Invalid validation pattern " + name, e);
        }
    }

    private static PureBigraph redex(N root) {
        PureBigraphMutable g = pureBuilder(SIGNATURE).createMutable();
        emit(g, g.addRoot(), root);
        return g;
    }

    private static void emit(PureBigraphMutable g, BigraphEntity<?> parent, N n) {
        if (n.isSite()) {
            g.addSite(parent);
            return;
        }
        var node = g.addNode(parent, SIGNATURE.getControlByName(n.control()), n.name());
        if (n.link() == Link.CLOSED) {
            g.connectNodeToLink(node, g.addEdge(n.name() + "-edge"));
        } else if (n.link() == Link.OPEN) {
            g.connectNodeToLink(node, g.addOuterName(n.name() + "-link"));
        }
        n.kids().forEach(k -> emit(g, node, k));
    }

    /** How many nodes of each control the redex needs. */
    private static Map<String, Integer> census(N root) {
        Map<String, Integer> counts = new HashMap<>();
        Deque<N> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            N n = todo.pop();
            if (n.isSite()) continue;
            counts.merge(n.control(), 1, Integer::sum);
            todo.addAll(n.kids());
        }
        return Map.copyOf(counts);
    }
}
