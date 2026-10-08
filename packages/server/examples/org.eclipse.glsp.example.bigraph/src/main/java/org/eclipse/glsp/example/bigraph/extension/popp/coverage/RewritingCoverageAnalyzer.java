package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import it.uniud.mads.jlibbig.core.std.AgentMatch;
import it.uniud.mads.jlibbig.core.std.AgentMatcher;
import it.uniud.mads.jlibbig.core.std.Bigraph;
import it.uniud.mads.jlibbig.core.std.Signature;
import org.bigraphs.framework.converter.jlibbig.JLibBigBigraphEncoder;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageMarker;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageRewriteRules;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageRewriteRules.CompiledRule;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

/**
 * Coverage analysis by forward chaining over a bigraph with reaction rules, evaluated with the matcher only.
 * This is the executable form of the coverage rules in {@link CoverageRewriteRules}; it is a reference model for
 * them (the tests check that it agrees with {@link DomainCoverageAnalyzer}), not the analyzer to use at scale.
 * <p>
 * <b>Cost.</b> jLibBig's {@link AgentMatcher} solves a constraint model per call whose size grows with the agent,
 * so a session costs roughly (matcher calls) x (agent size)^1.5..2: about 60 ms for a handful of nodes, about 4 s for
 * 48 POPP nodes. {@link DomainCoverageAnalyzer} does the same job in microseconds.
 * <p>
 * <b>Why the matcher only.</b> The framework's {@code PureReactiveSystem.buildParametricReaction} is deliberately not
 * used: it corrupts links that cross the parameter/context boundary and its relabeling asserts on the tracking map.
 * All rules are monotone state upgrades, so a rule's effect is applied directly from its match: the agent node the
 * redex's receiver node was embedded into gets the rule's target state and reason marker.
 * <p>
 * <b>Evaluation.</b> Rounds: build the scratch bigraph from the current states, match every rule against that
 * snapshot, apply all effects (never lowering a state), repeat until a round changes nothing. Stale matches within a
 * round stay sound because states only increase and every rule's target is monotone in the states it reads. The agent
 * is encoded once per round and every redex once, at construction. Two cheap necessary conditions skip matcher calls
 * that cannot succeed: a node-count census per control, and for link rules a look at the relation graph.
 */
public final class RewritingCoverageAnalyzer implements CoverageAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(RewritingCoverageAnalyzer.class);

    private static final List<CompiledRule> RULES = CoverageRewriteRules.compile();

    /** A rule with its redex already encoded for jLibBig, and the node count per control the redex needs. */
    private record Prepared(CompiledRule rule, Bigraph jRedex, boolean[] neededParam, Map<String, Integer> required) {}

    private final Supplier<POPPBigraph> structure;
    private final RelationGraph relations;

    // Per instance, not static: nothing encoded is shared between analyzers (or threads).
    private final JLibBigBigraphEncoder encoder = new JLibBigBigraphEncoder();
    private final Signature jlibSignature;
    private final List<Prepared> prepared;

    public RewritingCoverageAnalyzer(Supplier<POPPBigraph> structure, RelationGraph relations) {
        this.structure = structure;
        this.relations = relations;

        // Every bigraph here uses CoverageSignature, so the jLibBig signature can come from any of them.
        this.jlibSignature = encoder.encode(RULES.getFirst().rule().getRedex()).getSignature();
        List<Prepared> encoded = new ArrayList<>();
        for (CompiledRule r : RULES) {
            PureBigraph redex = r.rule().getRedex();
            boolean[] needed = new boolean[redex.getSites().size()];
            Arrays.fill(needed, true);
            Bigraph jRedex = encoder.encode(redex, jlibSignature);
            encoded.add(new Prepared(r, jRedex, needed, census(jRedex)));
        }
        this.prepared = List.copyOf(encoded);
    }

    @Override
    public CoverageSession session(CoverageMode mode) {
        // Seed with intrinsic coverage straight from a freshly built bigraph (no rewriting involved, links intact).
        // The same bigraph is round 1's snapshot, since the seeded states equal the defaults it was built with.
        PureBigraph agent = CoverageBigraph.build(structure.get(), mode, Map.of());
        Readout initial = Readout.of(agent);
        Map<String, Coverage> states = new HashMap<>(initial.coverage());
        Map<String, Derivation> why = new HashMap<>(initial.derivation());

        int roundBudget = 2 * agent.getNodes().size() + 2;
        AgentMatcher agentMatcher = new AgentMatcher();
        int rounds = 0, matcherCalls = 0;

        for (boolean changed = true; changed; ) {
            if (--roundBudget < 0) throw new IllegalStateException("Coverage evaluation did not terminate");
            changed = false;
            rounds++;

            Bigraph jAgent = encoder.encode(agent, jlibSignature);
            Map<String, Integer> available = census(jAgent);
            Map<String, Coverage> snapshot = new HashMap<>(states);

            for (Prepared p : prepared) {
                CompiledRule rule = p.rule();
                if (!mayMatch(p.required(), available)) continue;
                if (rule.isLinkRule() && !linkPossible(rule, snapshot)) continue;

                matcherCalls++;
                for (AgentMatch match : each(agentMatcher.match(jAgent, p.jRedex(), p.neededParam().clone()))) {
                    Map<String, String> embedding = embedding(match);
                    String receiver = resolve(embedding, rule.receiverNode(), rule);
                    Coverage current = states.getOrDefault(receiver, Coverage.UNCOVERED);
                    if (rule.target().compareTo(current) <= 0) continue;

                    String giver = rule.giverNode() == null ? null : resolve(embedding, rule.giverNode(), rule);
                    states.put(receiver, rule.target());
                    why.put(receiver, new Derivation(rule.reason(), giver));
                    changed = true;
                }
            }

            if (changed) agent = CoverageBigraph.build(structure.get(), mode, states);
        }
        logger.debug("[session {}] {} rounds, {} matcher calls", mode, rounds, matcherCalls);
        return new RewritingSession(mode, relations, new Readout(states, why));
    }

    /** Number of nodes per control name. */
    private static Map<String, Integer> census(Bigraph b) {
        Map<String, Integer> counts = new HashMap<>();
        for (var node : b.getNodes()) counts.merge(node.getControl().getName(), 1, Integer::sum);
        return counts;
    }

    /**
     * A redex can only embed into an agent that has at least as many nodes of every control. A matcher call that
     * cannot succeed still builds a whole constraint model (~2 ms and growing with the agent), so this is checked first.
     */
    private static boolean mayMatch(Map<String, Integer> required, Map<String, Integer> available) {
        for (var need : required.entrySet()) {
            if (available.getOrDefault(need.getKey(), 0) < need.getValue()) return false;
        }
        return true;
    }

    /**
     * Necessary condition for a link rule, checked on the relation graph instead of by the matcher: some relation of
     * the rule's type has a giver in the state the redex needs and a receiver in the state it upgrades.
     */
    private boolean linkPossible(CompiledRule rule, Map<String, Coverage> snapshot) {
        boolean forward = rule.relation().getCoveragePropagation() == RelationType.CoveragePropagation.FORWARD;
        for (Relation r : relations.all()) {
            if (r.type() != rule.relation()) continue;
            TreeNode<?> giver = forward ? r.source() : r.target();
            TreeNode<?> receiver = forward ? r.target() : r.source();
            if (snapshot.getOrDefault(giver.getId(), Coverage.UNCOVERED) == rule.giverState()
                    && snapshot.getOrDefault(receiver.getId(), Coverage.UNCOVERED) == rule.receiverState()) {
                return true;
            }
        }
        return false;
    }

    // jLibBig's AgentMatcher#match returns an Iterable or an Iterator depending on the version; accept either.
    @SuppressWarnings("unchecked")
    private static Iterable<AgentMatch> each(Iterable<? extends AgentMatch> matches) {
        return (Iterable<AgentMatch>) matches;
    }

    @SuppressWarnings("unchecked")
    private static Iterable<AgentMatch> each(Iterator<? extends AgentMatch> matches) {
        return () -> (Iterator<AgentMatch>) matches;
    }

    /** Redex node name -> agent node name for one match. */
    private static Map<String, String> embedding(AgentMatch match) {
        Map<String, String> byRedexName = new HashMap<>();
        match.emb_nodes.forEach((redexNode, agentNode) ->
                byRedexName.put(redexNode.getEditable().getName(), agentNode.getEditable().getName()));
        return byRedexName;
    }

    private static String resolve(Map<String, String> embedding, String redexNode, CompiledRule rule) {
        String agentNode = embedding.get(redexNode);
        if (agentNode == null) {
            throw new IllegalStateException("Rule " + rule.name() + ": redex node '" + redexNode
                    + "' is not in the match embedding " + embedding.keySet());
        }
        return agentNode;
    }

    private final class RewritingSession extends AbstractCoverageSession {
        private final Readout readout;

        RewritingSession(CoverageMode mode, RelationGraph relations, Readout readout) {
            super(mode, relations);
            this.readout = readout;
        }

        @Override
        public Coverage coverage(TreeNode<?> node) {
            Coverage c = readout.coverage().get(node.getId());
            if (c == null) throw new IllegalStateException("Node missing from the coverage bigraph: " + node);
            return c;
        }

        @Override
        public CoverageReason explain(TreeNode<?> node) {
            Coverage state = coverage(node);
            Derivation d = readout.derivation().get(node.getId());

            if (d != null) {
                return switch (d.marker()) {
                    case CoverageMarker.COV_INTRINSIC -> new CoverageReason.Intrinsic(node, Coverage.COVERED);
                    case CoverageMarker.PART_DECOMP, CoverageMarker.COV_DECOMP -> decompositionReason(node, state);
                    case CoverageMarker.PART_LINK, CoverageMarker.COV_LINK -> {
                        Relation r = relations.all().stream()
                                .filter(x -> giverOf(x).getId().equals(d.giverId())
                                        && receiverOf(x).getId().equals(node.getId()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("No relation from " + d.giverId() + " to " + node.getId()));
                        yield linkReason(node, state, r, giverOf(r));
                    }
                    default -> throw new IllegalStateException("Not a reason marker: " + d.marker());
                };
            }

            if (!node.getChildren().isEmpty()) return decompositionReason(node, Coverage.UNCOVERED);
            return candidates(node).stream().findFirst()
                    .<CoverageReason>map(c -> linkReason(node, Coverage.UNCOVERED, c.relation(), c.node()))
                    .orElseGet(() -> new CoverageReason.NotCovered(node, CoverageGap.remediesFor(node.getKind())));
        }
    }

    record Derivation(CoverageMarker marker, String giverId) {}

    record Readout(Map<String, Coverage> coverage, Map<String, Derivation> derivation) {
        /** Reads state cells off a bigraph. Only valid on a freshly built bigraph, never on a rewritten one. */
        static Readout of(PureBigraph g) {
            Map<String, Coverage> cov = new HashMap<>();
            Map<String, Derivation> why = new HashMap<>();
            for (BigraphEntity.NodeEntity<?> cell : g.getNodes()) {
                Optional<Coverage> state = CoverageMarker.forNode(cell).flatMap(CoverageMarker::state);
                if (state.isEmpty()) continue;
                var owner = (BigraphEntity.NodeEntity<?>) g.getParent(cell);
                boolean isDomain = false;
                Derivation d = null;
                for (BigraphEntity<?> sib : g.getChildrenOf(owner)) {
                    if (!(sib instanceof BigraphEntity.NodeEntity<?> s)) continue;
                    String ctrl = s.getControl().getNamedType().stringValue();
                    if (ctrl.equals(POPPBigraphSignature.DOM.controlName())) { isDomain = true; continue; }

                    Optional<CoverageMarker> m = CoverageMarker.forNode(s).filter(x -> x.belongsTo(state.get()));
                    if (m.isEmpty()) continue;
                    String giver = null;
                    if (m.get() == CoverageMarker.PART_LINK || m.get() == CoverageMarker.COV_LINK) {
                        var link = g.getLinkOfPoint(g.getPorts(s).get(0));
                        for (BigraphEntity<?> pt : g.getPointsFromLink(link)) {
                            if (!(pt instanceof BigraphEntity.Port port)) continue;
                            var pointNode = g.getNodeOfPort(port);
                            if (pointNode == null || pointNode == s) continue;
                            var pointOwner = g.getParent(pointNode);
                            if (pointOwner instanceof BigraphEntity.NodeEntity<?> po && !po.getName().equals(owner.getName())) {
                                giver = po.getName();
                            }
                        }
                    }
                    d = new Derivation(m.get(), giver);
                }

                if (isDomain) { cov.put(owner.getName(), state.get()); if (d != null) why.put(owner.getName(), d); }
            }
            return new Readout(cov, why);
        }
    }
}