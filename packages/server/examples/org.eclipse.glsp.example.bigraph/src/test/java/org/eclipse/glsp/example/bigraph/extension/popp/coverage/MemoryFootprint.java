package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The heap the coverage analysis keeps on the GLSP server, measured as the used heap after full garbage collections,
 * step by step in one JVM: the JVM with the framework loaded (by a first, discarded model), the POPP model with its synced bigraph, the rule set and its static analysis
 * (loaded once per JVM, shared by all models), the incremental analyzer after a session in every mode (one coverage
 * bigraph per mode, kept for the next edit), and the same after {@code edits} edits (whether the kept state grows).
 * The domain analyzer keeps nothing between sessions, which the last step checks.
 * <p>
 * {@code MemoryFootprint <out.csv> <depth> [edits=50]}: appends one row per step. Run every depth in a JVM of its
 * own (the rule set is loaded once per JVM), see {@code run-benchmarks.sh}. Allocation per inspection (garbage, not
 * kept) comes from the JMH benchmarks with {@code -prof gc}.
 */
public final class MemoryFootprint {

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        int depth = Integer.parseInt(args[1]);
        int edits = args.length > 2 ? Integer.parseInt(args[2]) : 50;
        List<String> rows = new ArrayList<>();

        // a first model loads and initializes the framework (EMF, factories, signatures), which is not the model's
        Reference.reachabilityFence(BenchmarkSupport.model(0));
        long empty = usedAfterGc();
        Synced popp = BenchmarkSupport.model(depth);
        List<TreeNode<?>> nodes = BenchmarkSupport.nodes(popp);
        long model = usedAfterGc();
        rows.add(row(depth, nodes.size(), "model and bigraph", model - empty));

        if (!RewritingCoverageAnalyzer.REWRITING_SUPPORTED) throw new IllegalStateException("needs the patched framework");
        long rules = usedAfterGc(); // class initialization: the rule set, its guards and the shared rewriter
        rows.add(row(depth, nodes.size(), "rule set and rewriter (once per JVM)", rules - model));

        CoverageAnalyzer incremental = CoverageTestModels.analyzer("incremental", popp);
        for (CoverageMode mode : CoverageMode.presets()) BenchmarkSupport.inspect(incremental, mode, nodes);
        long kept = usedAfterGc();
        rows.add(row(depth, nodes.size(), "incremental analyzer, all modes", kept - rules));

        EditScript script = new EditScript(popp.model, BenchmarkSupport.EDIT_SEED);
        for (int edit = 0; edit < edits; edit++) {
            script.undo();
            script.reseed(BenchmarkSupport.EDIT_SEED + edit);
            script.step(EditScript.Action.MIXED);
            for (CoverageMode mode : CoverageMode.presets()) BenchmarkSupport.inspect(incremental, mode, nodes);
        }
        script.undo();
        for (CoverageMode mode : CoverageMode.presets()) BenchmarkSupport.inspect(incremental, mode, nodes);
        long afterEdits = usedAfterGc();
        rows.add(row(depth, nodes.size(), "incremental analyzer, all modes, after " + edits + " edits", afterEdits - rules));

        CoverageAnalyzer domain = CoverageTestModels.analyzer("domain", popp);
        for (CoverageMode mode : CoverageMode.presets()) BenchmarkSupport.inspect(domain, mode, nodes);
        long withDomain = usedAfterGc();
        rows.add(row(depth, nodes.size(), "domain analyzer, all modes", withDomain - afterEdits));

        // everything stays reachable until the last measurement
        Reference.reachabilityFence(popp);
        Reference.reachabilityFence(incremental);
        Reference.reachabilityFence(domain);

        boolean header = !Files.exists(out);
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            if (header) csv.println("depth,popp_nodes,component,retained_kib");
            rows.forEach(csv::println);
        }
        rows.forEach(System.out::println);
    }

    private static String row(int depth, int nodes, String component, long bytes) {
        return String.format(Locale.ROOT, "%d,%d,\"%s\",%.1f", depth, nodes, component, bytes / 1024.0);
    }

    /** The used heap after full collections, once it no longer shrinks (at most ten rounds). */
    static long usedAfterGc() {
        var memory = ManagementFactory.getMemoryMXBean();
        long previous = Long.MAX_VALUE;
        for (int round = 0; round < 10; round++) {
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long used = memory.getHeapMemoryUsage().getUsed();
            if (used >= previous) return Math.min(used, previous);
            previous = used;
        }
        return previous;
    }
}
