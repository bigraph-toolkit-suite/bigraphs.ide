package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import org.bigraphs.framework.core.impl.pure.PureBigraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link RewriteListener} that records every matcher call and rewrite of a run with its start time
 * (relative to the start of the run) and duration, for traces and benchmarks. One instance per run.
 */
public final class RewriteTrace implements RewriteListener {
    public enum Kind { GUARD, RULE, REWRITE }

    /** {@code matched}: guard holds / rule matched, always true for rewrites. Times in nanoseconds. */
    public record Event(Kind kind, String name, boolean matched, long start, long nanos) {}

    private final List<Event> events = new ArrayList<>();
    private long t0;

    @Override
    public void runStarted(PureBigraph agent) {
        events.clear();
        t0 = System.nanoTime();
    }

    @Override
    public void guardMatched(Guard guard, boolean holds, long nanos) { add(Kind.GUARD, guard.label(), holds, nanos); }

    @Override
    public void ruleMatched(Rule rule, boolean matched, long nanos) { add(Kind.RULE, rule.name(), matched, nanos); }

    @Override
    public void rewritten(Rule rule, PureBigraph agent, long nanos) { add(Kind.REWRITE, rule.name(), true, nanos); }

    private void add(Kind kind, String name, boolean matched, long nanos) {
        events.add(new Event(kind, name, matched, System.nanoTime() - nanos - t0, nanos));
    }

    public List<Event> events() { return Collections.unmodifiableList(events); }

    public long count(Kind kind) { return events.stream().filter(event -> event.kind() == kind).count(); }

    public long count(Kind kind, boolean matched) {
        return events.stream().filter(event -> event.kind() == kind && event.matched() == matched).count();
    }

    public long nanos(Kind kind) { return events.stream().filter(event -> event.kind() == kind).mapToLong(Event::nanos).sum(); }
}
