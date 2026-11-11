package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.BenchmarkConfigs.Built;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.BenchmarkConfigs.CallRecorder;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.BenchmarkConfigs.Config;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Where the time of a session goes, per {@link BenchmarkConfigs configuration}: the matcher calls for rules (hits and
 * misses), for guards, the rewrites, and the rest (building the coverage bigraph, the worklist, the readout). The
 * latencies themselves come from the JMH benchmarks ({@link CoverageScratchBenchmark}, {@link CoverageEditBenchmark}).
 * This harness splits them up: the counts are deterministic, the times are medians over {@code reps} repetitions
 * after a warm-up.
 * <p>
 * {@code CoverageCallCounts <out-dir> [depths=0,1,2,3] [configs=ladder,ablation] [reps=5] [edits=8] [editMode=VERIFY]}
 * <p>
 * From scratch: one session per mode. Incremental: {@code edits} MIXED edits, each from the benchmark model (the
 * previous edit is undone and the analyzer synced untimed), the session in {@code editMode} timed. Depth 0 has no
 * decompositions, so it has no incremental rows. For the {@code default} configuration every session also gives its
 * hits-only bound ({@link BenchmarkConfigs#hitsOnly}), the time a perfect pruning would need, as configuration
 * {@code hits-only}.
 * <p>
 * Writes {@code calls.csv} (one row per configuration, depth, phase, and mode or edit) and {@code configs.csv} (per
 * configuration the time to build it, i.e. its static analysis, its rules, guards and enabled rules per rule).
 */
public final class CoverageCallCounts {

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "calls");
        int[] depths = Arrays.stream((args.length > 1 ? args[1] : "0,1,2,3").split(",")).mapToInt(Integer::parseInt).toArray();
        List<Config> configs = BenchmarkConfigs.named(args.length > 2 ? args[2] : "ladder,ablation");
        int reps = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        int edits = args.length > 4 ? Integer.parseInt(args[4]) : 8;
        CoverageMode editMode = CoverageMode.valueOf(args.length > 5 ? args[5] : "VERIFY");
        Files.createDirectories(out);
        if (!RewritingCoverageAnalyzer.REWRITING_SUPPORTED) throw new IllegalStateException("needs the patched framework");

        List<Built> built = new ArrayList<>();
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("configs.csv")))) {
            csv.println("config,group,description,build_ms,rules,guards,enabled_per_rule");
            for (Config config : configs) {
                long start = System.nanoTime();
                Built configBuilt = new Built(config);
                double buildMs = (System.nanoTime() - start) / 1e6;
                built.add(configBuilt);
                double[] figures = configBuilt.staticFigures();
                csv.printf(Locale.ROOT, "%s,%s,\"%s\",%.1f,%.0f,%.0f,%.2f%n", config.name(), config.group(),
                        config.description(), buildMs, figures[0], figures[1], figures[2]);
            }
        }

        System.out.println("warm-up (depth 1, every configuration)");
        for (Built config : built) {
            scratch(config, 1, false);
            incremental(config, 1, Math.min(edits, 3), editMode, false);
        }

        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("calls.csv")))) {
            csv.println("config,group,depth,popp_nodes,phase,mode,step,edit,median_ms,min_ms,max_ms,rule_calls,hits,"
                    + "guard_calls,guard_holds,rewrites,rule_ms,guard_ms,rewrite_ms");
            System.out.printf("%-18s %5s %6s %16s %18s %10s %10s%n", "config", "depth", "nodes", "scratch ms (sum)",
                    "incremental ms", "rule calls", "guard calls");
            for (int depth : depths) {
                int nodes = BenchmarkSupport.model(depth).model.getAllNodes().size();
                for (Built config : built) {
                    // checked first: a configuration that computes something else is not measured
                    scratch(config, depth, true);
                    if (depth > 0) incremental(config, depth, edits, editMode, true);

                    List<List<Sample>> scratchRuns = new ArrayList<>(), incrementalRuns = new ArrayList<>();
                    for (int rep = 0; rep < reps; rep++) {
                        scratchRuns.add(scratch(config, depth, false));
                        if (depth > 0) incrementalRuns.add(incremental(config, depth, edits, editMode, false));
                    }
                    String name = config.config.name(), group = config.config.group();
                    print(name, depth, nodes, write(csv, name, group, depth, nodes, "scratch", scratchRuns),
                            depth > 0 ? write(csv, name, group, depth, nodes, "incremental", incrementalRuns) : Double.NaN,
                            scratchRuns.get(reps - 1));
                    if (name.equals(BenchmarkConfigs.DEFAULT.name())) {
                        List<List<Sample>> scratchHits = hitsOnly(scratchRuns);
                        List<List<Sample>> incrementalHits = depth > 0 ? hitsOnly(incrementalRuns) : null;
                        print("hits-only", depth, nodes, write(csv, "hits-only", "bound", depth, nodes, "scratch", scratchHits),
                                depth > 0 ? write(csv, "hits-only", "bound", depth, nodes, "incremental", incrementalHits)
                                        : Double.NaN, scratchHits.get(reps - 1));
                    }
                    csv.flush();
                }
            }
        }
    }

    /** One timed session, what the rewriter did in it, and its hits (for the hits-only bound). */
    record Sample(String mode, int step, String edit, long nanos, int ruleCalls, int hits, int guardCalls,
                  int guardHolds, int rewrites, long ruleNanos, long guardNanos, long rewriteNanos, CallRecorder run) {}

    static void print(String config, int depth, int nodes, double scratchMs, double incrementalMs, List<Sample> scratch) {
        System.out.printf(Locale.ROOT, "%-18s %5d %6d %16.1f %18.1f %10d %10d%n", config, depth, nodes, scratchMs,
                incrementalMs, scratch.stream().mapToInt(Sample::ruleCalls).sum(),
                scratch.stream().mapToInt(Sample::guardCalls).sum());
    }

    /** The hits-only bound of every session of {@code runs}, in the same positions. */
    static List<List<Sample>> hitsOnly(List<List<Sample>> runs) {
        List<List<Sample>> out = new ArrayList<>();
        for (List<Sample> samples : runs) {
            List<Sample> run = new ArrayList<>();
            for (Sample sample : samples) {
                long nanos = BenchmarkConfigs.hitsOnly(sample.run(), null, 0);
                int hits = sample.run().hitRules.size();
                long ruleNanos = sample.run().hitNanos.stream().mapToLong(Long::longValue).sum();
                run.add(new Sample(sample.mode(), sample.step(), sample.edit(), nanos, hits, hits, 0, 0, hits,
                        ruleNanos, 0, nanos - ruleNanos, sample.run()));
            }
            out.add(run);
        }
        return out;
    }

    /** One session per mode, from scratch. With {@code check}, compares every session with the domain analyzer. */
    static List<Sample> scratch(Built config, int depth, boolean check) {
        Synced popp = BenchmarkSupport.model(depth);
        List<TreeNode<?>> nodes = BenchmarkSupport.nodes(popp);
        CoverageAnalyzer analyzer = config.fromScratch(popp);
        List<Sample> out = new ArrayList<>();
        for (CoverageMode mode : CoverageMode.presets()) {
            config.recorder.reset();
            long start = System.nanoTime();
            BenchmarkSupport.inspect(analyzer, mode, nodes);
            long nanos = System.nanoTime() - start;
            out.add(sample(mode.name(), 0, "-", nanos, config.recorder));
            if (check) BenchmarkSupport.check(config.config.name(), popp, mode, analyzer.session(mode), "from scratch");
        }
        return out;
    }

    /**
     * The incremental analyzer: one session from scratch (untimed), then per edit: undo the previous one and sync
     * (untimed), make the edit, time the session. The same edits for every configuration.
     */
    static List<Sample> incremental(Built config, int depth, int edits, CoverageMode mode, boolean check) {
        Synced popp = BenchmarkSupport.model(depth);
        List<TreeNode<?>> nodes = BenchmarkSupport.nodes(popp);
        CoverageAnalyzer analyzer = config.incremental(popp);
        analyzer.session(mode);
        EditScript script = new EditScript(popp.model, BenchmarkSupport.EDIT_SEED);
        List<Sample> out = new ArrayList<>();
        for (int edit = 0; edit < edits; edit++) {
            script.undo();
            analyzer.session(mode);
            script.reseed(BenchmarkSupport.EDIT_SEED + edit);
            String what = script.step(EditScript.Action.MIXED);
            config.recorder.reset();
            long start = System.nanoTime();
            BenchmarkSupport.inspect(analyzer, mode, nodes);
            long nanos = System.nanoTime() - start;
            out.add(sample(mode.name(), edit + 1, what, nanos, config.recorder));
            if (check) BenchmarkSupport.check(config.config.name(), popp, mode, analyzer.session(mode), "after " + what);
        }
        return out;
    }

    static Sample sample(String mode, int step, String edit, long nanos, CallRecorder recorder) {
        return new Sample(mode, step, edit, nanos, recorder.ruleCalls, recorder.hits, recorder.guardCalls,
                recorder.guardHolds, recorder.rewrites, recorder.ruleNanos, recorder.guardNanos, recorder.rewriteNanos,
                recorder.snapshot());
    }

    /** One row per position (mode or edit) with the median over the runs. Returns the sum of the medians in ms. */
    static double write(PrintWriter csv, String config, String group, int depth, int nodes, String phase,
                        List<List<Sample>> runs) {
        double sum = 0;
        for (int position = 0; position < runs.getFirst().size(); position++) {
            List<Sample> samples = new ArrayList<>();
            for (List<Sample> run : runs) samples.add(run.get(position));
            samples.sort(Comparator.comparingLong(Sample::nanos));
            Sample median = samples.get(samples.size() / 2);
            sum += median.nanos() / 1e6;
            csv.printf(Locale.ROOT, "%s,%s,%d,%d,%s,%s,%d,\"%s\",%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f%n", config,
                    group, depth, nodes, phase, median.mode(), median.step(), median.edit(), median.nanos() / 1e6,
                    samples.getFirst().nanos() / 1e6, samples.getLast().nanos() / 1e6, median.ruleCalls(),
                    median.hits(), median.guardCalls(), median.guardHolds(), median.rewrites(), median.ruleNanos() / 1e6,
                    median.guardNanos() / 1e6, median.rewriteNanos() / 1e6);
        }
        return sum;
    }
}
