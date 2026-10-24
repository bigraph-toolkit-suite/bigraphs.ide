package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Tree;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The whole-agent rules scheduled by worklist and guards agree with the domain analyzer and save matcher calls. */
class RewritingCoverageAnalyzerTest {

    @Test
    void theStaticAnalysisFindsFewGuardsAndSparseDependencies() {
        String summary = RewritingCoverageAnalyzer.analysisSummary();
        assertTrue(summary.contains(" 36 guards"), summary); // per kind and state, gate controls included
        assertTrue(summary.contains("enables 5.8 rules"), summary);
    }

    @Test
    void everyScheduleAgreesWithTheDomainAnalyzerOnRandomModels() {
        StringBuilder report = new StringBuilder();
        for (String variant : new String[]{"worklist", "worklist-seeded", "guards-first", "worklist-guards-seeded-first"}) {
            assertEquals(0, CoverageRandomModels.mismatches(variant, 12, 8, 4400, report), variant + "\n" + report);
        }
    }

    @Test
    void rewritingAgreesWithTheDomainAnalyzerOnRandomModels() {
        assumeTrue(RewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        StringBuilder report = new StringBuilder();
        for (String variant : new String[]{"worklist-guards-seeded-first"}) {
            assertEquals(0, CoverageRandomModels.mismatches(variant, 12, 8, 4400, report), variant + "\n" + report);
        }
    }

    @Test
    void theFixpointBigraphKeepsEveryRelationEdgeAndRecordsTheGiverOnIt() {
        assumeTrue(RewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        Synced popp = new Synced();
        CoverageTestModels.fullModel(popp.model, 2, true);
        CoverageInput input = CoverageInput.of(popp.model, popp.bigraph);
        var rewriting = new RewritingCoverageAnalyzer(input);
        CoverageSession session = rewriting.session(CoverageMode.VERIFY);
        CoverageSession expected = new DomainCoverageAnalyzer(input).session(CoverageMode.VERIFY);
        for (var node : popp.model.getAllNodes()) {
            assertEquals(expected.coverage(node), session.coverage(node), node.getDescription());
            assertNotNull(session.explain(node)); // link reasons resolve their giver over the relation edge
        }
        var fixpoint = rewriting.lastFixpoint();
        // one edge per relation, none split: every relation edge still joins exactly two stubs (plus reason markers)
        assertEquals(popp.bigraph.getBigraph().getEdges().size(), fixpoint.getEdges().size());
    }

    @Test
    void eachLeverSavesMatcherCalls() {
        assumeTrue(RewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        Synced popp = new Synced();
        Tree tree = CoverageTestModels.balancedTree(popp.model, 2, true);
        CoverageInput input = CoverageInput.of(popp.model, popp.bigraph);
        var rounds = new RewritingCoverageAnalyzer(input,
                new RewritingCoverageAnalyzer.Options(false, false, RewritingCoverageAnalyzer.Options.ALL_RULES));
        var seeded = new RewritingCoverageAnalyzer(input,
                RewritingCoverageAnalyzer.Options.WORKLIST_SEEDED);
        var guarded = new RewritingCoverageAnalyzer(input);

        for (var analyzer : List.of(rounds, seeded, guarded)) {
            assertEquals(Coverage.COVERED, analyzer.coverage(tree.root(), CoverageMode.VERIFY));
        }
        assertTrue(seeded.lastStats().matcherCalls() < rounds.lastStats().matcherCalls(),
                seeded.lastStats() + " vs " + rounds.lastStats());
        assertTrue(guarded.lastStats().matcherCalls() * 5 < rounds.lastStats().matcherCalls(),
                guarded.lastStats() + " vs " + rounds.lastStats());
    }
}
