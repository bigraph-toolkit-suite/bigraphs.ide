package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.junit.jupiter.api.Test;

import java.util.*;

/** After every edit of random edit sequences, the incremental analyzer agrees with the domain analyzer in every mode. */
class IncrementalCoverageAnalyzerTest {

    /** Number of (step, mode) pairs with a disagreement; the first few are described in {@code report}. */
    static int mismatches(String variant, int depth, int steps, long seed, StringBuilder report) {
        Synced popp = new Synced();
        CoverageTestModels.fullModel(popp.model, depth, seed % 2 == 0);
        CoverageAnalyzer analyzer = CoverageTestModels.analyzer(variant, popp);
        EditScript script = new EditScript(popp.model, seed);
        int bad = 0;
        for (int step = 0; step <= steps; step++) {
            String what = step == 0 ? "start" : script.step(EditScript.Action.MIXED);
            for (CoverageMode mode : CoverageMode.presets()) {
                if ((step + mode.hashCode()) % 2 == 1 && step > 0 && step < steps) continue; // modes lag behind
                CoverageSession expected = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph)).session(mode);
                CoverageSession actual = analyzer.session(mode);
                List<String> diff = new ArrayList<>();
                for (TreeNode<?> node : popp.model.getAllNodes()) {
                    if (expected.coverage(node) != actual.coverage(node)) {
                        diff.add(node.getDescription() + " " + expected.coverage(node) + "/" + actual.coverage(node));
                    }
                    actual.explain(node); // must not throw
                }
                if (!diff.isEmpty() && bad++ < 3) {
                    report.append(String.format("seed %d step %d (%s) %s: expected/actual %s%n", seed, step, what, mode, diff));
                }
            }
        }
        return bad;
    }

    @Test
    void agreesAfterEveryEdit() {
        assumeTrue(ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        StringBuilder report = new StringBuilder();
        int bad = 0;
        for (long seed = 1; seed <= 6; seed++) bad += mismatches("incremental", 1 + (int) (seed % 2), 40, seed, report);
        assertEquals(0, bad, report.toString());
    }

    /**
     * The modes evaluated in parallel ({@link CoverageAnalyzer#sessions}) agree with the domain analyzer after every
     * edit. Runs in a pool of one thread per mode, so that all modes really overlap even on a machine with few cores.
     */
    @Test
    void agreesWhenTheModesRunInParallel() throws Exception {
        assumeTrue(ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        List<CoverageMode> modes = CoverageMode.presets();
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(modes.size());
        StringBuilder report = new StringBuilder();
        int bad = 0;
        try {
            for (long seed = 1; seed <= 6; seed++) {
                Synced popp = new Synced();
                CoverageTestModels.fullModel(popp.model, 1 + (int) (seed % 2), seed % 2 == 0);
                CoverageAnalyzer analyzer = CoverageTestModels.analyzer("incremental", popp);
                EditScript script = new EditScript(popp.model, seed);
                for (int step = 0; step <= 30; step++) {
                    String what = step == 0 ? "start" : script.step(EditScript.Action.MIXED);
                    Map<CoverageMode, CoverageSession> sessions = pool.submit(() -> analyzer.sessions(modes)).get();
                    for (CoverageMode mode : modes) {
                        CoverageSession expected = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph)).session(mode);
                        CoverageSession actual = sessions.get(mode);
                        List<String> diff = new ArrayList<>();
                        for (TreeNode<?> node : popp.model.getAllNodes()) {
                            if (expected.coverage(node) != actual.coverage(node)) {
                                diff.add(node.getDescription() + " " + expected.coverage(node) + "/" + actual.coverage(node));
                            }
                        }
                        if (!diff.isEmpty() && bad++ < 3) {
                            report.append(String.format("seed %d step %d (%s) %s: expected/actual %s%n", seed, step, what, mode, diff));
                        }
                    }
                }
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(0, bad, report.toString());
    }

    @Test
    void editsTheCoverageBigraphInsteadOfRebuildingIt() {
        assumeTrue(ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        Synced popp = new Synced();
        CoverageTestModels.fullModel(popp.model, 2, true);
        var analyzer = (IncrementalCoverageAnalyzer) CoverageTestModels.analyzer("incremental", popp);
        analyzer.session(CoverageMode.VERIFY);
        assertFalse(analyzer.lastStats().incremental());
        EditScript script = new EditScript(popp.model, 7);
        for (EditScript.Action action : List.of(EditScript.Action.LINK, EditScript.Action.TYPE, EditScript.Action.MOVE,
                EditScript.Action.NODE, EditScript.Action.LINK)) {
            script.step(action);
            analyzer.session(CoverageMode.VERIFY);
            var stats = analyzer.lastStats();
            assertTrue(stats.incremental() && stats.operations() > 0, action + ": " + stats);
            assertTrue(stats.seeded() < ScheduledRewritingCoverageAnalyzer.rules().size(), action + ": " + stats);
        }
    }
}
