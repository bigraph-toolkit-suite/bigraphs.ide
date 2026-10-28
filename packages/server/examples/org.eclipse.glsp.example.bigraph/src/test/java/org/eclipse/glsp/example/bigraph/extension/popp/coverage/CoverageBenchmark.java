package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.RewriterVariants.Built;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.RewriterVariants.CallRecorder;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The roundup: every {@link RewriterVariants rewriter variant} on the benchmark models
 * ({@link CoverageTestModels#fullModel}, complete, 6 / 18 / 42 / 90 POPP nodes for depth 0 to 3), from scratch in
 * every mode and incrementally after the same edits. Each variant is checked against the domain analyzer on every
 * model first (a variant that computes something else is not benchmarked).
 * <p>
 * {@code CoverageBenchmark <out-dir> [depths=0,1,2,3] [variants=all] [reps=5] [edits=8] [editMode=VERIFY]}
 * <p>
 * Every measurement is repeated {@code reps} times after a warm-up (all variants on the depth 1 model), and the median
 * is written. From scratch: one session per mode, timed one at a time. Incremental: {@code edits} MIXED edits of
 * {@link EditScript}, each from the benchmark model (the previous edit is undone and the analyzer synced untimed), the
 * session in {@code editMode} after the edit timed. Edit {@code k} has the seed {@code 4711 + 2 + k}, after two
 * untimed edits (seeds 4711 and 4712), as in the earlier edit-chain benchmarks, so the edits are the same as there. Depth 0 has no decompositions, so it has no
 * incremental row.
 * <p>
 * For the {@code counted} variant (the analyzer's default), every session also gives its hits-only bound
 * ({@link RewriterVariants#hitsOnly}): the time of its hitting rule calls and their rewrites, the time a perfect
 * pruning would need. These rows have the variant {@code hits-only}.
 * <p>
 * Writes {@code roundup.csv} (one row per variant, depth, phase, and mode or edit) and {@code variants.csv} (the time
 * to build each variant, i.e. its static analysis, and its rule and guard counts).
 */
public final class CoverageBenchmark {

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "benchmark-results");
        int[] depths = Arrays.stream((args.length > 1 ? args[1] : "0,1,2,3").split(",")).mapToInt(Integer::parseInt).toArray();
        List<RewriterVariants.Variant> variants = RewriterVariants.named(args.length > 2 ? args[2] : "all");
        int reps = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        int edits = args.length > 4 ? Integer.parseInt(args[4]) : 8;
        CoverageMode editMode = CoverageMode.valueOf(args.length > 5 ? args[5] : "VERIFY");
        Files.createDirectories(out);
        if (!RewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS) throw new IllegalStateException("needs the fixed jLibBig AgentMatcher");
        System.out.println("framework fixes: firstMatch=" + RewritingCoverageAnalyzer.FIRST_MATCH_WORKS + " rewrite=true");

        List<Built> built = new ArrayList<>();
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("variants.csv")))) {
            csv.println("variant,description,build_ms,rules,guards,enabled_per_rule");
            for (RewriterVariants.Variant variant : variants) {
                long start = System.nanoTime();
                Built variantBuilt = new Built(variant);
                double buildMs = (System.nanoTime() - start) / 1e6;
                built.add(variantBuilt);
                double enabled = 0;
                for (int i = 0; i < variantBuilt.rewriter.rules().size(); i++) enabled += variantBuilt.rewriter.enabledBy(i).length;
                csv.printf(Locale.ROOT, "%s,\"%s\",%.1f,%d,%d,%.2f%n", variant.name(), variant.description(), buildMs,
                        variantBuilt.rewriter.rules().size(), variantBuilt.rewriter.guards().size(),
                        enabled / variantBuilt.rewriter.rules().size());
            }
        }

        System.out.println("warm-up (depth 1, every variant)");
        for (Built variant : built) {
            scratch(variant, model(1), false);
            incremental(variant, 1, Math.min(edits, 3), editMode, false);
        }

        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("roundup.csv")))) {
            csv.println("variant,depth,popp_nodes,phase,mode,step,edit,median_ms,min_ms,max_ms,rule_calls,hits,"
                    + "guard_calls,guard_holds,rewrites,matcher_ms");
            System.out.printf("%-12s %5s %6s %16s %18s %10s %10s%n", "variant", "depth", "nodes", "scratch ms (sum)",
                    "incremental ms", "rule calls", "guard calls");
            for (int depth : depths) {
                int nodes = model(depth).model.getAllNodes().size();
                for (Built variant : built) {
                    // check first: a variant that computes something else is not benchmarked
                    scratch(variant, model(depth), true);
                    if (depth > 0) incremental(variant, depth, edits, editMode, true);

                    List<List<Sample>> scratchRuns = new ArrayList<>(), incrementalRuns = new ArrayList<>();
                    for (int rep = 0; rep < reps; rep++) {
                        scratchRuns.add(scratch(variant, model(depth), false));
                        if (depth > 0) incrementalRuns.add(incremental(variant, depth, edits, editMode, false));
                    }
                    String name = variant.variant.name();
                    print(name, depth, nodes, write(csv, name, depth, nodes, "scratch", scratchRuns),
                            depth > 0 ? write(csv, name, depth, nodes, "incremental", incrementalRuns) : Double.NaN,
                            scratchRuns.get(reps - 1));
                    if (name.equals("counted")) {
                        List<List<Sample>> scratchHits = hitsOnly(scratchRuns);
                        List<List<Sample>> incrementalHits = depth > 0 ? hitsOnly(incrementalRuns) : null;
                        print("hits-only", depth, nodes, write(csv, "hits-only", depth, nodes, "scratch", scratchHits),
                                depth > 0 ? write(csv, "hits-only", depth, nodes, "incremental", incrementalHits) : Double.NaN,
                                scratchHits.get(reps - 1));
                    }
                    csv.flush();
                }
            }
        }
    }

    /** One timed session, what the rewriter did in it, and its hits (for the hits-only bound). */
    record Sample(String mode, int step, String edit, long nanos, int ruleCalls, int hits, int guardCalls,
                  int guardHolds, int rewrites, long matcherNanos, CallRecorder run) {}

    static void print(String variant, int depth, int nodes, double scratchMs, double incrementalMs, List<Sample> scratch) {
        System.out.printf(Locale.ROOT, "%-12s %5d %6d %16.0f %18.0f %10d %10d%n", variant, depth, nodes, scratchMs,
                incrementalMs, scratch.stream().mapToInt(Sample::ruleCalls).sum(),
                scratch.stream().mapToInt(Sample::guardCalls).sum());
    }

    /** The hits-only bound of every session of {@code runs}, in the same positions. */
    static List<List<Sample>> hitsOnly(List<List<Sample>> runs) {
        List<List<Sample>> out = new ArrayList<>();
        for (List<Sample> samples : runs) {
            List<Sample> run = new ArrayList<>();
            for (Sample sample : samples) {
                long nanos = RewriterVariants.hitsOnly(sample.run(), null, 0);
                int hits = sample.run().hitRules.size();
                run.add(new Sample(sample.mode(), sample.step(), sample.edit(), nanos, hits, hits, 0, 0, hits,
                        sample.run().hitNanos.stream().mapToLong(Long::longValue).sum(), sample.run()));
            }
            out.add(run);
        }
        return out;
    }

    /** The seed of edit {@code k}, after {@link #EDIT_WARMUP} untimed edits (as in the earlier edit-chain benchmarks). */
    static final long EDIT_SEED = 4711;
    static final int EDIT_WARMUP = 2;

    static Synced model(int depth) {
        Synced popp = new Synced();
        CoverageTestModels.fullModel(popp.model, depth, true);
        return popp;
    }

    /** One session per mode, from scratch. With {@code check}, compares every session with the domain analyzer. */
    static List<Sample> scratch(Built variant, Synced popp, boolean check) {
        CoverageAnalyzer analyzer = variant.fromScratch(popp);
        List<Sample> out = new ArrayList<>();
        for (CoverageMode mode : CoverageMode.presets()) {
            variant.recorder.reset();
            long start = System.nanoTime();
            CoverageSession session = analyzer.session(mode);
            long nanos = System.nanoTime() - start;
            out.add(sample(mode.name(), 0, "-", nanos, variant.recorder));
            if (check) check(variant, popp, mode, session, "from scratch");
        }
        return out;
    }

    /**
     * The incremental analyzer: one session from scratch (untimed), then per edit: undo the previous one and sync
     * (untimed), make the edit, time the session. The same edits for every variant.
     */
    static List<Sample> incremental(Built variant, int depth, int edits, CoverageMode mode, boolean check) {
        Synced popp = model(depth);
        IncrementalRewritingCoverageAnalyzer analyzer = variant.incremental(popp);
        analyzer.session(mode);
        EditScript script = new EditScript(popp.model, EDIT_SEED);
        List<Sample> out = new ArrayList<>();
        for (int edit = -EDIT_WARMUP; edit < edits; edit++) {
            script.undo();
            analyzer.session(mode);
            script.reseed(EDIT_SEED + EDIT_WARMUP + edit);
            String what = script.step(EditScript.Action.MIXED);
            variant.recorder.reset();
            long start = System.nanoTime();
            CoverageSession session = analyzer.session(mode);
            long nanos = System.nanoTime() - start;
            if (edit < 0) continue;
            out.add(sample(mode.name(), edit + 1, what, nanos, variant.recorder));
            if (check) check(variant, popp, mode, session, "after " + what);
        }
        return out;
    }

    static Sample sample(String mode, int step, String edit, long nanos, CallRecorder recorder) {
        return new Sample(mode, step, edit, nanos, recorder.ruleCalls, recorder.hits, recorder.guardCalls,
                recorder.guardHolds, recorder.rewrites, recorder.matcherNanos, recorder.snapshot());
    }

    static void check(Built variant, Synced popp, CoverageMode mode, CoverageSession got, String when) {
        CoverageSession want = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph)).session(mode);
        for (TreeNode<?> node : popp.model.getAllNodes()) {
            if (want.coverage(node) != got.coverage(node)) {
                throw new AssertionError(variant.variant.name() + " " + mode + " " + when + ": " + node.getDescription()
                        + " is " + got.coverage(node) + ", expected " + want.coverage(node));
            }
        }
    }

    /** One row per position (mode or edit) with the median over the runs, returns the sum of the medians in ms. */
    static double write(PrintWriter csv, String variant, int depth, int nodes, String phase, List<List<Sample>> runs) {
        double sum = 0;
        for (int position = 0; position < runs.get(0).size(); position++) {
            List<Long> times = new ArrayList<>();
            for (List<Sample> run : runs) times.add(run.get(position).nanos());
            Collections.sort(times);
            double median = times.get(times.size() / 2) / 1e6;
            sum += median;
            Sample last = runs.get(runs.size() - 1).get(position);
            csv.printf(Locale.ROOT, "%s,%d,%d,%s,%s,%d,\"%s\",%.2f,%.2f,%.2f,%d,%d,%d,%d,%d,%.2f%n", variant,
                    depth, nodes, phase, last.mode(), last.step(), last.edit(), median, times.get(0) / 1e6,
                    times.get(times.size() - 1) / 1e6, last.ruleCalls(), last.hits(), last.guardCalls(),
                    last.guardHolds(), last.rewrites(), last.matcherNanos() / 1e6);
        }
        return sum;
    }
}
