package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.BenchmarkConfigs.Built;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.BenchmarkConfigs.CallRecorder;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.BenchmarkConfigs.Config;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Traces every matcher call of one session per {@link BenchmarkConfigs configuration}: the session from scratch and
 * the session after each of {@code edits} MIXED edits (with the seeds of the earlier trace reports, two untimed edits
 * first, so the same edits as there), on one benchmark model in one mode, all incremental. Each configuration runs the
 * whole round once untraced first (warm-up). Not a benchmark: tracing adds a little overhead per call, and every
 * session is traced once.
 * <p>
 * {@code CoverageTraceRound <out-dir> [depth=2] [mode=VERIFY] [configs=ladder] [edits=6]}
 * <p>
 * The sessions of the {@code default} configuration also give their hits-only bound
 * ({@link BenchmarkConfigs#hitsOnly}), as configuration {@code hits-only}: only the hitting calls, back to back.
 * <p>
 * Writes {@code calls.csv} (one row per matcher call, start relative to the session) and {@code sessions.csv} (one row
 * per session, with the operations the sync replayed and the states it reset, the reach of the edit).
 */
public final class CoverageTraceRound {

    /** A traced session of the default configuration, for its hits-only bound. */
    record HitsOnly(String phase, int step, String edit, int nodes, IncrementalRewritingCoverageAnalyzer.SyncStats sync,
                    CallRecorder run) {}

    static final List<HitsOnly> hitsOnly = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "trace");
        int depth = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        CoverageMode mode = CoverageMode.valueOf(args.length > 2 ? args[2] : "VERIFY");
        List<Config> configs = BenchmarkConfigs.named(args.length > 3 ? args[3] : "ladder");
        int edits = args.length > 4 ? Integer.parseInt(args[4]) : 6;
        Files.createDirectories(out);

        try (PrintWriter calls = new PrintWriter(Files.newBufferedWriter(out.resolve("calls.csv")));
             PrintWriter sessions = new PrintWriter(Files.newBufferedWriter(out.resolve("sessions.csv")))) {
            calls.println("config,phase,step,edit,seq,kind,name,matched,start_ms,duration_ms");
            sessions.println("config,depth,popp_nodes,mode,phase,step,edit,session_ms,rule_calls,hits,misses,guard_calls,"
                    + "guard_holds,rewrites,matcher_ms,operations,resets");
            System.out.printf("%-14s %-12s %4s %-34s %10s %6s %6s %6s %8s%n", "config", "phase", "step", "edit",
                    "session ms", "rules", "hits", "guards", "match ms");
            for (Config definition : configs) {
                Built config = new Built(definition);
                round(config, depth, mode, edits, null, null); // warm-up
                config.recorder.trace = true;
                round(config, depth, mode, edits, calls, sessions);
            }
            if (configs.contains(BenchmarkConfigs.DEFAULT)) {
                for (HitsOnly bound : hitsOnly) {
                    CallRecorder trace = new CallRecorder();
                    long nanos = BenchmarkConfigs.hitsOnly(bound.run(), trace, 0);
                    long start = 0;
                    write(calls, sessions, "hits-only", bound.phase(), bound.step(), bound.edit(), depth, bound.nodes(),
                            mode, start, nanos, trace, bound.run().hitRules.size(), bound.run().hitRules.size(), 0, 0,
                            bound.sync());
                }
            }
        }
    }

    /**
     * The session from scratch and one after each edit. Written to the CSV files unless they are {@code null}.
     */
    static void round(Built config, int depth, CoverageMode mode, int edits, PrintWriter calls, PrintWriter sessions) {
        Synced popp = BenchmarkSupport.model(depth);
        int nodes = popp.model.getAllNodes().size();
        IncrementalRewritingCoverageAnalyzer analyzer = (IncrementalRewritingCoverageAnalyzer) config.incremental(popp);
        traced(config, analyzer, mode, "scratch", 0, "-", depth, nodes, calls, sessions);
        EditScript script = new EditScript(popp.model, BenchmarkSupport.EDIT_SEED);
        for (int edit = -EDIT_WARMUP; edit < edits; edit++) {
            script.undo();
            boolean tracing = config.recorder.trace;
            config.recorder.trace = false;
            analyzer.session(mode); // sync to the benchmark model, untraced
            script.reseed(BenchmarkSupport.EDIT_SEED + EDIT_WARMUP + edit);
            String what = script.step(EditScript.Action.MIXED);
            if (edit < 0) {
                analyzer.session(mode);
            } else {
                config.recorder.trace = tracing;
                traced(config, analyzer, mode, "incremental", edit + 1, what, depth, nodes, calls, sessions);
            }
            config.recorder.trace = tracing;
        }
    }

    /** Untraced edits before the traced ones, as in the earlier trace reports (so the traced edits are the same). */
    static final int EDIT_WARMUP = 2;

    static void traced(Built config, IncrementalRewritingCoverageAnalyzer analyzer, CoverageMode mode, String phase,
                       int step, String edit, int depth, int nodes, PrintWriter calls, PrintWriter sessions) {
        CallRecorder recorder = config.recorder;
        recorder.reset();
        long start = System.nanoTime();
        analyzer.session(mode);
        long nanos = System.nanoTime() - start;
        if (calls == null) return;
        IncrementalRewritingCoverageAnalyzer.SyncStats sync = analyzer.lastSync(mode);
        if (config.config.equals(BenchmarkConfigs.DEFAULT)) {
            hitsOnly.add(new HitsOnly(phase, step, edit, nodes, sync, recorder.snapshot()));
        }
        write(calls, sessions, config.config.name(), phase, step, edit, depth, nodes, mode, start, nanos, recorder,
                recorder.ruleCalls, recorder.hits, recorder.guardCalls, recorder.guardHolds, sync);
    }

    static void write(PrintWriter calls, PrintWriter sessions, String config, String phase, int step, String edit,
                      int depth, int nodes, CoverageMode mode, long start, long nanos, CallRecorder recorder,
                      int ruleCalls, int hits, int guardCalls, int guardHolds,
                      IncrementalRewritingCoverageAnalyzer.SyncStats sync) {
        int seq = 0;
        long matcherNanos = 0;
        for (CallRecorder.Call call : recorder.calls) {
            calls.printf(Locale.ROOT, "%s,%s,%d,\"%s\",%d,%s,\"%s\",%b,%.3f,%.3f%n", config, phase, step, edit, seq++,
                    call.kind(), call.name(), call.matched(), (call.start() - start) / 1e6, call.nanos() / 1e6);
            matcherNanos += call.nanos();
        }
        sessions.printf(Locale.ROOT, "%s,%d,%d,%s,%s,%d,\"%s\",%.2f,%d,%d,%d,%d,%d,%d,%.2f,%d,%d%n", config, depth, nodes,
                mode, phase, step, edit, nanos / 1e6, ruleCalls, hits, ruleCalls - hits, guardCalls, guardHolds, hits,
                matcherNanos / 1e6, sync.operations(), sync.resets());
        System.out.printf(Locale.ROOT, "%-14s %-12s %4d %-34.34s %10.1f %6d %6d %6d %8.1f%n", config, phase, step, edit,
                nanos / 1e6, ruleCalls, hits, guardCalls, matcherNanos / 1e6);
    }
}
