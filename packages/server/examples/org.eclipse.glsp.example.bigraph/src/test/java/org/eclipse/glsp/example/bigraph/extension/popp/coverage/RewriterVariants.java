package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.*;

import java.util.*;
import java.util.function.Function;

/**
 * The configurations of the {@link MonotoneFixpointRewriter} the benchmarks compare, from the baseline (no analysis at
 * all) to the analyzer's default, each adding one optimization:
 * <ol>
 *   <li>{@code baseline}: every rule enables every rule, first in first out, no guards. A rewrite queues all rules.</li>
 *   <li>{@code worklist}: the generic overlap analysis decides which rules a rewrite queues.</li>
 *   <li>{@code topological}: the worklist ordered by the strongly connected components of the dependencies.</li>
 *   <li>{@code guards}: the receiver guards, matched and kept until an enabling rewrite ({@link GuardEvaluator#cached()}).</li>
 *   <li>{@code counted}: the guards counted ({@link GuardEvaluator#counting()}). This is the analyzer's default.</li>
 * </ol>
 * All of them run the same rules with the same seed and the same per-mode pruning, through
 * {@link RewritingCoverageAnalyzer} and {@link IncrementalRewritingCoverageAnalyzer}, so they only differ in the
 * rewriter.
 */
public final class RewriterVariants {
    private RewriterVariants() {}

    /** A configuration, built with a listener that observes every run. */
    public record Variant(String name, String description, Function<RewriteListener, MonotoneFixpointRewriter> build) {}

    public static final List<Variant> ALL = List.of(
            new Variant("baseline", "no analysis: every rewrite queues every rule",
                    listener -> builder(false).withListener(listener).build()),
            new Variant("worklist", "+ overlap analysis",
                    listener -> builder(false).withProduceUseAnalyzer(new OverlapProduceUseAnalyzer())
                            .withListener(listener).build()),
            new Variant("topological", "+ topological order",
                    listener -> builder(false).withProduceUseAnalyzer(new OverlapProduceUseAnalyzer())
                            .withOrder(WorklistOrder.topological()).withListener(listener).build()),
            new Variant("guards", "+ guards (cached)",
                    listener -> builder(true).withProduceUseAnalyzer(new OverlapProduceUseAnalyzer())
                            .withOrder(WorklistOrder.topological()).withGuardEvaluator(GuardEvaluator.cached())
                            .withListener(listener).build()),
            new Variant("counted", "+ guards counted (default)",
                    listener -> builder(true).withProduceUseAnalyzer(new OverlapProduceUseAnalyzer())
                            .withOrder(WorklistOrder.topological()).withGuardEvaluator(GuardEvaluator.counting())
                            .withListener(listener).build()));

    /** The variants named in {@code names} (comma separated, {@code all} for every one), in the order of {@link #ALL}. */
    public static List<Variant> named(String names) {
        if (names.equals("all")) return ALL;
        Set<String> wanted = new HashSet<>(List.of(names.split(",")));
        List<Variant> out = ALL.stream().filter(variant -> wanted.contains(variant.name())).toList();
        if (out.size() != wanted.size()) throw new IllegalArgumentException("Unknown variant in " + names);
        return out;
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
        for (int i = 0; i < run.hitNanos.size(); i++) {
            if (trace != null) {
                trace.calls.add(new CallRecorder.Call("RULE", run.hitRules.get(i).name(), true, start + time, run.hitNanos.get(i)));
            }
            time += run.hitNanos.get(i) + run.hitRewriteNanos.get(i);
        }
        return time;
    }

    private static MonotoneFixpointRewriter.Builder builder(boolean guarded) {
        return MonotoneFixpointRewriter.builder(RewritingCoverageAnalyzer.rewriterRules(guarded))
                .withMatcher(RewritingCoverageAnalyzer.FIRST_MATCH_WORKS ? Matcher.matchFirst() : Matcher.matchAll());
    }

    /** One variant, built once, and the recorder that observes its runs. */
    public static final class Built {
        public final Variant variant;
        public final CallRecorder recorder = new CallRecorder();
        public final MonotoneFixpointRewriter rewriter;

        public Built(Variant variant) {
            this.variant = variant;
            this.rewriter = variant.build().apply(recorder);
        }

        /** An analyzer that evaluates every session from scratch with this variant. */
        public CoverageAnalyzer fromScratch(Synced popp) {
            return new RewritingCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph), rewriter);
        }

        /** The incremental analyzer with this variant, registered with the model's bigraph. */
        public IncrementalRewritingCoverageAnalyzer incremental(Synced popp) {
            CoverageInput input = CoverageInput.of(popp.model, popp.bigraph);
            var analyzer = new IncrementalRewritingCoverageAnalyzer(input, new RewritingCoverageAnalyzer(input, rewriter));
            popp.bigraph.addListener(analyzer);
            return analyzer;
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
        public long matcherNanos, rewriteNanos;
        public boolean trace;
        public final List<Call> calls = new ArrayList<>();
        /** The rules that hit in the runs since the last reset, in order, with the time of the call and of the rewrite. */
        public final List<Rule> hitRules = new ArrayList<>();
        public final List<Long> hitNanos = new ArrayList<>(), hitRewriteNanos = new ArrayList<>();

        public void reset() {
            ruleCalls = hits = guardCalls = guardHolds = rewrites = 0;
            matcherNanos = rewriteNanos = 0;
            calls.clear();
            hitRules.clear();
            hitNanos.clear();
            hitRewriteNanos.clear();
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
            matcherNanos += nanos;
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
            matcherNanos += nanos;
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
