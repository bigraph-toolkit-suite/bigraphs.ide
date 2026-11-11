package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.*;

import java.util.*;
import java.util.stream.Stream;

/**
 * The analyzer configurations the benchmarks compare. A configuration is the editor's default with some optimizations
 * switched off or replaced, so whatever a comparison does not vary is always the default. The groups:
 * <ul>
 *   <li><b>ladder</b>: from the {@code baseline} (no optimization at all) to the {@code default}, adding one
 *   optimization per step, in the order of the thesis: the overlap analysis decides which rules a rewrite queues
 *   ({@code +overlap}), the worklist in topological order ({@code +topological}), only the rules the change can have
 *   enabled at the start ({@code +seed}), only the rules that can match in the mode ({@code +per-mode}), the receiver
 *   guards matched and kept until an enabling rewrite ({@code +guards}), and counted ({@code default}).</li>
 *   <li><b>ablation</b>: the default without one optimization, or with another strategy in its place.</li>
 *   <li><b>encodings</b>: both gate rule sets (threshold, the editor's, and one rule per combination of slot states)
 *   under both dependency analyses (the generic overlap analysis and the coarse control-pair analysis of
 *   {@link EncodingComparison}), with the plain worklist and with every other optimization of the default.</li>
 *   <li><b>domain</b>: the {@link DomainCoverageAnalyzer}, the reference, which does not rewrite.</li>
 * </ul>
 * Every configuration runs through {@link RewritingCoverageAnalyzer} (from scratch) and
 * {@link IncrementalRewritingCoverageAnalyzer} (incrementally), so the configurations only differ in what they name.
 */
public final class BenchmarkConfigs {
    private BenchmarkConfigs() {}

    public enum GateRules { THRESHOLD, COMBINATION }

    public enum Dependencies { EVERYTHING, OVERLAP, CONTROLS }

    public enum Guards { NONE, CACHED, UNCACHED, COUNTED }

    /**
     * One configuration. {@code domain} configurations ignore every other field.
     *
     * @param dependencies which rules a rewrite queues: every rule, the overlap analysis, or the control-pair analysis
     * @param topological  whether the worklist is ordered topologically (otherwise first in first out)
     * @param matchAll     whether the matcher enumerates all matches and takes the first (otherwise {@code matchFirst})
     * @param seeded       whether a session starts with the seed only (otherwise with every usable rule)
     * @param perMode      whether only the rules that can match in the mode are used
     */
    public record Config(String name, String group, String description, boolean domain, GateRules gateRules,
                         Dependencies dependencies, boolean topological, Guards guards, boolean matchAll, boolean seeded,
                         boolean perMode) {

        Config named(String newName, String newGroup, String newDescription) {
            return new Config(newName, newGroup, newDescription, domain, gateRules, dependencies, topological, guards,
                    matchAll, seeded, perMode);
        }

        Config withGateRules(GateRules value) {
            return new Config(name, group, description, domain, value, dependencies, topological, guards, matchAll, seeded, perMode);
        }

        Config withDependencies(Dependencies value) {
            return new Config(name, group, description, domain, gateRules, value, topological, guards, matchAll, seeded, perMode);
        }

        Config withTopological(boolean value) {
            return new Config(name, group, description, domain, gateRules, dependencies, value, guards, matchAll, seeded, perMode);
        }

        Config withGuards(Guards value) {
            return new Config(name, group, description, domain, gateRules, dependencies, topological, value, matchAll, seeded, perMode);
        }

        Config withMatchAll(boolean value) {
            return new Config(name, group, description, domain, gateRules, dependencies, topological, guards, value, seeded, perMode);
        }

        Config withSeeded(boolean value) {
            return new Config(name, group, description, domain, gateRules, dependencies, topological, guards, matchAll, value, perMode);
        }

        Config withPerMode(boolean value) {
            return new Config(name, group, description, domain, gateRules, dependencies, topological, guards, matchAll, seeded, value);
        }
    }

    /** The editor's analyzer ({@link RewritingCoverageAnalyzer#RewritingCoverageAnalyzer(CoverageInput)}). */
    public static final Config DEFAULT = new Config("default", "default", "the editor's analyzer", false,
            GateRules.THRESHOLD, Dependencies.OVERLAP, true, Guards.COUNTED, false, true, true);

    public static final Config DOMAIN = new Config("domain", "domain", "the domain analyzer (no rewriting)", true,
            GateRules.THRESHOLD, Dependencies.EVERYTHING, false, Guards.NONE, false, false, false);

    private static final Config BASELINE = DEFAULT.withDependencies(Dependencies.EVERYTHING).withTopological(false)
            .withGuards(Guards.NONE).withSeeded(false).withPerMode(false);

    /** From the baseline to the default, one optimization per step. The last step is the default itself. */
    public static final List<Config> LADDER = List.of(
            BASELINE.named("baseline", "ladder", "no optimization: every rewrite queues every rule"),
            BASELINE.withDependencies(Dependencies.OVERLAP).named("+overlap", "ladder", "+ overlap analysis"),
            BASELINE.withDependencies(Dependencies.OVERLAP).withTopological(true)
                    .named("+topological", "ladder", "+ topological order"),
            BASELINE.withDependencies(Dependencies.OVERLAP).withTopological(true).withSeeded(true)
                    .named("+seed", "ladder", "+ seed"),
            BASELINE.withDependencies(Dependencies.OVERLAP).withTopological(true).withSeeded(true).withPerMode(true)
                    .named("+per-mode", "ladder", "+ rules per mode"),
            DEFAULT.withGuards(Guards.CACHED).named("+guards", "ladder", "+ guards (cached)"),
            DEFAULT);

    /** The default without one optimization, or with another strategy in its place. */
    public static final List<Config> ABLATION = List.of(
            DEFAULT.withSeeded(false).named("no-seed", "ablation", "every usable rule at the start"),
            DEFAULT.withPerMode(false).named("no-per-mode", "ablation", "every rule in every mode"),
            DEFAULT.withTopological(false).named("fifo", "ablation", "first in first out instead of topological"),
            DEFAULT.withDependencies(Dependencies.EVERYTHING).named("no-overlap", "ablation", "every rewrite queues every rule"),
            DEFAULT.withGuards(Guards.CACHED).named("guards-cached", "ablation", "guards cached instead of counted"),
            DEFAULT.withGuards(Guards.UNCACHED).named("guards-uncached", "ablation", "guards matched every time"),
            DEFAULT.withGuards(Guards.NONE).named("no-guards", "ablation", "no guards"),
            DEFAULT.withMatchAll(true).named("match-all", "ablation", "matchAll instead of matchFirst"));

    /** Both gate rule sets under both dependency analyses, with the plain worklist and with the default otherwise. */
    public static final List<Config> ENCODINGS = encodings();

    private static List<Config> encodings() {
        List<Config> out = new ArrayList<>();
        Config worklist = BASELINE.withTopological(true);
        for (GateRules gateRules : GateRules.values()) {
            for (Dependencies dependencies : List.of(Dependencies.OVERLAP, Dependencies.CONTROLS)) {
                String label = gateRules.name().toLowerCase(Locale.ROOT) + "-" + dependencies.name().toLowerCase(Locale.ROOT);
                out.add(worklist.withGateRules(gateRules).withDependencies(dependencies).named(label + "-worklist",
                        "encodings", label + ", worklist only (topological, no seed, no guards, every rule)"));
                out.add(DEFAULT.withGateRules(gateRules).withDependencies(dependencies).named(label + "-default",
                        "encodings", label + ", every other optimization of the default"));
            }
        }
        return List.copyOf(out);
    }

    /** Every configuration once (the default and the threshold-overlap default of the encodings are the same). */
    public static final List<Config> ALL = Stream.of(LADDER, ABLATION, ENCODINGS, List.of(DOMAIN))
            .flatMap(List::stream).toList();

    /**
     * The configurations named in {@code names}, comma separated, in the given order. A group name ({@code ladder},
     * {@code ablation}, {@code encodings}) stands for its configurations, {@code all} for every one.
     */
    public static List<Config> named(String names) {
        Map<String, Config> out = new LinkedHashMap<>();
        for (String name : names.split(",")) {
            List<Config> matching = switch (name) {
                case "all" -> ALL;
                case "ladder" -> LADDER;
                case "ablation" -> ABLATION;
                case "encodings" -> ENCODINGS;
                default -> ALL.stream().filter(config -> config.name().equals(name)).limit(1).toList();
            };
            if (matching.isEmpty()) throw new IllegalArgumentException("Unknown configuration " + name);
            for (Config config : matching) out.putIfAbsent(config.name(), config);
        }
        return List.copyOf(out.values());
    }

    /** The one configuration called {@code name}. */
    public static Config byName(String name) {
        return ALL.stream().filter(config -> config.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown configuration " + name));
    }

    // The rule sets and dependency matrices, built once per JVM

    private static CoverageRuleSet combinationRules;
    private static final Map<GateRules, boolean[][]> controlPairs = new EnumMap<>(GateRules.class);

    static synchronized CoverageRuleSet ruleSet(GateRules gateRules) {
        if (gateRules == GateRules.THRESHOLD) return CoverageRuleSet.DEFAULT;
        if (combinationRules == null) {
            combinationRules = new CoverageRuleSet(CoverageRewriteRules.compile(CoverageRewriteRules.specs(false)));
        }
        return combinationRules;
    }

    /** The control-pair analysis of a gate rule set, in the order of {@link #ruleSet}. */
    static synchronized boolean[][] controlPairs(GateRules gateRules) {
        return controlPairs.computeIfAbsent(gateRules, key ->
                EncodingComparison.controlPairs(CoverageRewriteRules.specs(key == GateRules.THRESHOLD)));
    }

    /**
     * The hits-only bound of a run: the time of its hitting rule calls and of the rewrites they lead to, as if a perfect
     * pruning had made only those calls. Taken from the run itself rather than from a replay: the matcher picks its
     * match by the internal order of the agent, so a replay on a copy may choose other matches and take another path
     * to the fixpoint. With {@code trace}, the hitting calls are added to it back to back (each followed by its
     * rewrite), starting at {@code start}.
     */
    public static long hitsOnly(CallRecorder run, CallRecorder trace, long start) {
        long time = 0;
        for (int index = 0; index < run.hitNanos.size(); index++) {
            if (trace != null) {
                trace.calls.add(new CallRecorder.Call("RULE", run.hitRules.get(index).name(), true, start + time,
                        run.hitNanos.get(index)));
            }
            time += run.hitNanos.get(index) + run.hitRewriteNanos.get(index);
        }
        return time;
    }

    /** One configuration, built once (its static analysis), and the recorder that observes its runs. */
    public static final class Built {
        public final Config config;
        public final CallRecorder recorder = new CallRecorder();
        /** {@code null} for the domain analyzer. */
        public final MonotoneFixpointRewriter rewriter;
        private final CoverageRuleSet ruleSet;

        public Built(Config config) {
            this.config = config;
            if (config.domain()) {
                this.ruleSet = null;
                this.rewriter = null;
                return;
            }
            this.ruleSet = ruleSet(config.gateRules());
            ProduceUseAnalyzer dependencies = switch (config.dependencies()) {
                case EVERYTHING -> ProduceUseAnalyzer.everything();
                case OVERLAP -> new OverlapProduceUseAnalyzer();
                case CONTROLS -> ProduceUseAnalyzer.of(controlPairs(config.gateRules()));
            };
            GuardEvaluator guards = switch (config.guards()) {
                case NONE, CACHED -> GuardEvaluator.cached();
                case UNCACHED -> GuardEvaluator.uncached();
                case COUNTED -> GuardEvaluator.counting();
            };
            this.rewriter = MonotoneFixpointRewriter.builder(ruleSet.rewriterRules(config.guards() != Guards.NONE))
                    .withProduceUseAnalyzer(dependencies)
                    .withOrder(config.topological() ? WorklistOrder.topological() : WorklistOrder.fifo())
                    .withMatcher(config.matchAll() ? Matcher.matchAll() : Matcher.matchFirst())
                    .withGuardEvaluator(guards)
                    .withListener(recorder)
                    .build();
        }

        /** An analyzer that evaluates every session from scratch with this configuration. */
        public CoverageAnalyzer fromScratch(Synced popp) {
            CoverageInput input = CoverageInput.of(popp.model, popp.bigraph);
            if (config.domain()) return new DomainCoverageAnalyzer(input);
            return engine(input);
        }

        /**
         * The incremental analyzer with this configuration, registered with the model's bigraph. The domain analyzer
         * has no incremental form, so for it every session is computed from scratch.
         */
        public CoverageAnalyzer incremental(Synced popp) {
            CoverageInput input = CoverageInput.of(popp.model, popp.bigraph);
            if (config.domain()) return new DomainCoverageAnalyzer(input);
            var analyzer = new IncrementalRewritingCoverageAnalyzer(input, engine(input));
            popp.bigraph.addListener(analyzer);
            return analyzer;
        }

        private RewritingCoverageAnalyzer engine(CoverageInput input) {
            return new RewritingCoverageAnalyzer(input, rewriter, ruleSet, config.seeded(), config.perMode());
        }

        /** The number of rules, of guards, and the rules a rule enables on average. Zero for the domain analyzer. */
        public double[] staticFigures() {
            if (rewriter == null) return new double[3];
            int rules = rewriter.rules().size();
            double enabled = 0;
            for (int index = 0; index < rules; index++) enabled += rewriter.enabledBy(index).length;
            return new double[]{rules, rewriter.guards().size(), enabled / rules};
        }
    }

    /**
     * Counts what the rewriter does in the runs since the last {@link #reset()}, and with {@link #trace} on also keeps
     * every matcher call with its start time. Runs on one thread at a time (the benchmarks evaluate one session at a
     * time).
     */
    public static final class CallRecorder implements RewriteListener {
        /** A matcher call: kind {@code GUARD} or {@code RULE}, absolute start in nanoseconds. */
        public record Call(String kind, String name, boolean matched, long start, long nanos) {}

        public int ruleCalls, hits, guardCalls, guardHolds, rewrites;
        public long ruleNanos, guardNanos, rewriteNanos;
        public boolean trace;
        public final List<Call> calls = new ArrayList<>();
        /** The rules that hit in the runs since the last reset, in order, with the time of the call and of the rewrite. */
        public final List<Rule> hitRules = new ArrayList<>();
        public final List<Long> hitNanos = new ArrayList<>(), hitRewriteNanos = new ArrayList<>();

        public void reset() {
            ruleCalls = hits = guardCalls = guardHolds = rewrites = 0;
            ruleNanos = guardNanos = rewriteNanos = 0;
            calls.clear();
            hitRules.clear();
            hitNanos.clear();
            hitRewriteNanos.clear();
        }

        public long matcherNanos() {
            return ruleNanos + guardNanos;
        }

        /** The hits since the last reset on their own, for the hits-only bound after the recorder moved on. */
        public CallRecorder snapshot() {
            CallRecorder copy = new CallRecorder();
            copy.hitRules.addAll(hitRules);
            copy.hitNanos.addAll(hitNanos);
            copy.hitRewriteNanos.addAll(hitRewriteNanos);
            return copy;
        }

        @Override
        public void guardMatched(Guard guard, boolean holds, long nanos) {
            guardCalls++;
            if (holds) guardHolds++;
            guardNanos += nanos;
            if (trace) calls.add(new Call("GUARD", guard.label(), holds, System.nanoTime() - nanos, nanos));
        }

        @Override
        public void ruleMatched(Rule rule, boolean matched, long nanos) {
            ruleCalls++;
            if (matched) {
                hits++;
                hitRules.add(rule);
                hitNanos.add(nanos);
            }
            ruleNanos += nanos;
            if (trace) calls.add(new Call("RULE", rule.name(), matched, System.nanoTime() - nanos, nanos));
        }

        @Override
        public void rewritten(Rule rule, PureBigraph agent, long nanos) {
            rewrites++;
            rewriteNanos += nanos;
            hitRewriteNanos.add(nanos);
        }
    }
}
