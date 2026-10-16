package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every case of {@link RewritingCoverageAnalyzerTest}, run against {@link FragmentRewritingCoverageAnalyzer}, plus a
 * differential check of each lever combination against {@link DomainCoverageAnalyzer} on random models (n-ary,
 * single-child decompositions, empty chain nodes from files, every relation type, both modes).
 */
class FragmentRewritingCoverageAnalyzerTest extends RewritingCoverageAnalyzerTest {

    @Override
    protected CoverageAnalyzer createAnalyzer(CoverageInput input) {
        return new FragmentRewritingCoverageAnalyzer(input);
    }

    @Test
    void depthFourIsCheapEnoughToAlwaysRun() {
        agreesWithTheDomainAnalyzerOnTheLargestTrees(true);
        agreesWithTheDomainAnalyzerOnTheLargestTrees(false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fragment-rounds", "fragment-pruned", "fragment-worklist", "fragment-slim", "fragment",
            "fragment-memo"})
    void agreesWithTheDomainAnalyzerOnRandomModels(String variant) {
        StringBuilder report = new StringBuilder();
        assertEquals(0, CoverageRandomModels.mismatches(variant, 150, 30, 1000, report), report.toString());
    }
}
