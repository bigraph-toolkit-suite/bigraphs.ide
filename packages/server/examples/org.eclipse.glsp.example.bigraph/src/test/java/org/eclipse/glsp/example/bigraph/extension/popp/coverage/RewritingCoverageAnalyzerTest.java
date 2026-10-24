package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.junit.jupiter.api.Test;


/** Rewriting with worklist, seed and guards agrees with the domain analyzer and keeps the relation edges intact. */
class RewritingCoverageAnalyzerTest {

    @Test
    void rewritingAgreesWithTheDomainAnalyzerOnRandomModels() {
        assumeTrue(RewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        StringBuilder report = new StringBuilder();
        assertEquals(0, CoverageRandomModels.mismatches("default", 12, 8, 4400, report), report.toString());
    }

    @Test
    void theFixpointBigraphKeepsEveryRelationEdgeAndRecordsTheGiverOnIt() {
        assumeTrue(RewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        Synced popp = new Synced();
        CoverageTestModels.fullModel(popp.model, 2, true);
        CoverageInput input = CoverageInput.of(popp.model, popp.bigraph);
        var fixpoint = new RewritingCoverageAnalyzer(input).rewriteToFixpoint(CoverageMode.VERIFY,
                CoverageBigraph.build(popp.bigraph, CoverageMode.VERIFY), RewritingCoverageAnalyzer.LINK_RULES);
        CoverageSession session = fixpoint.session();
        CoverageSession expected = new DomainCoverageAnalyzer(input).session(CoverageMode.VERIFY);
        for (var node : popp.model.getAllNodes()) {
            assertEquals(expected.coverage(node), session.coverage(node), node.getDescription());
            assertNotNull(session.explain(node)); // link reasons resolve their giver over the relation edge
        }
        // one edge per relation, none split: every relation edge still joins exactly two stubs (plus reason markers)
        assertEquals(popp.bigraph.getBigraph().getEdges().size(), fixpoint.bigraph().getEdges().size());
    }
}
