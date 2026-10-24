package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

/**
 * Counters of one session of {@link RewritingCoverageAnalyzer}, for profiling and for the thesis' cost
 * breakdown.
 *
 * @param rounds          evaluation rounds (waves)
 * @param matcherCalls    calls of the framework's {@code PureBigraphMatcher} (rule and guard calls; jLibBig underneath)
 * @param successfulCalls matcher calls that found at least one match
 * @param agentNodes      nodes of the coverage bigraph at the start
 * @param buildNanos      building the coverage bigraphs
 * @param matchNanos      time spent in the matcher, including the framework's translation to jLibBig and iterating
 *                        the matches
 * @param rewriteNanos    with {@code rewrite}: building the rewritten agents ({@code buildParametricReaction}); else 0
 * @param readoutNanos    with {@code rewrite}: walking the fixpoint bigraph for states and reasons; else 0
 */
public record RewritingStats(int rounds, int matcherCalls, int successfulCalls, int agentNodes,
                             long buildNanos, long matchNanos, long rewriteNanos, long readoutNanos) {
    public RewritingStats(int rounds, int matcherCalls, int successfulCalls, int agentNodes,
                          long buildNanos, long matchNanos) {
        this(rounds, matcherCalls, successfulCalls, agentNodes, buildNanos, matchNanos, 0, 0);
    }

    @Override
    public String toString() {
        String s = String.format("%d rounds, %d matcher calls (%d matched), agent %d nodes, build %.1f ms, match %.1f ms",
                rounds, matcherCalls, successfulCalls, agentNodes, buildNanos / 1e6, matchNanos / 1e6);
        if (rewriteNanos > 0 || readoutNanos > 0)
            s += String.format(", rewrite %.1f ms, readout %.2f ms", rewriteNanos / 1e6, readoutNanos / 1e6);
        return s;
    }
}
