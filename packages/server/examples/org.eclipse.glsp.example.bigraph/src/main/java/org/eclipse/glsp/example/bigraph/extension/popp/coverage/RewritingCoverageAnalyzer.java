package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import it.uniud.mads.jlibbig.core.std.Match;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatch;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatcher;
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
 * <b>Matching.</b> Through the Bigraph Framework's {@link PureBigraphMatcher} on the whole coverage bigraph
 * ({@link CoverageBigraph}). The node embedding of a match (which agent node is the receiver, and the giver) is read
 * from the framework's match result. Underneath, the framework encodes agent and redex for jLibBig and solves a
 * constraint model per call whose size grows with the agent, so a session costs roughly
 * (matcher calls) x (agent size)^1.5..2. {@link DomainCoverageAnalyzer} does the same job in microseconds;
 * {@link FragmentRewritingCoverageAnalyzer} evaluates the same rules on local fragments and is linear in the model.
 * <p>
 * <b>Why the matcher only.</b> The framework's {@code PureReactiveSystem.buildParametricReaction} is deliberately not
 * used: it corrupts links that cross the parameter/context boundary and its relabeling asserts on the tracking map.
 * All rules are monotone state upgrades, so a rule's effect is applied directly from its match: the agent node the
 * redex's receiver node was embedded into gets the rule's target state and reason marker.
 * <p>
 * <b>Evaluation.</b> Rounds: build the scratch bigraph from the current states, match every rule against that
 * snapshot, apply all effects (never lowering a state), repeat until a round changes nothing. Stale matches within a
 * round stay sound because states only increase and every rule's target is monotone in the states it reads.
 * <p>
 * <b>Pruning</b> ({@link Pruning}): two cheap necessary conditions skip matcher calls that cannot succeed. The
 * <i>census</i> compares the number of nodes per control the redex needs with the agent's; the <i>link guard</i> asks
 * the relation graph whether some relation of a link rule's type has a giver and a receiver in the states the redex
 * needs. Without pruning, every rule is matched in every round.
 */
public final class RewritingCoverageAnalyzer implements CoverageAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(RewritingCoverageAnalyzer.class);

    private static final List<CompiledRule> RULES = CoverageRewriteRules.compile();

    /** Which necessary conditions are checked before a matcher call. */
    public enum Pruning {
        /** Every rule is matched in every round. */
        NONE(false, false),
        /** Only the node-count census per control. */
        CENSUS(true, false),
        /** Census and, for link rules, the link guard on the relation graph. */
        FULL(true, true);

        final boolean census, linkGuard;

        Pruning(boolean census, boolean linkGuard) { this.census = census; this.linkGuard = linkGuard; }
    }

    /** A rule and the node count per control its redex needs. */
    private record Prepared(CompiledRule rule, Map<String, Integer> required) {}

    private final Supplier<POPPBigraph> structure;
    private final RelationGraph relations;
    private final List<Prepared> prepared;
    private final Pruning pruning;

    public RewritingCoverageAnalyzer(Supplier<POPPBigraph> structure, RelationGraph relations) {
        this(structure, relations, Pruning.FULL);
    }

    public RewritingCoverageAnalyzer(Supplier<POPPBigraph> structure, RelationGraph relations, Pruning pruning) {
        this.structure = structure;
        this.relations = relations;
        this.pruning = pruning;
        List<Prepared> list = new ArrayList<>();
        for (CompiledRule r : RULES) list.add(new Prepared(r, census(r.rule().getRedex())));
        this.prepared = List.copyOf(list);
    }

    private volatile RewritingStats lastStats;

    /** Counters of the most recent session (for profiling; not synchronised across concurrent sessions). */
    public RewritingStats lastStats() { return lastStats; }

    @Override
    public CoverageSession session(CoverageMode mode) {
        // Seed with intrinsic coverage straight from a freshly built bigraph (no rewriting involved, links intact).
        // The same bigraph is round 1's snapshot, since the seeded states equal the defaults it was built with.
        PureBigraph agent = CoverageBigraph.build(structure.get(), mode, Map.of());
        Readout initial = Readout.of(agent);
        Map<String, Coverage> states = new HashMap<>(initial.coverage());
        Map<String, Derivation> why = new HashMap<>(initial.derivation());

        int roundBudget = 2 * agent.getNodes().size() + 2;
        PureBigraphMatcher matcher = new PureBigraphMatcher();
        int rounds = 0, matcherCalls = 0, successfulCalls = 0;
        long tBuild = 0, tEncode = 0, tMatch = 0, t0;
        int agentNodes = agent.getNodes().size();

        for (boolean changed = true; changed; ) {
            if (--roundBudget < 0) throw new IllegalStateException("Coverage evaluation did not terminate");
            changed = false;
            rounds++;

            Map<String, Integer> available = census(agent);
            Map<String, Coverage> snapshot = new HashMap<>(states);

            for (Prepared p : prepared) {
                CompiledRule rule = p.rule();
                if (pruning.census && !mayMatch(p.required(), available)) continue;
                if (pruning.linkGuard && rule.isLinkRule() && !linkPossible(rule, snapshot)) continue;

                matcherCalls++;
                t0 = System.nanoTime();
                boolean any = false;
                // the framework encodes agent and redex for jLibBig inside the call; that time counts as matching
                for (PureBigraphMatch match : matcher.match(agent, rule.rule())) {
                    any = true;
                    Map<String, String> embedding = embedding(match.getJLibMatchResult());
                    String receiver = resolve(embedding, rule.receiverNode(), rule);
                    Coverage current = states.getOrDefault(receiver, Coverage.UNCOVERED);
                    if (rule.target().compareTo(current) <= 0) continue;

                    String giver = rule.giverNode() == null ? null : resolve(embedding, rule.giverNode(), rule);
                    states.put(receiver, rule.target());
                    why.put(receiver, new Derivation(rule.reason(), giver));
                    changed = true;
                }
                tMatch += System.nanoTime() - t0;
                if (any) successfulCalls++;
            }

            if (changed) {
                t0 = System.nanoTime();
                agent = CoverageBigraph.build(structure.get(), mode, states);
                tBuild += System.nanoTime() - t0;
            }
        }
        lastStats = new RewritingStats(rounds, matcherCalls, successfulCalls, agentNodes, tBuild, tEncode, tMatch);
        logger.debug("[session {}] {}", mode, lastStats);
        return new RewritingSession(mode, relations, new Readout(states, why));
    }

    /** Number of nodes per control name. */
    private static Map<String, Integer> census(PureBigraph b) {
        Map<String, Integer> counts = new HashMap<>();
        for (var node : b.getNodes()) counts.merge(node.getControl().getNamedType().stringValue(), 1, Integer::sum);
        return counts;
    }

    /**
     * A redex can only embed into an agent that has at least as many nodes of every control. A matcher call that
     * cannot succeed still builds a whole constraint model (growing with the agent), so this is checked first.
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

    /** Redex node name -> agent node name for one match. */
    private static Map<String, String> embedding(Match match) {
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
                boolean isDomain = POPPBigraphSignature.isKindControl(POPPBigraphSignature.forNode(owner));
                Derivation d = null;
                for (BigraphEntity<?> sib : g.getChildrenOf(owner)) {
                    if (!(sib instanceof BigraphEntity.NodeEntity<?> s)) continue;

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