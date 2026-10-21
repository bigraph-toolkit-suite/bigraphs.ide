package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Tree;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The whole-agent rules scheduled by workset and guards agree with the domain analyzer and save matcher calls. */
class ScheduledRewritingCoverageAnalyzerTest {

    @Test
    void theStaticAnalysisFindsFewGuardsAndSparseDependencies() {
        String summary = ScheduledRewritingCoverageAnalyzer.analysisSummary();
        assertTrue(summary.contains(" 36 guards"), summary); // per kind and state, chain controls included
        assertTrue(summary.contains("enables 5.8 rules"), summary);
    }

    @Test
    void everyScheduleAgreesWithTheDomainAnalyzerOnRandomModels() {
        StringBuilder report = new StringBuilder();
        for (String variant : new String[]{"workset", "workset-seeded", "guards-first", "prefilter",
                "workset-guards-seeded-first", "workset-prefilter-seeded", "workset-guards-seeded-first-prefilter"}) {
            assertEquals(0, CoverageRandomModels.mismatches(variant, 12, 8, 4400, report), variant + "\n" + report);
        }
    }

    @Test
    void rewritingAgreesWithTheDomainAnalyzerOnRandomModels() {
        assumeTrue(ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        StringBuilder report = new StringBuilder();
        for (String variant : new String[]{"workset-prefilter-seeded"}) {
            assertEquals(0, CoverageRandomModels.mismatches(variant, 12, 8, 4400, report), variant + "\n" + report);
        }
    }

    @Test
    void theFixpointBigraphKeepsEveryRelationEdgeAndRecordsTheGiverOnIt() {
        assumeTrue(ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        Synced popp = new Synced();
        CoverageTestModels.fullModel(popp.model, 2, true);
        CoverageInput input = CoverageInput.of(popp.model, popp.bigraph);
        var rewriting = new ScheduledRewritingCoverageAnalyzer(input);
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
    void eachLeverSavesMatcherCallsAndThePrefilterCallsOnlyForHits() {
        assumeTrue(ScheduledRewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        Synced popp = new Synced();
        Tree tree = CoverageTestModels.balancedTree(popp.model, 2, true);
        CoverageInput input = CoverageInput.of(popp.model, popp.bigraph);
        var rounds = new ScheduledRewritingCoverageAnalyzer(input,
                new ScheduledRewritingCoverageAnalyzer.Options(false, false, ScheduledRewritingCoverageAnalyzer.Options.ALL_RULES));
        var guarded = new ScheduledRewritingCoverageAnalyzer(input,
                ScheduledRewritingCoverageAnalyzer.Options.WORKSET_GUARDS_SEEDED_FIRST);
        var prefiltered = new ScheduledRewritingCoverageAnalyzer(input);

        for (var analyzer : List.of(rounds, guarded, prefiltered)) {
            assertEquals(Coverage.COVERED, analyzer.coverage(tree.root(), CoverageMode.VERIFY));
        }
        assertTrue(guarded.lastStats().matcherCalls() * 5 < rounds.lastStats().matcherCalls(),
                guarded.lastStats() + " vs " + rounds.lastStats());
        // the prefilter is exact for the coverage rules: every matcher call finds a match
        assertEquals(prefiltered.lastStats().successfulCalls(), prefiltered.lastStats().matcherCalls(),
                prefiltered.lastStats().toString());
        assertTrue(prefiltered.lastStats().matcherCalls() < guarded.lastStats().matcherCalls());
    }
}
