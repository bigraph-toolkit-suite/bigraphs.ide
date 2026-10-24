package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.Test;

import java.util.*;

/** {@link EditScript#undo()} restores the starting model, and the incremental analyzer follows it back. */
class EditScriptTest {

    /** The model as text: nodes with parent and decomposition type, and the relations (ids of created nodes vary). */
    private static String shape(POPPModel model) {
        List<String> lines = new ArrayList<>();
        for (TreeNode<?> n : model.getAllNodes()) {
            lines.add(n.getDescription() + " < " + n.getParent().map(p -> ((TreeNode<?>) p).getDescription()).orElse("-")
                    + (n.getChildren().isEmpty() ? "" : " " + n.getDecompositionType()));
        }
        for (Relation r : model.getRelations().all()) {
            lines.add(r.source().getDescription() + " -> " + r.target().getDescription());
        }
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    @Test
    void undoRestoresTheModel() {
        for (EditScript.Action action : EditScript.Action.values()) {
            Synced popp = new Synced();
            CoverageTestModels.fullModel(popp.model, 2, true);
            String start = shape(popp.model);
            EditScript script = new EditScript(popp.model, 4711);
            for (long seed = 1; seed <= 5; seed++) {
                script.reseed(seed);
                for (int i = 0; i < 15; i++) script.step(action);
                script.undo();
                assertEquals(start, shape(popp.model), action + " seed " + seed);
            }
        }
    }

    @Test
    void sameSeedSameEdits() {
        List<List<String>> runs = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            Synced popp = new Synced();
            CoverageTestModels.fullModel(popp.model, 2, true);
            EditScript script = new EditScript(popp.model, 4711);
            List<String> done = new ArrayList<>();
            for (long seed = 1; seed <= 3; seed++) {
                script.reseed(seed);
                for (int i = 0; i < 10; i++) done.add(script.step(EditScript.Action.MIXED));
                script.undo();
            }
            runs.add(done);
        }
        assertEquals(runs.get(0), runs.get(1));
    }

    @Test
    void incrementalAgreesAfterUndo() {
        assumeTrue(RewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        Synced popp = new Synced();
        CoverageTestModels.fullModel(popp.model, 2, true);
        CoverageAnalyzer analyzer = CoverageTestModels.analyzer("incremental", popp);
        EditScript script = new EditScript(popp.model, 4711);
        CoverageMode mode = CoverageMode.valueOf("VERIFY");
        analyzer.session(mode).coverage(popp.model.getAllNodes().iterator().next());
        for (long seed = 1; seed <= 4; seed++) {
            script.reseed(seed);
            for (int i = 0; i < 8; i++) {
                script.step(EditScript.Action.MIXED);
                analyzer.session(mode).coverage(popp.model.getAllNodes().iterator().next());
            }
            script.undo();
            CoverageSession expected = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph)).session(mode);
            CoverageSession actual = analyzer.session(mode);
            for (TreeNode<?> node : popp.model.getAllNodes()) {
                assertEquals(expected.coverage(node), actual.coverage(node), "seed " + seed + " " + node.getDescription());
            }
        }
    }
}
