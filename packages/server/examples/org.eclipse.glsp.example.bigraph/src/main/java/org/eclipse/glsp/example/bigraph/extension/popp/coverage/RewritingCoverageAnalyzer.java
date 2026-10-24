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
 * {@link Options#DEFAULT}: worklist, seed, guards (matchFirst).
 * <p>
 * Which rule to match when is decided from what the rules themselves say, not from tables over the model: every
 * decision not to call the matcher for a rule follows from the rule set (a static analysis), from a matcher call on a
 * smaller pattern (a guard). The levers can be combined freely;
 * with none of them every rule is matched in every round.
 * <p>
 * <b>Rule worklist</b> ({@code worklist}). A static analysis of the rules computes which rules a rule <i>enables</i>:
 * {@code r} enables {@code s} if {@code r}'s receiver as {@code r} leaves it (new state and reason marker) can be glued
 * onto a node of {@code s}'s redex that needs something {@code r} created ({@link CoverageRewriteRules#enablingByOverlap()},
 * a produce-use dependency as in critical pair analysis; a node without a site has exactly its children, and a node has
 * one state). The coarser analysis by parent&gt;child control pairs ({@code -Dpopp.enabling=controls}) is kept for the
 * comparison. How much the worklist can prune depends on the rule set: with threshold gate rules
 * ({@link CoverageRewriteRules#THRESHOLD_GATES}) a slot that changes state enables only the thresholds of its new
 * state, not every combination of sibling states. Evaluation runs in waves: a wave takes the queued rules in turn and
 * rewrites with each until it has no match; a rule that rewrote something queues every rule it enables for the next
 * wave, each at most once; a rule without a match is dropped, since only an enabling rule can give it one. Without
 * {@code worklist}
 * every wave tries every rule (rounds).
 * <p>
 * <b>Mode</b> ({@code -Dpopp.prune=mode}, the default; {@code none} keeps every rule). A rule whose receiver kind no
 * coverage can reach in the mode ({@link CoverageMode#raisableKinds()}), or a link rule whose giver kind is neither
 * intrinsic nor reachable, can never match in that mode and is never queued.
 * <p>
 * <b>Guard patterns</b> ({@code guards}). Each rule has a guard: its receiver node with its marker children and a
 * site, e.g. {@code Goal[Unc | -]} ("some goal is still uncovered") or {@code GoalAnd[Part | -]}. The guard embeds
 * into the rule's redex, and embeddings compose, so if the guard has no match in the agent, the rule has none either.
 * Rules share guards (36 guards for 147 rules), and a guard is matched at most once per wave, so one matcher call can
 * rule out a whole group.
 * <p>
 * <b>Seed</b> ({@code seed}). The rules queued at the start; the developer's knowledge of the start state. For
 * coverage only link rules can match first: the trees of intrinsic kinds start covered (gate nodes included, see
 * {@link CoverageBigraph}) and all others uncovered, so no gate or lift rule has an input until a link rule raised
 * something.
 * <p>
 * <b>Applying the rules.</b> Every match is turned into the next agent by the framework's
 * {@code PureReactiveSystem.buildParametricReaction} (context, instantiated reactum and parameters composed), and the
 * states and reasons are read off the fixpoint bigraph by one walk ({@link RewritingReadout#ofRewritten}). The agent
 * <i>is</i> the state: there is no state map. A rewrite invalidates the other matches of the same call, so a rule is
 * matched again after each application until it has no match; when a call found exactly one match and the rule cannot
 * enable itself, the re-match is skipped. This needs a jLibBig whose {@code AgentMatcher} keeps links between
 * parameters and deeper context nodes (the bundled 0.0.4 splits them; see {@link #REWRITE_KEEPS_LINKS}), otherwise
 * rewriting is refused.
 */
public final class RewritingCoverageAnalyzer implements CoverageAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(RewritingCoverageAnalyzer.class);

    private static final List<CompiledRule> RULES = CoverageRewriteRules.compile();
    private static final DynamicSignature SIGNATURE = RULES.getFirst().rule().getRedex().getSignature();
    /** The node kinds and the gate controls. */
    private static final Set<String> KINDS = new HashSet<>(), GATES = new HashSet<>();
    static {
        for (POPPBigraphSignature c : POPPBigraphSignature.values()) {
            if (POPPBigraphSignature.isKindControl(c)) KINDS.add(c.controlName());
            if (POPPBigraphSignature.isDecompControl(c)) GATES.add(c.controlName());
        }
    }

    /**
     * @param worklist     queue only enabled rules (otherwise every wave matches every rule)
     * @param guards      match a rule's guard before the rule
     * @param seed        the rules queued at the start
     * @param nested      with {@code guards}: also a second-level guard that adds one child of the receiver
     * @param firstMatch  stop a guard and a rule at its first match ({@code matchFirst}; needs the fixed framework, see
     *                    {@link #FIRST_MATCH_WORKS}, otherwise all matches are enumerated)
     */
    public record Options(boolean worklist, boolean guards, Predicate<CompiledRule> seed, boolean nested, boolean firstMatch) {
        public Options(boolean worklist, boolean guards, Predicate<CompiledRule> seed) {
            this(worklist, guards, seed, false, false);
        }

        public static final Predicate<CompiledRule> ALL_RULES = r -> true;
        /** For coverage: only link rules can match on a freshly built coverage bigraph (see the class comment). */
        public static final Predicate<CompiledRule> LINK_RULES = CompiledRule::isLinkRule;

        /** None of the optimisations: every rule every round until a round changes nothing (the baseline). */
        public static final Options ROUNDS = new Options(false, false, ALL_RULES);
        /** Only the worklist: every rule queued at the start. */
        public static final Options WORKLIST = new Options(true, false, ALL_RULES);
        public static final Options WORKLIST_SEEDED = new Options(true, false, LINK_RULES);
        /** Only guards (with matchFirst): every rule every round, guarded. */
        public static final Options GUARDS_FIRST = new Options(false, true, ALL_RULES, false, true);
        /** Everything: worklist, seed and guards (with matchFirst). */
        public static final Options WORKLIST_GUARDS_SEEDED_FIRST = new Options(true, true, LINK_RULES, false, true);

        /** The analyzer's default: worklist, seed and guards. */
        public static final Options DEFAULT = WORKLIST_GUARDS_SEEDED_FIRST;
    }

    /** A rule with what the static analysis knows about it. */
    private record Scheduled(int index, CompiledRule rule, Guard guard, Guard nested, int[] enables) {
        boolean enablesItself() {
            for (int i : enables) if (i == index) return true;
            return false;
        }
    }

    /** A guard pattern shared by all rules whose receiver looks the same. */
    private record Guard(String key, ParametricReactionRule<PureBigraph> rule) {}

    private final CoverageInput input;
    private final Options options;
    private final List<Scheduled> rules;
    private final int guardCount;
    private volatile RewritingStats lastStats;
    private volatile int lastGuardCalls;

    /** With {@link Options#DEFAULT}. */
    public RewritingCoverageAnalyzer(CoverageInput input) {
        this(input, Options.DEFAULT);
    }

    public RewritingCoverageAnalyzer(CoverageInput input, Options options) {
        this.input = input;
        this.options = options;
        Analysis a = ANALYSIS;
        this.rules = a.rules;
        this.guardCount = a.guards;
    }

    public Options options() { return options; }

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

    /** Counters of the most recent session; {@code matcherCalls} counts rule and guard calls. */
    public RewritingStats lastStats() { return lastStats; }

    /** Guard matcher calls of the most recent session (included in {@link RewritingStats#matcherCalls()}). */
    public int lastGuardCalls() { return lastGuardCalls; }

    private volatile boolean tracing;
    private volatile RewritingTrace lastTrace;

    /** Record a {@link RewritingTrace} of every following session (slower; not for benchmarks). */
    public void setTracing(boolean on) { tracing = on; }

    /** The trace of the most recent session, if tracing was on. */
    public RewritingTrace lastTrace() { return lastTrace; }

    /**
     * Runs a few sessions on a small built-in model, so that class loading and JIT compilation of the matcher (the
     * framework's matcher, with jLibBig and its constraint solver underneath) do not land on the user's first
     * inspection. Meant to be called once per JVM, off the UI path ({@code POPPExtension} starts it in a background
     * thread when the first model is loaded). Not called by the analyzer itself, so the cold-start benchmark still
     * measures the unprepared case.
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

    /** The static enabling relation (rule name to the names of the rules it enables), for analysis tools. */
    public static Map<String, List<String>> enablingGraph() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Scheduled s : ANALYSIS.rules) {
            List<String> to = new ArrayList<>(s.enables().length);
            for (int i : s.enables()) to.add(ANALYSIS.rules.get(i).rule().name());
            out.put(s.rule().name(), to);
        }
        return out;
    }

    /** Average number of rules a rule enables, and the number of distinct guards (for the thesis). */
    public static String analysisSummary() {
        double avg = ANALYSIS.rules.stream().mapToInt(s -> s.enables().length).average().orElse(0);
        int max = ANALYSIS.rules.stream().mapToInt(s -> s.enables().length).max().orElse(0);
        return String.format("%d rules, %d guards, %d second-level guards, a rule enables %.1f rules on average (max %d)",
                ANALYSIS.rules.size(), ANALYSIS.guards, ANALYSIS.nestedGuards, avg, max);
    }

    @Override
    public CoverageSession session(CoverageMode mode) {
        long t0 = System.nanoTime();
        PureBigraph agent = CoverageBigraph.build(input.bigraph(), mode);
        return rewriteToFixpoint(mode, agent, options.seed(), System.nanoTime() - t0).session();
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
        return rewriteToFixpoint(mode, agent, seed, 0);
    }

    private Fixpoint rewriteToFixpoint(CoverageMode mode, PureBigraph agent, Predicate<CompiledRule> seed, long tBuild) {
        if (!REWRITE_KEEPS_LINKS && !Boolean.getBoolean("popp.rewrite.ignoreLinkCheck")) {
            throw new IllegalStateException("The jLibBig AgentMatcher on the classpath splits links between parameters and"
                    + " deeper context nodes; rewriting would corrupt the relation edges (see REWRITE_KEEPS_LINKS)");
        }
        long t0, tMatch = 0, tRewrite = 0;
        int agentNodes = agent.getNodes().size();
        RewritingTrace trace = tracing ? new RewritingTrace() : null;

        PureBigraphMatcher matcher = new PureBigraphMatcher();
        PureReactiveSystem reactions = new PureReactiveSystem();
        int waves = 0, ruleCalls = 0, guardCalls = 0, successful = 0;
        int budget = 2 * agentNodes + 2;            // every rewrite upgrades a state cell, at most twice per tree node

        boolean[] usable = usable(mode);
        LinkedHashSet<Scheduled> queue = new LinkedHashSet<>();
        for (Scheduled s : rules) if (usable[s.index()] && seed.test(s.rule())) queue.add(s);

        while (!queue.isEmpty()) {
            waves++;
            List<Scheduled> wave = new ArrayList<>(queue);
            queue.clear();
            long roundStart = trace == null ? 0 : trace.now(), rewriteInRound = 0;
            int guarded = 0, roundAgent = agent.getNodes().size();
            // Guards are cached per wave although rewrites change the agent within it: a guard that held may no longer
            // hold (the rule call then just fails), and one that did not hold can only start to hold through a rewrite
            // by a rule that enables this one, which queues it for the next wave.
            Map<String, Boolean> guardHolds = new HashMap<>();
            boolean changed = false;

            for (Scheduled s : wave) {
                if (options.guards()) {
                    List<Guard> levels = options.nested() && s.nested() != null ? List.of(s.guard(), s.nested()) : List.of(s.guard());
                    boolean holds = true;
                    for (Guard g : levels) {
                        Boolean h = guardHolds.get(g.key());
                        if (h == null) {
                            guardCalls++;
                            t0 = System.nanoTime();
                            long callStart = trace == null ? 0 : trace.now();
                            h = exists(matcher, agent, g.rule(), options.firstMatch());
                            tMatch += System.nanoTime() - t0;
                            if (trace != null) trace.call(waves, RewritingTrace.Kind.GUARD, g.key(), h, callStart, trace.now());
                            guardHolds.put(g.key(), h);
                        }
                        if (!h) { holds = false; break; }
                    }
                    if (!holds) { guarded++; continue; }
                }
                CompiledRule rule = s.rule();
                boolean applied = false;
                while (true) {
                    ruleCalls++;
                    t0 = System.nanoTime();
                    long callStart = trace == null ? 0 : trace.now();
                    Iterator<PureBigraphMatch> it = (options.firstMatch() && FIRST_MATCH_WORKS
                            ? matcher.matchFirst(agent, rule.rule()) : matcher.matchAll(agent, rule.rule())).iterator();
                    PureBigraphMatch match = it.hasNext() ? it.next() : null;
                    boolean more = it.hasNext();
                    tMatch += System.nanoTime() - t0;
                    if (trace != null) trace.call(waves, RewritingTrace.Kind.RULE, rule.name(), match != null, callStart, trace.now());
                    if (match == null) break;
                    successful++;
                    if (--budget < 0) throw new IllegalStateException("Coverage rewriting did not terminate");
                    t0 = System.nanoTime();
                    agent = reactions.buildParametricReaction(agent, match, rule.rule());
                    long rw = System.nanoTime() - t0;
                    tRewrite += rw;
                    rewriteInRound += rw;
                    applied = true;
                    // all matches enumerated and this was the only one: only the rule itself could have made a new one
                    if (!more && !(options.firstMatch() && FIRST_MATCH_WORKS) && !s.enablesItself()) break;
                }
                if (applied) {
                    changed = true;
                    if (options.worklist()) for (int i : s.enables()) if (usable[i]) queue.add(rules.get(i));
                }
            }
            if (trace != null) trace.round(waves, wave.size(), guarded, roundStart, trace.now(), rewriteInRound,
                    roundAgent, RewritingReadout.ofRewritten(agent).coverage());
            if (!changed) break;
            if (!options.worklist()) for (Scheduled s : rules) if (usable[s.index()]) queue.add(s);
        }
        t0 = System.nanoTime();
        RewritingReadout readout = RewritingReadout.ofRewritten(agent);
        long tReadout = System.nanoTime() - t0;
        lastStats = new RewritingStats(waves, ruleCalls + guardCalls, successful, agentNodes, tBuild, tMatch, tRewrite, tReadout);
        lastGuardCalls = guardCalls;
        lastFixpoint = agent;
        lastTrace = trace;
        logger.debug("[rewriting session {} {}] {} ({} guard calls)", mode, options, lastStats, guardCalls);
        return new Fixpoint(agent, new RewritingSession(mode, input.model().getRelations(), readout));
    }

    private volatile PureBigraph lastFixpoint;

    /** The fixpoint bigraph of the most recent session (for inspection and tests). */
    public PureBigraph lastFixpoint() { return lastFixpoint; }

    /**
     * Whether the jLibBig {@code AgentMatcher} on the classpath keeps a link between a parameter node and a context
     * node nested deeper than the redex's root (the split-edge bug of the bundled jLibBig 0.0.4): rewrites
     * {@code A[S_e] || B[B[S_e]]} with {@code A[-] -> A[-]} and checks that the edge still has both points.
     * {@code -Dpopp.rewrite.ignoreLinkCheck=true} rewrites anyway (to show what the bug does to the analysis).
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
     * Whether the pattern has a match: with {@code firstMatch} (and a framework whose {@code matchFirst} works) the
     * search stops at the first match, otherwise all matches are enumerated.
     */
    private static boolean exists(PureBigraphMatcher matcher, PureBigraph agent, ParametricReactionRule<PureBigraph> rule,
                                  boolean firstMatch) {
        if (firstMatch && FIRST_MATCH_WORKS) return matcher.matchFirst(agent, rule).iterator().hasNext();
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
            PureBigraph g = guardBigraph(new GuardShape(KINDS.iterator().next(), List.of(), null));
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

    /** Enabling by overlapping the rules ({@link CoverageRewriteRules#enablingByOverlap()}) or by control pairs. */
    static final boolean ENABLING_BY_OVERLAP = !"controls".equals(System.getProperty("popp.enabling", "overlap"));
    /** Never queue a rule that cannot match in the session's mode (see the class comment). */
    static final boolean PRUNE_BY_MODE = !"none".equals(System.getProperty("popp.prune", "mode"));
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
            if (s.nested() != null) shared.add(s.nested().rule().getRedex());
        }
        for (PureBigraph b : shared) new org.bigraphs.framework.converter.jlibbig.JLibBigBigraphEncoder().encode(b);
    }

    private static final class Analysis {
        final List<Scheduled> rules;
        final int guards, nestedGuards;

        Analysis(List<CompiledRule> compiled) {
            int n = compiled.size();
            List<Map<String, Integer>> needs = new ArrayList<>(n);
            List<Set<String>> exact = new ArrayList<>(n);
            List<Set<String>> added = new ArrayList<>(n), removed = new ArrayList<>(n);
            for (CompiledRule r : compiled) {
                PureBigraph redex = r.rule().getRedex(), reactum = r.rule().getReactum();
                Map<String, Integer> before = facts(redex), after = facts(reactum);
                needs.add(before);
                exact.add(exactNodes(redex));
                Set<String> plus = new HashSet<>(), minus = new HashSet<>();
                Set<String> keys = new HashSet<>(before.keySet());
                keys.addAll(after.keySet());
                for (String k : keys) {
                    int d = after.getOrDefault(k, 0) - before.getOrDefault(k, 0);
                    if (d > 0) plus.add(k);
                    else if (d < 0) minus.add(k);
                }
                added.add(plus);
                removed.add(minus);
            }
            Map<String, Guard> guardsByKey = new LinkedHashMap<>(), nestedByKey = new LinkedHashMap<>();
            List<Scheduled> out = new ArrayList<>(n);
            boolean[][] overlap = ENABLING_BY_OVERLAP ? CoverageRewriteRules.enablingByOverlap() : null;
            for (int i = 0; i < n; i++) {
                List<Integer> en = new ArrayList<>();
                for (int j = 0; j < n; j++) {
                    final int jj = j;
                    boolean enables = overlap != null ? overlap[i][j]
                            : added.get(i).stream().anyMatch(f -> needs.get(jj).containsKey(f))
                            || removed.get(i).stream().anyMatch(f -> exact.get(jj).contains(parentOf(f)));
                    if (enables) en.add(j);
                }
                Guard g = guard(compiled.get(i), false, guardsByKey);
                Guard nested = guard(compiled.get(i), true, nestedByKey);
                if (nested != null && nested.key().equals(g.key())) nested = null; // nothing to add (link rules)
                out.add(new Scheduled(i, compiled.get(i), g, nested, en.stream().mapToInt(Integer::intValue).toArray()));
            }
            this.rules = List.copyOf(out);
            this.guards = guardsByKey.size();
            this.nestedGuards = (int) nestedByKey.keySet().stream().filter(k -> !guardsByKey.containsKey(k)).count();
        }
    }

    /** Per mode, which rules can match at all ({@link #PRUNE_BY_MODE}), by rule index. */
    private static final Map<CoverageMode, boolean[]> USABLE = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean[] usable(CoverageMode mode) {
        return USABLE.computeIfAbsent(mode, m -> {
            Set<NodeKind> raisable = m.raisableKinds();
            boolean[] out = new boolean[RULES.size()];
            for (int i = 0; i < out.length; i++) {
                CompiledRule r = RULES.get(i);
                Optional<NodeKind> receiver = receiverKind(r), giver = Optional.ofNullable(GIVER_KINDS.get(r));
                out[i] = !PRUNE_BY_MODE || receiver.isPresent() && raisable.contains(receiver.get())
                        && (!r.isLinkRule() || giver.isPresent() && (raisable.contains(giver.get()) || m.isIntrinsic(giver.get())));
            }
            return out;
        });
    }

    /** The number of rules that can match in {@code mode} (all of them with {@code -Dpopp.prune=none}). */
    public static int usableRules(CoverageMode mode) {
        int c = 0;
        for (boolean u : usable(mode)) if (u) c++;
        return c;
    }

    private static String control(BigraphEntity<?> n) {
        return ((BigraphEntity.NodeEntity<?>) n).getControl().getNamedType().stringValue();
    }

    /**
     * The structure a bigraph has, as a multiset of facts: {@code parent>child} for every node below a node, the
     * control for a node below a root, and {@code link:a,b,...} for the (sorted) owners of a link's points.
     */
    private static Map<String, Integer> facts(PureBigraph b) {
        Map<String, Integer> out = new HashMap<>();
        for (BigraphEntity.NodeEntity<?> n : b.getNodes()) {
            BigraphEntity<?> p = b.getParent(n);
            String key = p instanceof BigraphEntity.NodeEntity<?> ? control(p) + ">" + control(n) : control(n);
            out.merge(key, 1, Integer::sum);
        }
        List<BigraphEntity.Link> links = new ArrayList<>(b.getEdges());
        links.addAll(b.getOuterNames());
        for (BigraphEntity.Link l : links) {
            List<String> pts = new ArrayList<>();
            for (BigraphEntity<?> pt : b.getPointsFromLink(l)) {
                if (pt instanceof BigraphEntity.Port port) {
                    BigraphEntity.NodeEntity<?> stub = b.getNodeOfPort(port);
                    BigraphEntity<?> owner = b.getParent(stub);
                    pts.add((owner instanceof BigraphEntity.NodeEntity<?> o ? control(o) : "") + ">" + control(stub));
                }
            }
            Collections.sort(pts);
            out.merge("link:" + String.join(",", pts), 1, Integer::sum);
        }
        return out;
    }

    /** Controls of the redex nodes without a site that have children (they match their children exactly). */
    private static Set<String> exactNodes(PureBigraph b) {
        Set<String> out = new HashSet<>();
        for (BigraphEntity.NodeEntity<?> n : b.getNodes()) {
            var kids = b.getChildrenOf(n);
            if (!kids.isEmpty() && kids.stream().noneMatch(k -> k instanceof BigraphEntity.SiteEntity)) out.add(control(n));
        }
        return out;
    }

    private static String parentOf(String fact) {
        int i = fact.indexOf('>');
        return fact.startsWith("link:") || i < 0 ? fact : fact.substring(0, i);
    }

    /** A guard's shape: a node, its markers, and optionally one tree child with its markers (each with a site). */
    private record GuardShape(String control, List<String> markers, GuardShape child) {
        String key() {
            return control + "[" + String.join("|", markers) + (child == null ? "" : "|" + child.key()) + "|-]";
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

    /**
     * The first-level guard: the rule's receiver with its markers and a site. The second-level ({@code nested}) guard
     * adds one tree child of the receiver with its markers: of the children, the one whose shape sorts last, which for
     * gate rules is the slot in the highest state ({@code Cov} &gt; {@code Part} &gt; {@code Unc}), so rules are
     * grouped by the best slot they need. Shared by key; both embed into the rule's redex.
     */
    private static Guard guard(CompiledRule rule, boolean nested, Map<String, Guard> byKey) {
        PureBigraph redex = rule.rule().getRedex();
        BigraphEntity.NodeEntity<?> receiver = redex.getNodes().stream()
                .filter(n -> n.getName().equals(rule.receiverNode())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Rule " + rule.name() + " has no receiver node"));
        GuardShape child = null;
        if (nested) {
            for (BigraphEntity<?> c : redex.getChildrenOf(receiver)) {
                if (!(c instanceof BigraphEntity.NodeEntity<?> t) || !(KINDS.contains(control(t)) || GATES.contains(control(t)))) continue;
                GuardShape candidate = new GuardShape(control(t), markers(redex, t), null);
                if (child == null || stateRank(candidate) > stateRank(child)
                        || (stateRank(candidate) == stateRank(child) && candidate.key().compareTo(child.key()) > 0)) {
                    child = candidate;
                }
            }
        }
        GuardShape shape = new GuardShape(control(receiver), markers(redex, receiver), child);
        return byKey.computeIfAbsent(shape.key(), k -> {
            try {
                PureBigraph g = guardBigraph(shape);
                return new Guard(k, new ParametricReactionRule<>(g, g));
            } catch (Exception e) {
                throw new IllegalStateException("Guard " + k + " for rule " + rule.name(), e);
            }
        });
    }

    private static int stateRank(GuardShape s) {
        return s.markers().contains("Cov") ? 2 : s.markers().contains("Part") ? 1 : 0;
    }

    private static PureBigraph guardBigraph(GuardShape shape) throws Exception {
        PureBigraphBuilder<DynamicSignature> b = pureBuilder(SIGNATURE);
        var node = b.root().child(shape.control()).down();
        for (String m : shape.markers()) node = node.child(m);
        if (shape.child() != null) {
            node = node.child(shape.child().control()).down();
            for (String m : shape.child().markers()) node = node.child(m);
            node = node.site().up();
        }
        node.site();
        return b.create();
    }
}
