package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatch;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatcher;
import org.bigraphs.framework.simulation.matching.pure.PureReactiveSystem;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageRewriteRules.CompiledRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;

/**
 * Coverage analysis by forward chaining with the coverage rules ({@link CoverageRewriteRules}) over the whole coverage
 * bigraph ({@link CoverageBigraph}), matched with the Bigraph Framework's {@link PureBigraphMatcher} (jLibBig
 * underneath), applied by rewriting. Evaluates every session from scratch; the POPP extension uses it through
 * {@link IncrementalRewritingCoverageAnalyzer}, which keeps the fixpoint bigraph and only re-derives what an edit affected.
 * <p>
 * Which rule to match when is decided from what the rules themselves say, not from tables over the model: every
 * decision not to call the matcher for a rule follows from the rule set (a static analysis), from the mode, or from a
 * matcher call on a smaller pattern (a guard).
 * <p>
 * <b>Rule worklist.</b> A static analysis of the rules computes which rules a rule <i>enables</i>: {@code r} enables
 * {@code s} if {@code r}'s receiver as {@code r} leaves it (new state and reason marker) can be glued onto a node of
 * {@code s}'s redex that needs something {@code r} created ({@link CoverageRewriteRules#enablingByOverlap()}, a
 * produce-use dependency as in critical pair analysis; a node without a site has exactly its children, and a node has
 * one state). Evaluation runs in waves: a wave takes the queued rules in turn and rewrites with each until it has no
 * match; a rule that rewrote something queues every rule it enables for the next wave, each at most once; a rule
 * without a match is dropped, since only an enabling rule can give it one.
 * <p>
 * <b>Mode.</b> A rule whose receiver kind no coverage can reach in the mode ({@link CoverageMode#raisableKinds()}), or
 * a link rule whose giver kind is neither intrinsic nor reachable, can never match in that mode and is never queued.
 * <p>
 * <b>Guards.</b> Each rule has a guard: its receiver node with its marker children and a site, e.g.
 * {@code Goal[Unc | -]} ("some goal is still uncovered") or {@code GoalAnd[Part | -]}. The guard embeds into the rule's
 * redex, and embeddings compose, so if the guard has no match in the agent, the rule has none either. Rules share
 * guards (36 guards for 147 rules), and a guard is matched at most once per wave, so one matcher call can rule out a
 * whole group.
 * <p>
 * <b>Seed.</b> The rules queued at the start. On a freshly built coverage bigraph only link rules can match first: the
 * trees of intrinsic kinds start covered (gate nodes included, see {@link CoverageBigraph}) and all others uncovered,
 * so no gate or lift rule has an input until a link rule raised something.
 * <p>
 * <b>Applying the rules.</b> Every match is turned into the next agent by the framework's
 * {@code PureReactiveSystem.buildParametricReaction} (context, instantiated reactum and parameters composed), and the
 * states and reasons are read off the fixpoint bigraph by one walk ({@link RewritingReadout#ofRewritten}). The agent
 * <i>is</i> the state: there is no state map. A rewrite invalidates the other matches of the same call, so a rule is
 * matched again after each application until it has no match. This needs a jLibBig whose {@code AgentMatcher} keeps
 * links between parameters and deeper context nodes (the bundled 0.0.4 splits them; see {@link #REWRITE_KEEPS_LINKS}),
 * otherwise rewriting is refused. Guards and rules stop at their first match ({@code matchFirst}) where the framework
 * supports it ({@link #FIRST_MATCH_WORKS}), and enumerate all matches otherwise.
 */
public final class RewritingCoverageAnalyzer implements CoverageAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(RewritingCoverageAnalyzer.class);

    private static final List<CompiledRule> RULES = CoverageRewriteRules.compile();
    private static final DynamicSignature SIGNATURE = RULES.getFirst().rule().getRedex().getSignature();
    /** The controls of the domain nodes (one per node kind). */
    private static final Set<String> KINDS = new HashSet<>();
    static {
        for (POPPBigraphSignature c : POPPBigraphSignature.values()) {
            if (POPPBigraphSignature.isKindControl(c)) KINDS.add(c.controlName());
        }
    }

    /** A rule with what the static analysis knows about it. */
    private record Scheduled(int index, CompiledRule rule, Guard guard, int[] enables) {
        boolean enablesItself() {
            for (int i : enables) if (i == index) return true;
            return false;
        }
    }

    /** A guard pattern shared by all rules whose receiver looks the same. */
    private record Guard(String key, ParametricReactionRule<PureBigraph> rule) {}

    /** The rules queued at the start of a session from scratch (see the class comment). */
    static final Predicate<CompiledRule> LINK_RULES = CompiledRule::isLinkRule;

    private final CoverageInput input;
    private final List<Scheduled> rules = ANALYSIS.rules;

    public RewritingCoverageAnalyzer(CoverageInput input) {
        this.input = input;
    }

    /** The coverage rules, in the order the analyzer uses. */
    public static List<CompiledRule> rules() { return RULES; }

    private static final Map<CompiledRule, Optional<NodeKind>> RECEIVER_KINDS = new IdentityHashMap<>();
    static {
        for (CompiledRule r : RULES) {
            Optional<NodeKind> kind = r.rule().getRedex().getNodes().stream()
                    .filter(n -> n.getName().equals(r.receiverNode())).findFirst()
                    .map(POPPBigraphSignature::forNode)
                    .flatMap(c -> POPPBigraphSignature.isKindControl(c) ? Optional.of(POPPBigraphSignature.toNodeKind(c))
                            : POPPBigraphSignature.gateKindOf(c));
            RECEIVER_KINDS.put(r, kind);
        }
    }

    /** The kind of a link rule's giver (always a domain node); link rules only. */
    private static final Map<CompiledRule, NodeKind> GIVER_KINDS = new IdentityHashMap<>();
    static {
        for (CompiledRule r : RULES) {
            if (r.giverNode() == null) continue;
            r.rule().getRedex().getNodes().stream()
                    .filter(n -> n.getName().equals(r.giverNode())).findFirst()
                    .map(POPPBigraphSignature::forNode)
                    .filter(POPPBigraphSignature::isKindControl)
                    .ifPresent(c -> GIVER_KINDS.put(r, POPPBigraphSignature.toNodeKind(c)));
        }
    }

    /** The kind of a rule's receiver: of the domain node, or of the tree a gate node belongs to. */
    public static Optional<NodeKind> receiverKind(CompiledRule rule) {
        return RECEIVER_KINDS.getOrDefault(rule, Optional.empty());
    }

    /**
     * Runs a few sessions on a small built-in model, so that class loading and JIT compilation of the matcher (the
     * framework's matcher, with jLibBig and its constraint solver underneath) do not land on the user's first
     * inspection. Meant to be called once per JVM, off the UI path ({@code POPPExtension} starts it in a background
     * thread when the first model is loaded).
     */
    public static void warmUp() {
        if (!REWRITE_KEEPS_LINKS) return; // nothing to warm up: rewriting is refused (logged by the link check)
        DynamicSignature sig = POPPBigraphSignature.create();
        POPPBigraph bigraph = new POPPBigraph(pureBuilder(sig).createMutable(), sig);
        POPPModel model = new POPPModel(); // only creates the nodes; the bigraph is filled by hand below
        TreeNode<?> root = model.createNode(NodeKind.SUCCESS_CRITERIA, "warm-up root", 0, 0);
        TreeNode<?> left = model.createNode(NodeKind.SUCCESS_CRITERIA, "warm-up left", 0, 0);
        TreeNode<?> right = model.createNode(NodeKind.SUCCESS_CRITERIA, "warm-up right", 0, 0);
        model.decompose(root, left);
        model.decompose(root, right);
        root.setDecompositionType(DecompositionType.OR);
        TreeNode<?> solution = model.createNode(NodeKind.SOLUTION, "warm-up solution", 0, 0);
        TreeNode<?> proof = model.createNode(NodeKind.SUCCESS_PROOF, "warm-up proof", 0, 0);
        for (TreeNode<?> n : List.<TreeNode<?>>of(left, right, root, solution, proof)) bigraph.addTreeNode(n);
        bigraph.createRelation(new Relation(solution, RelationType.PRODUCES, proof));
        bigraph.createRelation(new Relation(proof, RelationType.VALIDATES, left));
        RewritingCoverageAnalyzer analyzer = new RewritingCoverageAnalyzer(CoverageInput.of(model, bigraph));
        for (int i = 0; i < 20; i++) {
            for (CoverageMode mode : CoverageMode.presets()) analyzer.session(mode).coverage(root);
        }
    }

    @Override
    public CoverageSession session(CoverageMode mode) {
        return rewriteToFixpoint(mode, CoverageBigraph.build(input.bigraph(), mode), LINK_RULES).session();
    }

    /** A coverage bigraph rewritten to its fixpoint, and the session read off it. */
    public record Fixpoint(PureBigraph bigraph, CoverageSession session) {}

    /**
     * Rewrites {@code agent}, a coverage bigraph, until no queued rule matches, starting with the rules {@code seed}
     * accepts, and reads states and reasons off the fixpoint bigraph. {@link #session} calls it on a
     * freshly built coverage bigraph; {@link IncrementalRewritingCoverageAnalyzer} on its synced bigraph, edited in place since
     * the last fixpoint. Sound as long as no state in {@code agent} is above the least fixpoint and every rule that
     * may match is seeded or enabled by a seeded one. Needs a jLibBig whose AgentMatcher keeps links (see
     * {@link #REWRITE_KEEPS_LINKS}).
     */
    public Fixpoint rewriteToFixpoint(CoverageMode mode, PureBigraph agent, Predicate<CompiledRule> seed) {
        if (!REWRITE_KEEPS_LINKS) {
            throw new IllegalStateException("The jLibBig AgentMatcher on the classpath splits links between parameters and"
                    + " deeper context nodes; rewriting would corrupt the relation edges (see REWRITE_KEEPS_LINKS)");
        }
        PureBigraphMatcher matcher = new PureBigraphMatcher();
        PureReactiveSystem reactions = new PureReactiveSystem();
        int budget = 2 * agent.getNodes().size() + 2; // every rewrite upgrades a state cell, at most twice per tree node

        boolean[] usable = usable(mode);
        LinkedHashSet<Scheduled> queue = new LinkedHashSet<>();
        for (Scheduled s : rules) if (usable[s.index()] && seed.test(s.rule())) queue.add(s);

        while (!queue.isEmpty()) {
            List<Scheduled> wave = new ArrayList<>(queue);
            queue.clear();
            // Guards are cached per wave although rewrites change the agent within it: a guard that held may no longer
            // hold (the rule call then just fails), and one that did not hold can only start to hold through a rewrite
            // by a rule that enables this one, which queues it for the next wave.
            Map<String, Boolean> guardHolds = new HashMap<>();
            for (Scheduled s : wave) {
                Guard g = s.guard();
                Boolean holds = guardHolds.get(g.key());
                if (holds == null) {
                    holds = exists(matcher, agent, g.rule());
                    guardHolds.put(g.key(), holds);
                }
                if (!holds) continue;
                CompiledRule rule = s.rule();
                boolean applied = false;
                while (true) {
                    Iterator<PureBigraphMatch> it = (FIRST_MATCH_WORKS
                            ? matcher.matchFirst(agent, rule.rule()) : matcher.matchAll(agent, rule.rule())).iterator();
                    PureBigraphMatch match = it.hasNext() ? it.next() : null;
                    boolean more = it.hasNext();
                    if (match == null) break;
                    if (--budget < 0) throw new IllegalStateException("Coverage rewriting did not terminate");
                    agent = reactions.buildParametricReaction(agent, match, rule.rule());
                    applied = true;
                    // all matches enumerated and this was the only one: only the rule itself could have made a new one
                    if (!more && !FIRST_MATCH_WORKS && !s.enablesItself()) break;
                }
                if (applied) for (int i : s.enables()) if (usable[i]) queue.add(rules.get(i));
            }
        }
        RewritingReadout readout = RewritingReadout.ofRewritten(agent);
        return new Fixpoint(agent, new RewritingSession(mode, input.model().getRelations(), readout));
    }

    /**
     * Whether the jLibBig {@code AgentMatcher} on the classpath keeps a link between a parameter node and a context
     * node nested deeper than the redex's root (the split-edge bug of the bundled jLibBig 0.0.4): rewrites
     * {@code A[S_e] || B[B[S_e]]} with {@code A[-] -> A[-]} and checks that the edge still has both points.
     */
    public static final boolean REWRITE_KEEPS_LINKS = checkRewriteKeepsLinks();

    private static boolean checkRewriteKeepsLinks() {
        try {
            DynamicSignature sig = org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder()
                    .add("A", 0).add("B", 0).add("S", 1).create();
            PureBigraphBuilder<DynamicSignature> ab = pureBuilder(sig);
            var x = ab.createInner("x");
            ab.root().child("A").down().child("S").linkInner(x).up()
                    .child("B").down().child("B").down().child("S").linkInner(x);
            ab.closeInner(x);
            PureBigraph agent = ab.create();
            PureBigraphBuilder<DynamicSignature> rb = pureBuilder(sig);
            rb.root().child("A").down().site();
            PureBigraph redex = rb.create();
            ParametricReactionRule<PureBigraph> rule = new ParametricReactionRule<>(redex, redex);
            PureBigraphMatch m = new PureBigraphMatcher().match(agent, rule).iterator().next();
            PureBigraph result = new PureReactiveSystem().buildParametricReaction(agent, m, rule);
            boolean ok = result.getEdges().size() == 1;
            if (!ok) logger.error("jLibBig AgentMatcher splits links (unfixed 0.0.4); coverage analysis needs the fixed jLibBig");
            return ok;
        } catch (Exception e) {
            logger.warn("Link check failed; coverage analysis is disabled", e);
            return false;
        }
    }

    /**
     * Whether the pattern has a match: with a framework whose {@code matchFirst} works the search stops at the first
     * match, otherwise all matches are enumerated.
     */
    private static boolean exists(PureBigraphMatcher matcher, PureBigraph agent, ParametricReactionRule<PureBigraph> rule) {
        if (FIRST_MATCH_WORKS) return matcher.matchFirst(agent, rule).iterator().hasNext();
        return matcher.matchAll(agent, rule).iterator().hasNext();
    }

    /**
     * Whether the framework's {@code matchFirst} finds a match that exists. In bigraph-simulation 2.4.1 it never does
     * ({@code PureMatchIteratorImpl.FirstMatchOnly} does not run the engine); checked once on a guard and an agent
     * built from it.
     */
    public static final boolean FIRST_MATCH_WORKS = checkFirstMatch();

    private static boolean checkFirstMatch() {
        try {
            PureBigraph g = guardBigraph(new GuardShape(KINDS.iterator().next(), List.of()));
            PureBigraphBuilder<DynamicSignature> b = pureBuilder(SIGNATURE);
            b.root().child(KINDS.iterator().next());
            PureBigraph agent = b.create();
            boolean ok = new PureBigraphMatcher().matchFirst(agent, new ParametricReactionRule<>(g, g)).iterator().hasNext();
            if (!ok) logger.info("matchFirst finds no match (bigraph-simulation 2.4.1 bug); guards enumerate all matches");
            return ok;
        } catch (Exception e) {
            logger.warn("matchFirst check failed, guards enumerate all matches", e);
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Static analysis of the rule set (once per JVM)

    private static final Analysis ANALYSIS = new Analysis(RULES);

    /*
     * Every rule and guard bigraph is shared by all sessions, and the modes of a report are evaluated in parallel.
     * Reading a framework bigraph fills lazy caches (ports, link points) that are not thread-safe, so each one is read
     * once here, the way the matcher and the rewriting read it: class initialization runs on one thread and
     * happens-before every use of the class, so the parallel sessions only ever read filled caches.
     */
    static {
        List<PureBigraph> shared = new ArrayList<>();
        for (Scheduled s : ANALYSIS.rules) {
            shared.add(s.rule().rule().getRedex());
            shared.add(s.rule().rule().getReactum());
            shared.add(s.guard().rule().getRedex());
        }
        for (PureBigraph b : shared) new org.bigraphs.framework.converter.jlibbig.JLibBigBigraphEncoder().encode(b);
    }

    private static final class Analysis {
        final List<Scheduled> rules;

        Analysis(List<CompiledRule> compiled) {
            int n = compiled.size();
            Map<String, Guard> guardsByKey = new LinkedHashMap<>();
            boolean[][] enabling = CoverageRewriteRules.enablingByOverlap();
            List<Scheduled> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                List<Integer> en = new ArrayList<>();
                for (int j = 0; j < n; j++) if (enabling[i][j]) en.add(j);
                out.add(new Scheduled(i, compiled.get(i), guard(compiled.get(i), guardsByKey),
                        en.stream().mapToInt(Integer::intValue).toArray()));
            }
            this.rules = List.copyOf(out);
        }
    }

    /** Per mode, which rules can match at all (see the class comment), by rule index. */
    private static final Map<CoverageMode, boolean[]> USABLE = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean[] usable(CoverageMode mode) {
        return USABLE.computeIfAbsent(mode, m -> {
            Set<NodeKind> raisable = m.raisableKinds();
            boolean[] out = new boolean[RULES.size()];
            for (int i = 0; i < out.length; i++) {
                CompiledRule r = RULES.get(i);
                Optional<NodeKind> receiver = receiverKind(r), giver = Optional.ofNullable(GIVER_KINDS.get(r));
                out[i] = receiver.isPresent() && raisable.contains(receiver.get())
                        && (!r.isLinkRule() || giver.isPresent() && (raisable.contains(giver.get()) || m.isIntrinsic(giver.get())));
            }
            return out;
        });
    }

    private static String control(BigraphEntity<?> n) {
        return ((BigraphEntity.NodeEntity<?>) n).getControl().getNamedType().stringValue();
    }

    /** A guard's shape: a node and its markers, with a site. */
    private record GuardShape(String control, List<String> markers) {
        String key() {
            return control + "[" + String.join("|", markers) + "|-]";
        }
    }

    /** The unlinked leaf children of a redex node (its markers), sorted. */
    private static List<String> markers(PureBigraph redex, BigraphEntity.NodeEntity<?> n) {
        List<String> out = new ArrayList<>();
        for (BigraphEntity<?> c : redex.getChildrenOf(n)) {
            if (c instanceof BigraphEntity.NodeEntity<?> m && redex.getChildrenOf(m).isEmpty()
                    && m.getControl().getArity().getValue().intValue() == 0) {
                out.add(control(m));
            }
        }
        Collections.sort(out);
        return out;
    }

    /** The guard of a rule: its receiver with its markers and a site, shared by key; embeds into the rule's redex. */
    private static Guard guard(CompiledRule rule, Map<String, Guard> byKey) {
        PureBigraph redex = rule.rule().getRedex();
        BigraphEntity.NodeEntity<?> receiver = redex.getNodes().stream()
                .filter(n -> n.getName().equals(rule.receiverNode())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Rule " + rule.name() + " has no receiver node"));
        GuardShape shape = new GuardShape(control(receiver), markers(redex, receiver));
        return byKey.computeIfAbsent(shape.key(), k -> {
            try {
                PureBigraph g = guardBigraph(shape);
                return new Guard(k, new ParametricReactionRule<>(g, g));
            } catch (Exception e) {
                throw new IllegalStateException("Guard " + k + " for rule " + rule.name(), e);
            }
        });
    }

    private static PureBigraph guardBigraph(GuardShape shape) throws Exception {
        PureBigraphBuilder<DynamicSignature> b = pureBuilder(SIGNATURE);
        var node = b.root().child(shape.control()).down();
        for (String m : shape.markers()) node = node.child(m);
        node.site();
        return b.create();
    }
}
