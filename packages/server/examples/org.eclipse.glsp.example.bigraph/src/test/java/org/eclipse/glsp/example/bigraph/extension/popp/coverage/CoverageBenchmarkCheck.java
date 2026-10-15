package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

/**
 * Run before benchmarking a checkout: reports how many nodes the {@link RewritingCoverageAnalyzer} of that checkout
 * classifies differently from {@link DomainCoverageAnalyzer}. A benchmark of an analyzer that computes something else
 * is meaningless, and older encodings may well do so (e.g. through the link corruption in
 * {@code PureReactiveSystem.buildParametricReaction}). Deliberately a separate program rather than part of the JMH
 * setup: it would warm the JVM up and spoil the cold-start measurement.
 * <p>
 * Usage: {@code CoverageBenchmarkCheck [maxDepth=3]} with {@code -Danalyzer=<any name of CoverageTestModels#analyzer>};
 * {@link CoverageRandomModels} adds random models with every relation type and wider decompositions;
 * never throws on a mismatch, it only reports.
 */
public final class CoverageBenchmarkCheck {
    public static void main(String[] args) {
        int maxDepth = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        String variant = System.getProperty("variant", "unlabelled");
        String analyzer = System.getProperty("analyzer", "rewriting");
        System.out.printf("[check] rules: %d%n",
                org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageRewriteRules.compile().size());
        for (int depth = 1; depth <= maxDepth; depth++) {
            for (boolean proveAll : new boolean[]{true, false}) {
                for (CoverageMode mode : CoverageMode.values()) {
                    Synced popp = new Synced();
                    CoverageTestModels.balancedTree(popp.model, depth, proveAll);
                    CoverageSession expected = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph)).session(mode);
                    long start = System.nanoTime();
                    String outcome;
                    int mismatches = 0;
                    try {
                        CoverageSession actual = CoverageTestModels.analyzer(analyzer, popp).session(mode);
                        for (TreeNode<?> node : popp.model.getAllNodes()) {
                            if (expected.coverage(node) != actual.coverage(node)) mismatches++;
                        }
                        outcome = mismatches + " of " + popp.model.getAllNodes().size() + " nodes differ";
                    } catch (Throwable t) {
                        outcome = "FAILED: " + t;
                    }
                    System.out.printf("[check] variant=%s analyzer=%s depth=%d proveAll=%b mode=%s: %s (%d ms)%n",
                            variant, analyzer, depth, proveAll, mode, outcome, (System.nanoTime() - start) / 1_000_000);
                }
            }
        }
    }
}