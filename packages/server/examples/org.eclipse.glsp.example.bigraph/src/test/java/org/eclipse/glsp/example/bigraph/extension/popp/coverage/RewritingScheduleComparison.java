package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;

import java.util.*;

/**
 * Quick comparison of the whole-agent rewriting schedules (not a JMH benchmark; use {@code CoverageBenchmark} with
 * {@code -p analyzer=...} for the thesis numbers). Per analyzer and balanced-tree depth: median session time over
 * {@code runs} sessions after {@code warmup} sessions, and the counters of the last session.
 * <p>
 * On {@link CoverageTestModels#fullModel} (6, 18, 42, 90 POPP nodes for depth 0..3).
 * <p>
 * {@code RewritingScheduleComparison [maxDepth=2] [runs=5] [warmup=2] [mode=VERIFY] [analyzers=...]}
 */
public final class RewritingScheduleComparison {
    static final List<String> DEFAULT = List.of("worklist", "worklist-seeded", "guards-first", "prefilter",
            "worklist-guards-seeded-first", "worklist-prefilter-seeded", "worklist-guards-seeded-first-prefilter", "oracle");

    public static void main(String[] args) {
        int maxDepth = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int runs = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int warmup = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        CoverageMode mode = args.length > 3 ? CoverageMode.valueOf(args[3]) : CoverageMode.VERIFY;
        List<String> analyzers = args.length > 4 ? List.of(args[4].split(",")) : DEFAULT;

        System.out.println(ScheduledRewritingCoverageAnalyzer.analysisSummary());
        System.out.printf("%-36s %5s %6s %10s %7s %13s %7s %7s %9s %9s %10s%n",
                "analyzer", "depth", "nodes", "median ms", "waves", "matcher calls", "guards", "matched",
                "match ms", "build ms", "filter ms");
        for (int depth = 0; depth <= maxDepth; depth++) {
            for (String name : analyzers) {
                Synced popp = new Synced();
                CoverageTestModels.fullModel(popp.model, depth, true);
                CoverageAnalyzer analyzer = CoverageTestModels.analyzer(name, popp);
                for (int i = 0; i < warmup; i++) analyzer.session(mode);
                double[] ms = new double[runs];
                for (int i = 0; i < runs; i++) {
                    long t = System.nanoTime();
                    analyzer.session(mode);
                    ms[i] = (System.nanoTime() - t) / 1e6;
                }
                Arrays.sort(ms);
                RewritingStats st = analyzer instanceof OracleRewritingCoverageAnalyzer o ? o.lastStats()
                        : ((ScheduledRewritingCoverageAnalyzer) analyzer).lastStats();
                int guards = analyzer instanceof ScheduledRewritingCoverageAnalyzer s ? s.lastGuardCalls() : 0;
                System.out.printf("%-36s %5d %6d %10.1f %7d %13d %7d %7d %9.1f %9.1f %10.1f%n", name, depth,
                        popp.model.getAllNodes().size(), ms[runs / 2], st.rounds(), st.matcherCalls(), guards,
                        st.successfulCalls(), st.matchNanos() / 1e6, st.buildNanos() / 1e6, st.prefilterNanos() / 1e6);
            }
        }
    }
}
