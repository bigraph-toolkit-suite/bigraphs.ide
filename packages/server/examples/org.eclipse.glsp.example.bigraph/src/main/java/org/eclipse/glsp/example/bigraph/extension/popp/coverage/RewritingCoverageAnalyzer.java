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
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageRewriteRules.CompiledRule;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.Guard;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.GuardEvaluator;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.Matcher;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.MonotoneFixpointRewriter;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.OverlapProduceUseAnalyzer;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.Rule;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.WorklistOrder;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;

/**
 * Coverage analysis by forward chaining with the coverage rules ({@link CoverageRewriteRules}) over the whole coverage
 * bigraph ({@link CoverageBigraph}), matched with the Bigraph Framework's {@link PureBigraphMatcher} (jLibBig
 * underneath) and applied by rewriting. The rules are run to their fixpoint by the generic
 * {@link MonotoneFixpointRewriter}. This class only supplies what is specific to coverage: the rules, their guards,
 * which rules can match in a mode, and the seed. Evaluates every session from scratch. The POPP extension uses it
 * through {@link IncrementalRewritingCoverageAnalyzer}, which keeps the fixpoint bigraph and only re-derives what an
 * edit affected.
 * <p>
 * Which rule to match when is decided from what the rules themselves say, not from tables over the model: every
 * decision not to call the matcher for a rule follows from the rule set (a static analysis), from the mode, or from a
 * matcher call on a smaller pattern (a guard).
 * <p>
 * <b>Rule worklist.</b> The rewriter takes a rule from its worklist and rewrites with it until it has no match. A rule
 * that rewrote something queues every rule it <i>enables</i>, as the generic overlap analysis
 * ({@link OverlapProduceUseAnalyzer}) finds them: {@code r} enables {@code s} if what {@code r} creates or changes can
 * be glued onto the redex of {@code s} (a produce-use dependency as in critical pair analysis). The worklist is ordered
 * by the strongly connected components of that relation in topological order ({@link WorklistOrder#topological()}), so
 * a rule is tried only once no rule that could still enable it is queued.
 * <p>
 * <b>Mode.</b> A rule whose receiver kind no coverage can reach in the mode ({@link CoverageMode#raisableKinds()}), or
 * a link rule whose giver kind is neither intrinsic nor reachable, can never match in that mode and is never queued.
 * <p>
 * <b>Guards.</b> Each rule has a guard: its receiver node with its marker children and a site, e.g.
 * {@code Goal[Unc | -]} ("some goal is still uncovered") or {@code GoalAnd[Part | -]}. The guard embeds into the rule's
 * redex, and embeddings compose, so if the guard has no match in the agent, the rule has none either. Rules share
 * guards (36 guards for 147 rules), so one matcher call can rule out a whole group. Guards are counted
 * ({@link GuardEvaluator#counting()}): each one is counted once by the matcher, and from then on its count follows
 * from the rewrites, by an analysis of what each rule does to each guard.
 * <p>
 * <b>Seed.</b> The rules queued at the start. On a freshly built coverage bigraph only link rules can match first: the
 * trees of intrinsic kinds start covered (gate nodes included, see {@link CoverageBigraph}) and all others uncovered,
 * so no gate or lift rule has an input until a link rule raised something.
 * <p>
 * <b>Applying the rules.</b> Every match is turned into the next agent by the framework's
 * {@code PureReactiveSystem.buildParametricReaction} (context, instantiated reactum and parameters composed), and the
 * states and reasons are read off the fixpoint bigraph by one walk ({@link RewritingReadout#ofRewritten}). The agent
 * <i>is</i> the state: there is no state map. This needs a jLibBig whose {@code AgentMatcher} keeps links between
 * parameters and deeper context nodes (the bundled 0.0.4 splits them, see {@link #REWRITE_KEEPS_LINKS}), otherwise
 * rewriting is refused. Guards and rules stop at their first match ({@code matchFirst}) where the framework supports it
 * ({@link #FIRST_MATCH_WORKS}), and enumerate all matches otherwise.
 */
public final class RewritingCoverageAnalyzer implements CoverageAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(RewritingCoverageAnalyzer.class);

    private static final List<CompiledRule> RULES = CoverageRewriteRules.compile();
    private static final DynamicSignature SIGNATURE = RULES.getFirst().rule().getRedex().getSignature();
    /** The controls of the domain nodes (one per node kind). */
    private static final Set<String> KINDS = new HashSet<>();
    static {
        for (POPPBigraphSignature control : POPPBigraphSignature.values()) {
            if (POPPBigraphSignature.isKindControl(control)) KINDS.add(control.controlName());
        }
    }

    /** The rules queued at the start of a session from scratch (see the class comment). */
    static final Predicate<CompiledRule> LINK_RULES = CompiledRule::isLinkRule;

    private final CoverageInput input;
    private final MonotoneFixpointRewriter rewriter;

    public RewritingCoverageAnalyzer(CoverageInput input) {
        this(input, REWRITER);
    }

    /**
     * An analyzer on another configuration of the rewriter (benchmarks compare them). Its rules must be
     * {@link #rewriterRules} (with or without guards), in that order.
     */
    RewritingCoverageAnalyzer(CoverageInput input, MonotoneFixpointRewriter rewriter) {
        if (rewriter.rules().size() != RULES.size()) {
            throw new IllegalArgumentException("The rewriter does not run the coverage rules");
        }
        for (int i = 0; i < RULES.size(); i++) {
            if (!rewriter.rules().get(i).name().equals(RULES.get(i).name())) {
                throw new IllegalArgumentException("The rewriter's rules are not in the order of rules(): "
                        + rewriter.rules().get(i).name());
            }
        }
        this.input = input;
        this.rewriter = rewriter;
    }

    /** The coverage rules, in the order the analyzer uses. */
    public static List<CompiledRule> rules() { return RULES; }

    /**
     * The coverage rules for the generic {@link MonotoneFixpointRewriter}, in the order of {@link #rules()}: without
     * guards, or with this analyzer's guards (one {@link Guard} object per guard shape).
     */
    static List<Rule> rewriterRules(boolean guarded) {
        List<Rule> out = new ArrayList<>();
        for (int i = 0; i < RULES.size(); i++) {
            CompiledRule rule = RULES.get(i);
            out.add(guarded ? Rule.guarded(rule.name(), rule.rule(), GUARDS.get(i)) : Rule.of(rule.name(), rule.rule()));
        }
        return out;
    }

    private static final Map<CompiledRule, Optional<NodeKind>> RECEIVER_KINDS = new IdentityHashMap<>();
    static {
        for (CompiledRule rule : RULES) {
            Optional<NodeKind> kind = rule.rule().getRedex().getNodes().stream()
                    .filter(node -> node.getName().equals(rule.receiverNode())).findFirst()
                    .map(POPPBigraphSignature::forNode)
                    .flatMap(control -> POPPBigraphSignature.isKindControl(control)
                            ? Optional.of(POPPBigraphSignature.toNodeKind(control))
                            : POPPBigraphSignature.gateKindOf(control));
            RECEIVER_KINDS.put(rule, kind);
        }
    }

    /** The kind of a link rule's giver (always a domain node), for link rules only. */
    private static final Map<CompiledRule, NodeKind> GIVER_KINDS = new IdentityHashMap<>();
    static {
        for (CompiledRule rule : RULES) {
            if (rule.giverNode() == null) continue;
            rule.rule().getRedex().getNodes().stream()
                    .filter(node -> node.getName().equals(rule.giverNode())).findFirst()
                    .map(POPPBigraphSignature::forNode)
                    .filter(POPPBigraphSignature::isKindControl)
                    .ifPresent(control -> GIVER_KINDS.put(rule, POPPBigraphSignature.toNodeKind(control)));
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
        POPPModel model = new POPPModel(); // only creates the nodes, the bigraph is filled by hand below
        TreeNode<?> root = model.createNode(NodeKind.SUCCESS_CRITERIA, "warm-up root", 0, 0);
        TreeNode<?> left = model.createNode(NodeKind.SUCCESS_CRITERIA, "warm-up left", 0, 0);
        TreeNode<?> right = model.createNode(NodeKind.SUCCESS_CRITERIA, "warm-up right", 0, 0);
        model.decompose(root, left);
        model.decompose(root, right);
        root.setDecompositionType(DecompositionType.OR);
        TreeNode<?> solution = model.createNode(NodeKind.SOLUTION, "warm-up solution", 0, 0);
        TreeNode<?> proof = model.createNode(NodeKind.SUCCESS_PROOF, "warm-up proof", 0, 0);
        for (TreeNode<?> node : List.<TreeNode<?>>of(left, right, root, solution, proof)) bigraph.addTreeNode(node);
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
     * accepts, and reads states and reasons off the fixpoint bigraph. {@link #session} calls it on a freshly built
     * coverage bigraph, {@link IncrementalRewritingCoverageAnalyzer} on its synced bigraph, edited in place since the
     * last fixpoint. Sound as long as no state in {@code agent} is above the least fixpoint and every rule that may
     * match is seeded or enabled by a seeded one. Needs a jLibBig whose AgentMatcher keeps links (see
     * {@link #REWRITE_KEEPS_LINKS}).
     */
    public Fixpoint rewriteToFixpoint(CoverageMode mode, PureBigraph agent, Predicate<CompiledRule> seed) {
        if (!REWRITE_KEEPS_LINKS) {
            throw new IllegalStateException("The jLibBig AgentMatcher on the classpath splits links between parameters and"
                    + " deeper context nodes, rewriting would corrupt the relation edges (see REWRITE_KEEPS_LINKS)");
        }
        boolean[] usable = usable(mode);
        List<Rule> initialWorklist = new ArrayList<>();
        for (int i = 0; i < RULES.size(); i++) {
            if (usable[i] && seed.test(RULES.get(i))) initialWorklist.add(rewriter.rules().get(i));
        }
        int maxRewrites = 2 * agent.getNodes().size() + 2; // every rewrite upgrades a state cell, at most twice per tree node
        MonotoneFixpointRewriter.Result result = rewriter.rewriteToFixpoint(agent, initialWorklist,
                rule -> usable[rewriter.indexOf(rule)], maxRewrites);
        RewritingReadout readout = RewritingReadout.ofRewritten(result.agent());
        return new Fixpoint(result.agent(), new RewritingSession(mode, input.model().getRelations(), readout));
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
            PureBigraphBuilder<DynamicSignature> agentBuilder = pureBuilder(sig);
            var link = agentBuilder.createInner("x");
            agentBuilder.root().child("A").down().child("S").linkInner(link).up()
                    .child("B").down().child("B").down().child("S").linkInner(link);
            agentBuilder.closeInner(link);
            PureBigraph agent = agentBuilder.create();
            PureBigraphBuilder<DynamicSignature> redexBuilder = pureBuilder(sig);
            redexBuilder.root().child("A").down().site();
            PureBigraph redex = redexBuilder.create();
            ParametricReactionRule<PureBigraph> rule = new ParametricReactionRule<>(redex, redex);
            PureBigraphMatch match = new PureBigraphMatcher().match(agent, rule).iterator().next();
            PureBigraph result = new PureReactiveSystem().buildParametricReaction(agent, match, rule);
            boolean ok = result.getEdges().size() == 1;
            if (!ok) logger.error("jLibBig AgentMatcher splits links (unfixed 0.0.4), coverage analysis needs the fixed jLibBig");
            return ok;
        } catch (Exception e) {
            logger.warn("Link check failed, coverage analysis is disabled", e);
            return false;
        }
    }

    /**
     * Whether the framework's {@code matchFirst} finds a match that exists. In bigraph-simulation 2.4.1 it never does
     * ({@code PureMatchIteratorImpl.FirstMatchOnly} does not run the engine). Checked once on a guard and an agent
     * built from it.
     */
    public static final boolean FIRST_MATCH_WORKS = checkFirstMatch();

    private static boolean checkFirstMatch() {
        try {
            PureBigraph pattern = guardBigraph(new GuardShape(KINDS.iterator().next(), List.of()));
            PureBigraphBuilder<DynamicSignature> agentBuilder = pureBuilder(SIGNATURE);
            agentBuilder.root().child(KINDS.iterator().next());
            PureBigraph agent = agentBuilder.create();
            boolean ok = new PureBigraphMatcher().matchFirst(agent, new ParametricReactionRule<>(pattern, pattern))
                    .iterator().hasNext();
            if (!ok) logger.info("matchFirst finds no match (bigraph-simulation 2.4.1 bug), guards enumerate all matches");
            return ok;
        } catch (Exception e) {
            logger.warn("matchFirst check failed, guards enumerate all matches", e);
            return false;
        }
    }

    // The rule set, analysed once per JVM

    /** Per rule, in the order of {@link #rules()}: its guard, one object per guard shape (see the class comment). */
    private static final List<Guard> GUARDS = guards(RULES);

    /**
     * The rewriter all sessions share. Building it analyses the rule set (dependencies, worklist order) and reads every
     * rule and guard bigraph once, so that the lazy caches of the framework are filled before sessions of different
     * modes run in parallel (class initialization happens-before every use of the class).
     */
    private static final MonotoneFixpointRewriter REWRITER = MonotoneFixpointRewriter.builder(rewriterRules(true))
            .withProduceUseAnalyzer(new OverlapProduceUseAnalyzer())
            .withOrder(WorklistOrder.topological())
            .withMatcher(FIRST_MATCH_WORKS ? Matcher.matchFirst() : Matcher.matchAll())
            .withGuardEvaluator(GuardEvaluator.counting())
            .build();

    /** Per mode, which rules can match at all (see the class comment), by rule index. */
    private static final Map<CoverageMode, boolean[]> USABLE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Per rule, in the order of {@link #rules()}: whether it can upgrade anything in {@code mode}. */
    static boolean[] usable(CoverageMode mode) {
        return USABLE.computeIfAbsent(mode, key -> {
            Set<NodeKind> raisable = key.raisableKinds();
            boolean[] out = new boolean[RULES.size()];
            for (int i = 0; i < out.length; i++) {
                CompiledRule rule = RULES.get(i);
                Optional<NodeKind> receiver = receiverKind(rule), giver = Optional.ofNullable(GIVER_KINDS.get(rule));
                out[i] = receiver.isPresent() && raisable.contains(receiver.get())
                        && (!rule.isLinkRule() || giver.isPresent()
                        && (raisable.contains(giver.get()) || key.isIntrinsic(giver.get())));
            }
            return out;
        });
    }

    private static String control(BigraphEntity<?> node) {
        return ((BigraphEntity.NodeEntity<?>) node).getControl().getNamedType().stringValue();
    }

    /** A guard's shape: a node and its markers, with a site. */
    private record GuardShape(String control, List<String> markers) {
        String key() {
            return control + "[" + String.join("|", markers) + "|-]";
        }
    }

    /** The unlinked leaf children of a redex node (its markers), sorted. */
    private static List<String> markers(PureBigraph redex, BigraphEntity.NodeEntity<?> node) {
        List<String> out = new ArrayList<>();
        for (BigraphEntity<?> child : redex.getChildrenOf(node)) {
            if (child instanceof BigraphEntity.NodeEntity<?> marker && redex.getChildrenOf(marker).isEmpty()
                    && marker.getControl().getArity().getValue().intValue() == 0) {
                out.add(control(marker));
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Per rule: its guard, the receiver with its markers and a site. Rules with the same shape share one guard. */
    private static List<Guard> guards(List<CompiledRule> rules) {
        Map<String, Guard> byKey = new HashMap<>();
        List<Guard> out = new ArrayList<>();
        for (CompiledRule rule : rules) {
            PureBigraph redex = rule.rule().getRedex();
            BigraphEntity.NodeEntity<?> receiver = redex.getNodes().stream()
                    .filter(node -> node.getName().equals(rule.receiverNode())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Rule " + rule.name() + " has no receiver node"));
            GuardShape shape = new GuardShape(control(receiver), markers(redex, receiver));
            out.add(byKey.computeIfAbsent(shape.key(), key -> {
                try {
                    return Guard.of(key, guardBigraph(shape));
                } catch (Exception e) {
                    throw new IllegalStateException("Guard " + key + " for rule " + rule.name(), e);
                }
            }));
        }
        return List.copyOf(out);
    }

    private static PureBigraph guardBigraph(GuardShape shape) throws Exception {
        PureBigraphBuilder<DynamicSignature> builder = pureBuilder(SIGNATURE);
        var node = builder.root().child(shape.control()).down();
        for (String marker : shape.markers()) node = node.child(marker);
        node.site();
        return builder.create();
    }
}
