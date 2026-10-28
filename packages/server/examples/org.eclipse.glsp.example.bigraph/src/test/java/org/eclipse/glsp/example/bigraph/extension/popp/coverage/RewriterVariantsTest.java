package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Every rewriter variant the benchmarks compare computes the coverage of the domain analyzer, from scratch in every
 * mode and incrementally after edits, and each optimization calls the matcher for rules at most as often as the
 * variant before it.
 */
class RewriterVariantsTest {

    @Test
    void everyVariantAgreesWithTheDomainAnalyzer() {
        assumeTrue(RewritingCoverageAnalyzer.REWRITE_KEEPS_LINKS, "needs the fixed jLibBig AgentMatcher");
        int previousRuleCalls = Integer.MAX_VALUE;
        for (RewriterVariants.Variant definition : RewriterVariants.ALL) {
            RewriterVariants.Built variant = new RewriterVariants.Built(definition);
            List<CoverageBenchmark.Sample> scratch = CoverageBenchmark.scratch(variant, CoverageBenchmark.model(1), true);
            CoverageBenchmark.incremental(variant, 1, 4, CoverageMode.VERIFY, true);
            int ruleCalls = scratch.stream().mapToInt(CoverageBenchmark.Sample::ruleCalls).sum();
            assertTrue(ruleCalls > 0, definition.name());
            assertTrue(ruleCalls <= previousRuleCalls, definition.name() + " calls the matcher for rules more often");
            previousRuleCalls = ruleCalls;
        }
    }
}
