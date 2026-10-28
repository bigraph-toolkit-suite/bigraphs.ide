package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import org.bigraphs.framework.converter.jlibbig.JLibBigBigraphEncoder;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatch;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Rewrites an agent with a set of reaction rules until no rule matches anymore, for rule sets whose result does not
 * depend on the order of the rewrites (e.g. monotone rules that only raise labels). It knows nothing about the rules
 * beyond what it is given. Every match, of a rule or of a guard, is found by the {@link Matcher}.
 * <p>
 * <b>Worklist.</b> A rule is taken from the worklist and applied until it has no match. If it rewrote something, every
 * rule it enables is queued (each at most once). A rule that is not queued has no match, so an empty worklist is a
 * fixpoint. There are no waves: the queue is ordered by the {@link WorklistOrder}, ties in the order of queueing.
 * <p>
 * <b>Guards.</b> A rule is only tried if all its {@link Guard guards} match. When they are matched, and how long an
 * answer is kept, is up to the {@link GuardEvaluator}.
 * <p>
 * <b>Building.</b> A rewriter is made by a {@link Builder} ({@link #builder}): the rule set and the strategies are set
 * there, and {@link Builder#build()} runs the analyses once. The rewriter itself is immutable and can only run, so
 * there is no unanalysed rewriter that could be run, and no analysed one that could be reconfigured.
 * <table>
 *   <tr><td>{@link ProduceUseAnalyzer}</td><td>which rule can enable which ({@link OverlapProduceUseAnalyzer})</td></tr>
 *   <tr><td>{@link WorklistOrder}</td><td>the priority of a queued rule</td></tr>
 *   <tr><td>{@link Matcher}</td><td>finding a match of a rule or guard</td></tr>
 *   <tr><td>{@link Reactor}</td><td>applying a rule at a match</td></tr>
 *   <tr><td>{@link GuardEvaluator}</td><td>matching and caching guards</td></tr>
 *   <tr><td>{@link RewriteListener}</td><td>observing a run (traces, benchmarks: {@link RewriteTrace})</td></tr>
 * </table>
 * <b>Extending.</b> The loop is a template ({@link #step}, {@link #guardsHold}, {@link #applyUntilNoMatch},
 * {@link #findMatch}, {@link #rewrite}, {@link #enqueueEnabled}), as are the per-run objects ({@link #newRun},
 * {@link #newWorklist}). A subclass overrides what it changes and is built with {@link Builder#build(Function)}, e.g.
 * {@code builder.build(MyRewriter::new)} with a constructor {@code MyRewriter(Analysis analysis)} that passes the
 * analysis to {@code super}.
 * <p>
 * <b>Contract.</b> The rewriter cannot check these, the caller has to make sure that
 * <ol>
 *   <li>the fixpoint does not depend on the order of the rewrites (e.g. monotone rules, a least fixpoint),</li>
 *   <li>the {@link ProduceUseAnalyzer} over-approximates the real dependencies,</li>
 *   <li>every guard embeds into the redex of every rule it guards,</li>
 *   <li>no run needs more than {@code maxRewrites} rewrites (otherwise it fails instead of running forever).</li>
 * </ol>
 * {@link #rewriteToFixpoint} can run in parallel threads (as long as the strategies can).
 */
public class MonotoneFixpointRewriter {

    /** The fixpoint and what it took to reach it: matcher calls for rules and for guards. */
    public record Result(PureBigraph agent, int rewrites, int ruleCalls, int guardCalls) {}

    public static Builder builder(List<Rule> rules) {
        return new Builder(rules);
    }

    // Building
    /** Collects the rule set and the strategies. {@link #build()} analyses the rule set and makes the rewriter. */
    public static final class Builder {
        private final List<Rule> rules;
        private ProduceUseAnalyzer produceUseAnalyzer = ProduceUseAnalyzer.everything();
        private WorklistOrder order = WorklistOrder.fifo();
        private Matcher matcher = Matcher.matchFirst();
        private Reactor reactor = Reactor.parametric();
        private GuardEvaluator guardEvaluator = GuardEvaluator.cached();
        private RewriteListener listener = RewriteListener.NONE;

        private Builder(List<Rule> rules) {
            this.rules = List.copyOf(rules);
        }

        public Builder withProduceUseAnalyzer(ProduceUseAnalyzer analyzer) {
            this.produceUseAnalyzer = Objects.requireNonNull(analyzer);
            return this;
        }

        public Builder withOrder(WorklistOrder order) {
            this.order = Objects.requireNonNull(order);
            return this;
        }

        public Builder withMatcher(Matcher matcher) {
            this.matcher = Objects.requireNonNull(matcher);
            return this;
        }

        public Builder withReactor(Reactor reactor) {
            this.reactor = Objects.requireNonNull(reactor);
            return this;
        }

        public Builder withGuardEvaluator(GuardEvaluator evaluator) {
            this.guardEvaluator = Objects.requireNonNull(evaluator);
            return this;
        }

        /** The listener of every run that does not pass its own. */
        public Builder withListener(RewriteListener listener) {
            this.listener = Objects.requireNonNull(listener);
            return this;
        }

        public MonotoneFixpointRewriter build() {
            return build(MonotoneFixpointRewriter::new);
        }

        /** Analyses the rule set and makes the rewriter with {@code factory}, e.g. the constructor of a subclass. */
        public <R extends MonotoneFixpointRewriter> R build(Function<Analysis, R> factory) {
            return factory.apply(new Analysis(this));
        }
    }

    /**
     * The analysed rule set with the strategies it was analysed for: what a {@link Builder} hands to the constructor.
     * Immutable, a subclass only passes it on.
     */
    public static final class Analysis {
        private final List<Rule> rules;
        private final Map<Rule, Integer> indexOf = new IdentityHashMap<>();
        private final List<Guard> guards = new ArrayList<>();
        private final int[][] guardsOf;
        private final boolean[][] enables;
        private final int[][] enabled;
        private final int[][] invalidates;
        private final int[] priority;
        private final Matcher matcher;
        private final Reactor reactor;
        private final GuardEvaluator guardEvaluator;
        private final RewriteListener listener;

        private Analysis(Builder builder) {
            this.rules = builder.rules;
            this.matcher = builder.matcher;
            this.reactor = builder.reactor;
            this.guardEvaluator = builder.guardEvaluator;
            this.listener = builder.listener;
            int ruleCount = rules.size();

            this.guardsOf = new int[ruleCount][];
            Map<Guard, Integer> guardIndex = new IdentityHashMap<>();
            for (int i = 0; i < ruleCount; i++) {
                Rule rule = rules.get(i);
                if (indexOf.put(rule, i) != null) throw new IllegalArgumentException("Rule added twice: " + rule.name());
                guardsOf[i] = rule.guards().stream().mapToInt(guard -> guardIndex.computeIfAbsent(guard, newGuard -> {
                    guards.add(newGuard);
                    return guards.size() - 1;
                })).distinct().toArray();
            }

            boolean[][] enablesMatrix = builder.produceUseAnalyzer.analyzeRuleSet(rules);
            if (enablesMatrix.length != ruleCount || Arrays.stream(enablesMatrix).anyMatch(row -> row.length != ruleCount)) {
                throw new IllegalStateException("The produce-use analysis does not fit the rule set");
            }
            int[] priorities = builder.order.priorities(rules, enablesMatrix);
            if (priorities.length != ruleCount) throw new IllegalStateException("The worklist order does not fit the rule set");
            this.enables = enablesMatrix;
            this.priority = priorities;

            this.enabled = new int[ruleCount][];
            this.invalidates = new int[ruleCount][];
            for (int ruleIndex = 0; ruleIndex < ruleCount; ruleIndex++) {
                List<Integer> targets = new ArrayList<>();
                Set<Integer> dropped = new TreeSet<>();
                for (int differentRuleIndex = 0; differentRuleIndex < ruleCount; differentRuleIndex++) {
                    if (!enablesMatrix[ruleIndex][differentRuleIndex]) continue;
                    if (differentRuleIndex != ruleIndex) targets.add(differentRuleIndex); // rule is applied until it has no match, so it never needs to queue itself
                    for (int guard : guardsOf[differentRuleIndex]) dropped.add(guard);
                }
                enabled[ruleIndex] = targets.stream().mapToInt(Integer::intValue).toArray();
                invalidates[ruleIndex] = dropped.stream().mapToInt(Integer::intValue).toArray();
            }

            // The framework fills caches of a bigraph lazily and not thread-safely; parallel runs only read them.
            JLibBigBigraphEncoder encoder = new JLibBigBigraphEncoder();
            for (Rule rule : rules) {
                encoder.encode(rule.reaction().getRedex());
                encoder.encode(rule.reaction().getReactum());
            }
            for (Guard guard : guards) encoder.encode(guard.pattern().getRedex());
        }
    }

    // The rewriter
    protected final Analysis analysis;

    protected MonotoneFixpointRewriter(Analysis analysis) {
        this.analysis = Objects.requireNonNull(analysis);
    }

    /** Rewrites {@code agent} to the fixpoint, starting with the rules {@code initialWorklist}. */
    public Result rewriteToFixpoint(PureBigraph agent, Collection<Rule> initialWorklist, int maxRewrites) {
        return rewriteToFixpoint(agent, initialWorklist, rule -> true, maxRewrites);
    }

    /**
     * Rewrites {@code agent} to the fixpoint, starting with the rules {@code initialWorklist}. Rules that are not
     * {@code allowed} are never queued, which is sound if they cannot match in this run (e.g. rules per mode).
     */
    public Result rewriteToFixpoint(PureBigraph agent, Collection<Rule> initialWorklist, Predicate<Rule> allowed,
                                    int maxRewrites) {
        return rewriteToFixpoint(agent, initialWorklist, allowed, maxRewrites, analysis.listener);
    }

    /** As above, observed by {@code listener} instead of the rewriter's listener. */
    public final Result rewriteToFixpoint(PureBigraph agent, Collection<Rule> initialWorklist, Predicate<Rule> allowed,
                                          int maxRewrites, RewriteListener listener) {
        boolean[] usable = new boolean[analysis.rules.size()];
        for (int i = 0; i < usable.length; i++) usable[i] = allowed.test(analysis.rules.get(i));
        Run run = newRun(agent, usable, maxRewrites, listener);
        listener.runStarted(agent);
        for (Rule rule : initialWorklist) run.enqueue(indexOf(rule));
        while (!run.worklist.isEmpty()) {
            int rule = run.worklist.pop();
            listener.taken(analysis.rules.get(rule));
            step(run, rule);
        }
        Result result = run.result();
        listener.runFinished(result);
        return result;
    }

    /** The state of one run. Override to keep more. */
    protected Run newRun(PureBigraph agent, boolean[] usable, int maxRewrites, RewriteListener listener) {
        return new Run(agent, usable, maxRewrites, listener);
    }

    /** The worklist of a run. */
    protected Worklist newWorklist(Run run) {
        return Worklist.byPriority(analysis.priority);
    }

    /** One rule taken from the worklist: if its guards hold, apply it until it has no match, then queue what it enables. */
    protected void step(Run run, int rule) {
        if (!guardsHold(run, rule)) return;
        if (applyUntilNoMatch(run, rule)) enqueueEnabled(run, rule);
    }

    protected boolean guardsHold(Run run, int rule) {
        for (int guard : analysis.guardsOf[rule]) {
            if (!run.guardSession.holds(guard)) {
                run.listener.pruned(analysis.rules.get(rule), analysis.guards.get(guard));
                return false;
            }
        }
        return true;
    }

    /** Whether the rule rewrote anything. */
    protected boolean applyUntilNoMatch(Run run, int rule) {
        boolean applied = false;
        while (true) {
            Optional<PureBigraphMatch> match = findMatch(run, rule);
            if (match.isEmpty()) return applied;
            rewrite(run, rule, match.get());
            applied = true;
        }
    }

    protected Optional<PureBigraphMatch> findMatch(Run run, int rule) {
        Rule matched = analysis.rules.get(rule);
        long start = System.nanoTime();
        Optional<PureBigraphMatch> match = analysis.matcher.first(run.agent, matched.reaction());
        run.ruleCalls++;
        run.listener.ruleMatched(matched, match.isPresent(), System.nanoTime() - start);
        return match;
    }

    protected void rewrite(Run run, int rule, PureBigraphMatch match) {
        Rule applied = analysis.rules.get(rule);
        if (++run.rewrites > run.maxRewrites) {
            throw new IllegalStateException("No fixpoint after " + run.maxRewrites + " rewrites (rule " + applied.name() + ")");
        }
        long start = System.nanoTime();
        run.agent = analysis.reactor.apply(run.agent, match, applied.reaction());
        run.listener.rewritten(applied, run.agent, System.nanoTime() - start);
        run.guardSession.rewritten(rule);
    }

    protected void enqueueEnabled(Run run, int rule) {
        for (int enabled : analysis.enabled[rule]) run.enqueue(enabled);
    }

    /**
     * One run: the agent, the worklist, the guard session and the counters. The rewriter's hooks change it, a subclass
     * of the rewriter can extend it (see {@link #newRun}).
     */
    public class Run {
        protected PureBigraph agent;
        protected final boolean[] usable;
        protected final int maxRewrites;
        protected final RewriteListener listener;
        protected final Worklist worklist;
        protected final GuardEvaluator.Session guardSession;
        protected int rewrites, ruleCalls, guardCalls;

        protected Run(PureBigraph agent, boolean[] usable, int maxRewrites, RewriteListener listener) {
            this.agent = agent;
            this.usable = usable;
            this.maxRewrites = maxRewrites;
            this.listener = listener;
            this.worklist = newWorklist(this);
            this.guardSession = analysis.guardEvaluator.start(this);
        }

        public MonotoneFixpointRewriter rewriter() { return MonotoneFixpointRewriter.this; }

        public PureBigraph agent() { return agent; }

        public boolean usable(int rule) { return usable[rule]; }

        public RewriteListener listener() { return listener; }

        /** Queues {@code rule} if it is usable in this run. */
        public void enqueue(int rule) {
            if (!usable[rule]) return;
            worklist.push(rule);
            listener.queued(analysis.rules.get(rule));
        }

        /** Matches guard {@code guard} against the agent (timed, reported). */
        public boolean matchGuard(int guardIndex) {
            Guard guard = analysis.guards.get(guardIndex);
            long timestamp = System.nanoTime();
            boolean holds = analysis.matcher.first(agent, guard.pattern()).isPresent();
            guardCalls++;
            listener.guardMatched(guard, holds, System.nanoTime() - timestamp);
            return holds;
        }

        /** Counts the occurrences of guard {@code guard} in the agent (one matcher call enumerating all matches, timed, reported). */
        public int countGuard(int guardIndex) {
            Guard guard = analysis.guards.get(guardIndex);
            long timestamp = System.nanoTime();
            int occurrences = analysis.matcher.occurrences(agent, guard.pattern());
            guardCalls++;
            listener.guardMatched(guard, occurrences > 0, System.nanoTime() - timestamp);
            return occurrences;
        }

        public Result result() { return new Result(agent, rewrites, ruleCalls, guardCalls); }
    }

    // Accessors
    public List<Rule> rules() { return analysis.rules; }

    public int indexOf(Rule rule) {
        Integer index = analysis.indexOf.get(rule);
        if (index == null) throw new IllegalArgumentException("Rule not in the rule set: " + rule.name());
        return index;
    }

    public List<Guard> guards() { return Collections.unmodifiableList(analysis.guards); }

    /** Indices of the guards of rule {@code rule}. */
    public int[] guardsOf(int rule) { return analysis.guardsOf[rule].clone(); }

    /** {@code enables()[rule][other]}: a rewrite by {@code rule} can give {@code other} a new match. A copy. */
    public boolean[][] enables() {
        boolean[][] out = new boolean[analysis.enables.length][];
        for (int i = 0; i < out.length; i++) out[i] = analysis.enables[i].clone();
        return out;
    }

    /** The rules queued after rule {@code rule} rewrote something (the enabled ones but itself). */
    public int[] enabledBy(int rule) { return analysis.enabled[rule].clone(); }

    /**
     * The guards whose answer a rewrite by {@code rule} can change from "does not match" to "matches" (the guards of
     * the rules it enables). Not a copy, since the guard evaluator asks after every rewrite: do not modify.
     */
    public int[] invalidatedBy(int rule) { return analysis.invalidates[rule]; }

    public int[] priorities() { return analysis.priority.clone(); }
}
