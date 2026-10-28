package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import org.bigraphs.framework.core.impl.pure.PureBigraph;

import java.util.List;

/**
 * Observes a run of a {@link MonotoneFixpointRewriter}, e.g. to trace or benchmark it ({@link RewriteTrace}). Every
 * method has an empty default. Times are in nanoseconds, measured by the rewriter around the call they describe.
 * Called on the thread of the run.
 */
public interface RewriteListener {
    RewriteListener NONE = new RewriteListener() {};

    default void runStarted(PureBigraph agent) {}

    default void queued(Rule rule) {}

    /** A rule taken from the worklist, before its guards are evaluated. */
    default void taken(Rule rule) {}

    /** A guard answered by a matcher call. */
    default void guardMatched(Guard guard, boolean holds, long nanos) {}

    /** A rule skipped because {@code guard} does not hold (possibly a cached answer). */
    default void pruned(Rule rule, Guard guard) {}

    /** A matcher call for a rule. */
    default void ruleMatched(Rule rule, boolean matched, long nanos) {}

    /** A rewrite by {@code rule}: the reaction itself. */
    default void rewritten(Rule rule, PureBigraph agent, long nanos) {}

    default void runFinished(MonotoneFixpointRewriter.Result result) {}

    /** All of {@code listeners}, in order. */
    static RewriteListener all(RewriteListener... listeners) {
        List<RewriteListener> all = List.of(listeners);
        return new RewriteListener() {
            @Override public void runStarted(PureBigraph agent) { all.forEach(listener -> listener.runStarted(agent)); }
            @Override public void queued(Rule rule) { all.forEach(listener -> listener.queued(rule)); }
            @Override public void taken(Rule rule) { all.forEach(listener -> listener.taken(rule)); }
            @Override public void guardMatched(Guard guard, boolean holds, long nanos) { all.forEach(listener -> listener.guardMatched(guard, holds, nanos)); }
            @Override public void pruned(Rule rule, Guard guard) { all.forEach(listener -> listener.pruned(rule, guard)); }
            @Override public void ruleMatched(Rule rule, boolean matched, long nanos) { all.forEach(listener -> listener.ruleMatched(rule, matched, nanos)); }
            @Override public void rewritten(Rule rule, PureBigraph agent, long nanos) { all.forEach(listener -> listener.rewritten(rule, agent, nanos)); }
            @Override public void runFinished(MonotoneFixpointRewriter.Result result) { all.forEach(listener -> listener.runFinished(result)); }
        };
    }
}
