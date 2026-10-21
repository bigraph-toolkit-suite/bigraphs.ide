package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What a whole-agent rewriting session did, call by call and round by round, for comparing evaluation strategies
 * side by side (active rules, misses and time over the session). Recorded only when the analyzer is asked to
 * ({@code setTracing(true)}); a traced session is slower than an untraced one (the state census per round), so
 * benchmark without tracing.
 * <p>
 * Times are nanoseconds since the session started.
 */
public final class RewritingTrace {

    /** What a call was for. */
    public enum Kind { GUARD, RULE }

    /**
     * One matcher call.
     *
     * @param matched  the pattern had at least one match
     * @param upgraded (rules) at least one match upgraded a node, i.e. the call was a hit; a matched call without an
     *                 upgrade only found stale matches
     */
    public record Call(int round, Kind kind, String name, boolean matched, boolean upgraded, long start, long nanos) {
        /** A hit: a rule call that upgraded something. Guards are never hits, they only avoid rule calls. */
        public boolean hit() { return kind == Kind.RULE && upgraded; }
    }

    /**
     * One round (wave).
     *
     * @param queued         rules that were active (queued) at the start of the round
     * @param prefiltered    active rules the structural prefilter ruled out without a matcher call
     * @param guarded        active rules a failed guard ruled out
     * @param buildNanos     rebuilding (or rewriting into) the agent after the round
     * @param agentNodes     nodes of the agent the round matched against
     * @param uncovered      domain nodes per state after the round
     */
    public record Round(int round, int queued, int prefiltered, int guarded, long start, long nanos, long buildNanos,
                        int agentNodes, int uncovered, int partial, int covered) {}

    private final long t0 = System.nanoTime();
    private final List<Call> calls = new ArrayList<>();
    private final List<Round> rounds = new ArrayList<>();

    long now() { return System.nanoTime() - t0; }

    void call(int round, Kind kind, String name, boolean matched, boolean upgraded, long start, long end) {
        calls.add(new Call(round, kind, name, matched, upgraded, start, end - start));
    }

    void round(int round, int queued, int prefiltered, int guarded, long start, long end, long buildNanos,
               int agentNodes, Map<String, Coverage> states) {
        int u = 0, p = 0, c = 0;
        for (Coverage s : states.values()) {
            switch (s) {
                case UNCOVERED -> u++;
                case PARTIAL -> p++;
                case COVERED -> c++;
            }
        }
        rounds.add(new Round(round, queued, prefiltered, guarded, start, end - start, buildNanos, agentNodes, u, p, c));
    }

    public List<Call> calls() { return Collections.unmodifiableList(calls); }

    public List<Round> rounds() { return Collections.unmodifiableList(rounds); }
}
