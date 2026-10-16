package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

/**
 * Counters of one rewriting session, for profiling and for the thesis' cost breakdown.
 *
 * @param rounds          evaluation rounds (full-agent analyzer) or fragment evaluations (fragment analyzer)
 * @param matcherCalls    calls of the framework's {@code PureBigraphMatcher#match} (which runs jLibBig underneath)
 * @param successfulCalls matcher calls that found at least one (usable) match
 * @param agentNodes      full-agent analyzer: nodes of the coverage bigraph; fragment analyzer: nodes of the
 *                        largest fragment
 * @param buildNanos      full-agent analyzer: building the coverage bigraphs (framework bigraphs, so no separate
 *                        encode step); fragment analyzer: the structure snapshot and the per-fragment census
 * @param encodeNanos     fragment analyzer: building each fragment as a framework bigraph; 0 for the full-agent
 *                        analyzer
 * @param matchNanos      time spent in the matcher, including the framework's translation to jLibBig and iterating
 *                        the matches
 */
public record RewritingStats(int rounds, int matcherCalls, int successfulCalls, int agentNodes,
                             long buildNanos, long encodeNanos, long matchNanos) {
    @Override
    public String toString() {
        return String.format("%d rounds, %d matcher calls (%d matched), agent %d nodes, build %.1f ms, encode %.1f ms, match %.1f ms",
                rounds, matcherCalls, successfulCalls, agentNodes, buildNanos / 1e6, encodeNanos / 1e6, matchNanos / 1e6);
    }
}
