package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

/**
 * Counters of one rewriting session, for profiling and for the thesis' cost breakdown.
 *
 * @param rounds          evaluation rounds (full-agent analyzer) or fragment evaluations (fragment analyzer)
 * @param matcherCalls    calls of jLibBig's {@code AgentMatcher#match}
 * @param successfulCalls matcher calls that found at least one match
 * @param agentNodes      nodes of the largest bigraph handed to the matcher
 * @param buildNanos      time spent building coverage bigraphs / fragments
 * @param encodeNanos     time spent encoding them for jLibBig
 * @param matchNanos      time spent in the matcher (including iterating its matches)
 */
public record RewritingStats(int rounds, int matcherCalls, int successfulCalls, int agentNodes,
                             long buildNanos, long encodeNanos, long matchNanos) {
    @Override
    public String toString() {
        return String.format("%d rounds, %d matcher calls (%d matched), agent %d nodes, build %.1f ms, encode %.1f ms, match %.1f ms",
                rounds, matcherCalls, successfulCalls, agentNodes, buildNanos / 1e6, encodeNanos / 1e6, matchNanos / 1e6);
    }
}
