package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Traces one session per whole-agent variant on the benchmark models and writes them side by side as CSV, for
 * {@code plot_traces.py}: what was active, what was skipped, which calls hit or missed, round by round and over time.
 * Each variant runs {@code warmup} untraced sessions first, so the traced one is warm. Not a benchmark: tracing adds a
 * little overhead (a state census per round, and for the rewriting variants a readout per round).
 * <p>
 * {@code RewritingTraceReport <out-dir> [depths=0,1,2] [mode=VERIFY] [variants=...] [warmup=2]}
 * <p>
 * Writes {@code rounds.csv} (one row per variant, depth and round), {@code calls.csv} (one row per matcher call) and
 * {@code summary.csv} (one row per variant and depth).
 */
public final class RewritingTraceReport {
    static final List<String> DEFAULT = List.of("workset", "workset-seeded", "guards-first", "prefilter",
            "workset-guards-seeded-first", "workset-prefilter-seeded", "workset-guards-seeded-first-prefilter", "oracle");

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "trace");
        int[] depths = Arrays.stream((args.length > 1 ? args[1] : "0,1,2").split(",")).mapToInt(Integer::parseInt).toArray();
        CoverageMode mode = args.length > 2 ? CoverageMode.valueOf(args[2]) : CoverageMode.VERIFY;
        List<String> variants = args.length > 3 ? List.of(args[3].split(",")) : DEFAULT;
        int warmup = args.length > 4 ? Integer.parseInt(args[4]) : 2;
        Files.createDirectories(out);

        try (PrintWriter rounds = new PrintWriter(Files.newBufferedWriter(out.resolve("rounds.csv")));
             PrintWriter calls = new PrintWriter(Files.newBufferedWriter(out.resolve("calls.csv")));
             PrintWriter summary = new PrintWriter(Files.newBufferedWriter(out.resolve("summary.csv")))) {
            rounds.println("variant,depth,popp_nodes,round,active,prefiltered,guarded,guard_calls,guard_pass,rule_calls,"
                    + "hits,misses,stale,start_ms,duration_ms,build_ms,agent_nodes,uncovered,partial,covered");
            calls.println("variant,depth,popp_nodes,seq,round,kind,name,matched,upgraded,start_ms,duration_ms");
            summary.println("variant,depth,popp_nodes,session_ms,rounds,guard_calls,rule_calls,hits,misses,stale");
            System.out.printf("%-34s %5s %6s %10s %6s %7s %7s %5s %7s %6s%n", "variant", "depth", "nodes", "session ms",
                    "rounds", "guards", "rules", "hits", "misses", "stale");

            for (int depth : depths) {
                for (String variant : variants) {
                    if (!variant.equals("domain") && !ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS) {
                        System.out.println("skipped " + variant + " (needs the fixed jLibBig AgentMatcher)");
                        continue;
                    }
                    Synced popp = new Synced();
                    CoverageTestModels.fullModel(popp.model, depth, true);
                    int nodes = popp.model.getAllNodes().size();
                    CoverageAnalyzer analyzer = CoverageTestModels.analyzer(variant, popp);
                    if (analyzer instanceof OracleRewritingCoverageAnalyzer o) o.prepare(mode);
                    for (int i = 0; i < warmup; i++) analyzer.session(mode);
                    setTracing(analyzer, true);
                    long t = System.nanoTime();
                    analyzer.session(mode);
                    double sessionMs = (System.nanoTime() - t) / 1e6;
                    setTracing(analyzer, false);
                    RewritingTrace trace = lastTrace(analyzer);

                    String key = variant + "," + depth + "," + nodes;
                    int seq = 0;
                    for (RewritingTrace.Call c : trace.calls()) {
                        calls.printf(Locale.ROOT, "%s,%d,%d,%s,%s,%b,%b,%.4f,%.4f%n", key, seq++, c.round(), c.kind(),
                                c.name(), c.matched(), c.upgraded(), c.start() / 1e6, c.nanos() / 1e6);
                    }
                    int totalGuards = 0, totalRules = 0, totalHits = 0, totalMisses = 0, totalStale = 0;
                    for (RewritingTrace.Round r : trace.rounds()) {
                        int guardCalls = 0, guardPass = 0, ruleCalls = 0, hits = 0, misses = 0, stale = 0;
                        for (RewritingTrace.Call c : trace.calls()) {
                            if (c.round() != r.round()) continue;
                            if (c.kind() == RewritingTrace.Kind.GUARD) {
                                guardCalls++;
                                if (c.matched()) guardPass++;
                            } else {
                                ruleCalls++;
                                if (c.upgraded()) hits++;
                                else if (c.matched()) stale++;
                                else misses++;
                            }
                        }
                        totalGuards += guardCalls; totalRules += ruleCalls; totalHits += hits;
                        totalMisses += misses; totalStale += stale;
                        rounds.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.4f,%.4f,%.4f,%d,%d,%d,%d%n", key,
                                r.round(), r.queued(), r.prefiltered(), r.guarded(), guardCalls, guardPass, ruleCalls,
                                hits, misses, stale, r.start() / 1e6, r.nanos() / 1e6, r.buildNanos() / 1e6,
                                r.agentNodes(), r.uncovered(), r.partial(), r.covered());
                    }
                    summary.printf(Locale.ROOT, "%s,%.3f,%d,%d,%d,%d,%d,%d%n", key, sessionMs, trace.rounds().size(),
                            totalGuards, totalRules, totalHits, totalMisses, totalStale);
                    System.out.printf("%-34s %5d %6d %10.1f %6d %7d %7d %5d %7d %6d%n", variant, depth, nodes, sessionMs,
                            trace.rounds().size(), totalGuards, totalRules, totalHits, totalMisses, totalStale);
                    rounds.flush(); calls.flush(); summary.flush();
                }
            }
        }
        System.out.println("wrote " + out.resolve("rounds.csv") + ", calls.csv, summary.csv");
    }

    static void setTracing(CoverageAnalyzer a, boolean on) {
        if (a instanceof ScheduledRewritingCoverageAnalyzer s) s.setTracing(on);
        else if (a instanceof OracleRewritingCoverageAnalyzer o) o.setTracing(on);
        else throw new IllegalArgumentException("Not a traceable whole-agent analyzer: " + a.getClass().getSimpleName());
    }

    static RewritingTrace lastTrace(CoverageAnalyzer a) {
        if (a instanceof ScheduledRewritingCoverageAnalyzer s) return s.lastTrace();
        return ((OracleRewritingCoverageAnalyzer) a).lastTrace();
    }
}
