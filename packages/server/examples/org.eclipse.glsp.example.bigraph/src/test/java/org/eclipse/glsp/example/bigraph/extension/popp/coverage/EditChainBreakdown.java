package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Where the time of an edit-inspect step goes, per analyzer (from scratch and incremental, every schedule) and edit
 * action, and the hits-only bound of each: the step's time if every matcher call that finds nothing were skipped
 * (perfect pruning). For a from-scratch schedule that bound is what {@link OracleRewritingCoverageAnalyzer} measures; for
 * the incremental analyzer there is no replayable oracle (recording and replaying would both fall into the step), so it
 * is computed from a traced step instead:
 * <pre>  bound = build + sync + (matcher time of the calls that hit) + rewrite + readout</pre>
 * Every step starts from the benchmark model, as in {@link EditChainBenchmark}: the script undoes the previous edit and
 * the incremental analyzer resyncs (untimed, untraced), then step {@code k} makes the edit of seed {@code 4711 + k}, and
 * only the inspection after it is timed. So every analyzer sees exactly the same states. Every analyzer runs twice on
 * identical models: first untraced ({@code step_ms}, the mean of {@code steps} steps after {@code warmup} steps), then
 * traced for the breakdown of the same steps (tracing adds a readout per round, which is not counted in any phase, so
 * {@code traced_ms} is higher). Not a JMH benchmark; one JVM, sequential.
 * <p>
 * {@code EditChainBreakdown <out.csv> <analyzers> [depths=1,2,3] [actions=LINK,TYPE,MOVE,NODE,MIXED] [steps=5] [warmup=3]}
 */
public final class EditChainBreakdown {
    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        List<String> analyzers = List.of(args[1].split(","));
        int[] depths = Arrays.stream((args.length > 2 ? args[2] : "1,2,3").split(",")).mapToInt(Integer::parseInt).toArray();
        List<String> actions = List.of((args.length > 3 ? args[3] : "LINK,TYPE,MOVE,NODE,MIXED").split(","));
        int steps = args.length > 4 ? Integer.parseInt(args[4]) : 5;
        int warmup = args.length > 5 ? Integer.parseInt(args[5]) : 3;
        CoverageMode mode = CoverageMode.VERIFY;
        if (out.getParent() != null) Files.createDirectories(out.getParent());

        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out))) {
            csv.println("analyzer,depth,popp_nodes,action,steps,step_ms,traced_ms,build_ms,sync_ms,prefilter_ms,"
                    + "hit_match_ms,miss_match_ms,guard_ms,rewrite_ms,readout_ms,rule_calls,hits,guard_calls,bound_ms");
            System.out.printf("%-44s %5s %6s %-6s %9s %9s %8s %8s %9s %9s %9s %8s %7s %6s%n", "analyzer", "depth", "nodes",
                    "action", "step ms", "bound ms", "sync", "filter", "hit match", "miss mtch", "rewrite", "guards",
                    "calls", "hits");
            for (int depth : depths) {
                for (String action : actions) {
                    for (String name : analyzers) {
                        Row r = run(name, depth, EditScript.Action.valueOf(action), steps, warmup, mode);
                        csv.printf(Locale.ROOT, "%s,%d,%d,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,%.2f,%.2f,%.3f%n",
                                name, depth, r.nodes, action, steps, r.stepMs, r.tracedMs, r.buildMs, r.syncMs, r.prefilterMs,
                                r.hitMatchMs, r.missMatchMs, r.guardMs, r.rewriteMs, r.readoutMs, r.ruleCalls, r.hits,
                                r.guardCalls, r.boundMs());
                        csv.flush();
                        System.out.printf(Locale.ROOT, "%-44s %5d %6d %-6s %9.1f %9.1f %8.1f %8.1f %9.1f %9.1f %9.1f %8.1f %7.1f %6.1f%n",
                                name, depth, r.nodes, action, r.stepMs, r.boundMs(), r.syncMs, r.prefilterMs, r.hitMatchMs,
                                r.missMatchMs, r.rewriteMs, r.guardMs, r.ruleCalls, r.hits);
                    }
                }
            }
        }
    }

    /** Means per step. */
    private static final class Row {
        int nodes;
        double stepMs, tracedMs, buildMs, syncMs, prefilterMs, hitMatchMs, missMatchMs, guardMs, rewriteMs, readoutMs;
        double ruleCalls, hits, guardCalls;

        double boundMs() { return buildMs + syncMs + hitMatchMs + rewriteMs + readoutMs; }
    }

    private static final long SEED = 4711;

    private static Row run(String name, int depth, EditScript.Action action, int steps, int warmup, CoverageMode mode) {
        Row r = new Row();
        for (boolean traced : new boolean[]{false, true}) {
            Synced popp = new Synced();
            CoverageTestModels.FullModel trees = CoverageTestModels.fullModel(popp.model, depth, true);
            TreeNode<?> root = trees.root(NodeKind.PROBLEM);
            CoverageAnalyzer analyzer = CoverageTestModels.analyzer(name, popp);
            EditScript script = new EditScript(popp.model, 4711);
            r.nodes = popp.model.getAllNodes().size();
            analyzer.session(mode).coverage(root);
            boolean stateful = analyzer instanceof IncrementalCoverageAnalyzer;
            for (int k = -warmup; k < steps; k++) {
                script.undo();
                if (stateful) analyzer.session(mode).coverage(root); // resync to the benchmark model
                script.reseed(SEED + warmup + k);
                script.step(action);
                boolean measured = k >= 0;
                setTracing(analyzer, traced && measured);
                long t = System.nanoTime();
                analyzer.session(mode).coverage(root);
                double ms = (System.nanoTime() - t) / 1e6 / steps;
                setTracing(analyzer, false);
                if (!measured) continue;
                if (!traced) {
                    r.stepMs += ms;
                    continue;
                }
                r.tracedMs += ms;
                add(r, analyzer, steps);
            }
            setTracing(analyzer, false);
        }
        return r;
    }

    private static void add(Row r, CoverageAnalyzer analyzer, int steps) {
        RewritingStats stats;
        RewritingTrace trace;
        if (analyzer instanceof IncrementalCoverageAnalyzer inc) {
            stats = inc.lastEngineStats();
            trace = inc.lastTrace();
            r.syncMs += inc.lastStats().syncNanos() / 1e6 / steps;
        } else {
            var scratch = (ScheduledRewritingCoverageAnalyzer) analyzer;
            stats = scratch.lastStats();
            trace = scratch.lastTrace();
        }
        r.buildMs += stats.buildNanos() / 1e6 / steps;
        r.prefilterMs += stats.prefilterNanos() / 1e6 / steps;
        r.rewriteMs += stats.rewriteNanos() / 1e6 / steps;
        r.readoutMs += stats.readoutNanos() / 1e6 / steps;
        for (RewritingTrace.Call c : trace.calls()) {
            double ms = c.nanos() / 1e6 / steps;
            if (c.kind() == RewritingTrace.Kind.GUARD) {
                r.guardMs += ms;
                r.guardCalls += 1.0 / steps;
            } else if (c.matched()) {
                r.hitMatchMs += ms;
                r.hits += 1.0 / steps;
                r.ruleCalls += 1.0 / steps;
            } else {
                r.missMatchMs += ms;
                r.ruleCalls += 1.0 / steps;
            }
        }
    }

    private static void setTracing(CoverageAnalyzer analyzer, boolean on) {
        if (analyzer instanceof IncrementalCoverageAnalyzer inc) inc.setTracing(on);
        else if (analyzer instanceof ScheduledRewritingCoverageAnalyzer s) s.setTracing(on);
        else throw new IllegalArgumentException("no breakdown for " + analyzer.getClass().getSimpleName());
    }
}
