package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

/**
 * Run before benchmarking: on the benchmark models ({@link CoverageTestModels#fullModel}, complete and partial), in
 * every coverage mode, reports how many nodes each analyzer classifies differently from
 * {@link DomainCoverageAnalyzer}. A benchmark of an analyzer that computes something else is meaningless. A separate
 * program rather than part of the JMH setup, so it does not warm up the JVM of the cold-start measurement.
 * <p>
 * Usage: {@code CoverageBenchmarkCheck <maxDepth> <analyzer,...>}; never throws on a mismatch, it only reports, and
 * exits with status 1 if anything differed. {@code CoverageBenchmarkCheck --capabilities} only prints which framework
 * fixes are on the classpath ({@code firstMatch=... rewrite=...}), for the benchmark script; {@code --rules} which rule
 * set and enabling analysis are in effect ({@code -Dpopp.gates}, {@code -Dpopp.enabling}).
 * {@code CoverageBenchmarkCheck --edits <analyzer,...>} checks incremental analyzers instead: random edit sequences
 * ({@link EditScript}) on the benchmark models, compared with the domain analyzer after every edit.
 */
public final class CoverageBenchmarkCheck {
    public static void main(String[] args) {
        if (args.length > 1 && args[0].equals("--edits")) {
            int bad = 0;
            for (String analyzer : args[1].split(",")) {
                StringBuilder report = new StringBuilder();
                int mismatches = 0;
                long start = System.nanoTime();
                for (long seed = 1; seed <= 6; seed++) {
                    mismatches += IncrementalCoverageAnalyzerTest.mismatches(analyzer, 1 + (int) (seed % 2), 40, seed, report);
                }
                System.out.printf("[check] analyzer=%-24s 6 edit sequences of 40 steps, all modes: %d mismatches (%d ms)%n%s",
                        analyzer, mismatches, (System.nanoTime() - start) / 1_000_000, report);
                if (mismatches > 0) bad++;
            }
            System.out.println(bad == 0 ? "[check] OK" : "[check] " + bad + " analyzer(s) DIFFER");
            if (bad > 0) System.exit(1);
            return;
        }
        if (args.length > 0 && args[0].equals("--rules")) {
            System.out.println("gates=" + (CoverageRewriteRules.THRESHOLD_GATES ? "threshold" : "enumerated")
                    + " enabling=" + (ScheduledRewritingCoverageAnalyzer.ENABLING_BY_OVERLAP ? "overlap" : "controls")
                    + ": " + ScheduledRewritingCoverageAnalyzer.analysisSummary());
            return;
        }
        if (args.length > 0 && args[0].equals("--capabilities")) {
            System.out.println("firstMatch=" + ScheduledRewritingCoverageAnalyzer.FIRST_MATCH_WORKS
                    + " rewrite=" + ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS);
            return;
        }
        int maxDepth = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        String[] analyzers = (args.length > 1 ? args[1] : "worklist-prefilter-seeded").split(",");
        String variant = System.getProperty("variant", "unlabelled");
        int bad = 0;
        System.out.println("[check] matchFirst works: " + ScheduledRewritingCoverageAnalyzer.FIRST_MATCH_WORKS);
        System.out.println("[check] rewriting keeps links (fixed jLibBig AgentMatcher): " + ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS);
        for (String analyzer : analyzers) {
            for (int depth = 0; depth <= maxDepth; depth++) {
                for (boolean complete : new boolean[]{true, false}) {
                    int mismatches = 0, nodes = 0;
                    long start = System.nanoTime();
                    String outcome;
                    try {
                        for (CoverageMode mode : CoverageMode.presets()) {
                            Synced popp = new Synced();
                            CoverageTestModels.fullModel(popp.model, depth, complete);
                            CoverageSession expected = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph)).session(mode);
                            CoverageSession actual = CoverageTestModels.analyzer(analyzer, popp).session(mode);
                            for (TreeNode<?> node : popp.model.getAllNodes()) {
                                nodes++;
                                if (expected.coverage(node) != actual.coverage(node)) mismatches++;
                            }
                        }
                        outcome = mismatches + " of " + nodes + " node results differ";
                    } catch (Throwable t) {
                        mismatches = 1;
                        outcome = "FAILED: " + t;
                    }
                    if (mismatches > 0) bad++;
                    System.out.printf("[check] variant=%s analyzer=%-36s depth=%d complete=%-5b all modes: %s (%d ms)%n",
                            variant, analyzer, depth, complete, outcome, (System.nanoTime() - start) / 1_000_000);
                }
            }
        }
        System.out.println(bad == 0 ? "[check] OK" : "[check] " + bad + " configuration(s) DIFFER");
        if (bad > 0) System.exit(1);
    }
}
