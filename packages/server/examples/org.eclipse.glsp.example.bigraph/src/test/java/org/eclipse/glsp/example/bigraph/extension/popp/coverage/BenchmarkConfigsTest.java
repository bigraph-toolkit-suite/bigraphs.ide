package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Every configuration the benchmarks compare computes the coverage of the domain analyzer, from scratch in every mode
 * and incrementally after edits, and each step of the ladder calls the matcher for rules at most as often as the step
 * before it.
 */
class BenchmarkConfigsTest {

    @Test
    void everyConfigurationAgreesWithTheDomainAnalyzer() {
        assumeTrue(RewritingCoverageAnalyzer.REWRITING_SUPPORTED, "needs the patched framework");
        for (BenchmarkConfigs.Config config : BenchmarkConfigs.ALL) {
            BenchmarkConfigs.Built built = new BenchmarkConfigs.Built(config);
            CoverageCallCounts.scratch(built, 1, true);
            CoverageCallCounts.incremental(built, 1, 3, CoverageMode.VERIFY, true);
        }
    }

    @Test
    void everyStepOfTheLadderCallsTheMatcherForRulesAtMostAsOften() {
        assumeTrue(RewritingCoverageAnalyzer.REWRITING_SUPPORTED, "needs the patched framework");
        int previous = Integer.MAX_VALUE;
        for (BenchmarkConfigs.Config config : BenchmarkConfigs.LADDER) {
            List<CoverageCallCounts.Sample> scratch = CoverageCallCounts.scratch(new BenchmarkConfigs.Built(config), 1, false);
            int ruleCalls = scratch.stream().mapToInt(CoverageCallCounts.Sample::ruleCalls).sum();
            assertTrue(ruleCalls > 0, config.name());
            assertTrue(ruleCalls <= previous, config.name() + " calls the matcher for rules more often than the step before");
            previous = ruleCalls;
        }
    }

    @Test
    void theLadderEndsWithTheDefaultAndEveryNameIsUnique() {
        assertEquals(BenchmarkConfigs.DEFAULT, BenchmarkConfigs.LADDER.getLast());
        assertEquals(BenchmarkConfigs.ALL.size(), BenchmarkConfigs.ALL.stream().map(BenchmarkConfigs.Config::name).distinct().count());
    }
}
