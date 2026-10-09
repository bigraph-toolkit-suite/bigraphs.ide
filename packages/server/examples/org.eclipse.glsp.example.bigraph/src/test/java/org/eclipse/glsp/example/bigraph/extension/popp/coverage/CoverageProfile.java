package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Tree;

import java.util.Arrays;

/**
 * Quick profiling during development (not a substitute for {@link CoverageBenchmark}): median wall time of
 * {@code inspectRoot} and {@code editThenInspect} per analyzer and depth, plus the counters of the last session.
 * <p>
 * Usage: {@code CoverageProfile <analyzer,...> <depth,...> [samples=15]}
 */
public final class CoverageProfile {
    public static void main(String[] args) throws Exception {
        String[] analyzers = args[0].split(",");
        int[] depths = Arrays.stream(args[1].split(",")).mapToInt(Integer::parseInt).toArray();
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 15;
        for (String name : analyzers) {
            for (int depth : depths) {
                Synced popp = new Synced();
                Tree tree = CoverageTestModels.balancedTree(popp.model, depth, true);
                CoverageAnalyzer analyzer = CoverageTestModels.analyzer(name, popp);
                int n = popp.model.getAllNodes().size();

                double inspect = median(samples, () -> analyzer.session(CoverageMode.VERIFY).coverage(tree.root()));
                Object stats = stats(analyzer);
                double edit = median(samples, () -> {
                    popp.model.unrelate(tree.leaves().getFirst(), tree.proofs().getFirst());
                    analyzer.session(CoverageMode.VERIFY).coverage(tree.root());
                    popp.model.relate(tree.leaves().getFirst(), tree.proofs().getFirst());
                });
                // stats of a session on the edited model, for the edit-overhead question
                popp.model.unrelate(tree.leaves().getFirst(), tree.proofs().getFirst());
                analyzer.session(CoverageMode.VERIFY);
                Object editStats = stats(analyzer);
                popp.model.relate(tree.leaves().getFirst(), tree.proofs().getFirst());

                System.out.printf("[profile] %-16s depth=%d nodes=%2d inspectRoot %9.3f ms | editThenInspect %9.3f ms%n",
                        name, depth, n, inspect, edit);
                if (stats != null) System.out.printf("[profile]     inspect: %s%n[profile]     edited:  %s%n", stats, editStats);
            }
        }
    }

    private static Object stats(CoverageAnalyzer a) {
        try {
            return a.getClass().getMethod("lastStats").invoke(a);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static double median(int samples, Runnable r) {
        long budgetEnd = System.nanoTime() + 20_000_000_000L; // at most ~20 s per cell
        for (int i = 0; i < Math.max(2, samples / 5); i++) r.run(); // warm-up
        double[] t = new double[samples];
        int k = 0;
        for (; k < samples; k++) {
            long s = System.nanoTime();
            r.run();
            t[k] = (System.nanoTime() - s) / 1e6;
            if (System.nanoTime() > budgetEnd && k >= 2) { k++; break; }
        }
        double[] done = Arrays.copyOf(t, k);
        Arrays.sort(done);
        return done[done.length / 2];
    }
}
