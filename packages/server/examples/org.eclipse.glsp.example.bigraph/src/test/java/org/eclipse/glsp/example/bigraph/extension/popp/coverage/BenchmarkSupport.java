package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What every benchmark shares: the benchmark models, the edit seeds, the inspection that is timed, and the check
 * against the domain analyzer.
 * <p>
 * The models are {@link CoverageTestModels#fullModel}, complete: all six trees balanced, 6 / 18 / 42 / 90 POPP nodes
 * for depth 0 to 3. An inspection is one session and the coverage of every node (what the editor shows after a
 * change). The edits are those of {@link EditScript}, every edit from the benchmark model, edit {@code k} with the seed
 * {@code EDIT_SEED + k}.
 */
public final class BenchmarkSupport {
    private BenchmarkSupport() {}

    public static final long EDIT_SEED = 4711;

    public static Synced model(int depth) {
        Synced popp = new Synced();
        CoverageTestModels.fullModel(popp.model, depth, true);
        return popp;
    }

    /** The nodes of a model in a stable order (by description), so that every run reads them in the same order. */
    public static List<TreeNode<?>> nodes(Synced popp) {
        List<TreeNode<?>> out = new ArrayList<>(popp.model.getAllNodes());
        out.sort(Comparator.comparing(TreeNode::getDescription));
        return out;
    }

    /** One inspection: a session and the coverage of every node. Returns a value that depends on all of them. */
    public static int inspect(CoverageAnalyzer analyzer, CoverageMode mode, List<TreeNode<?>> nodes) {
        CoverageSession session = analyzer.session(mode);
        int sum = 0;
        for (TreeNode<?> node : nodes) sum = 31 * sum + session.coverage(node).ordinal();
        return sum;
    }

    /** Fails if {@code got} differs from the domain analyzer on any node of {@code popp}. */
    public static void check(String config, Synced popp, CoverageMode mode, CoverageSession got, String when) {
        CoverageSession want = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph)).session(mode);
        for (TreeNode<?> node : popp.model.getAllNodes()) {
            if (want.coverage(node) != got.coverage(node)) {
                throw new AssertionError(config + " " + mode + " " + when + ": " + node.getDescription() + " is "
                        + got.coverage(node) + ", expected " + want.coverage(node));
            }
        }
    }
}
