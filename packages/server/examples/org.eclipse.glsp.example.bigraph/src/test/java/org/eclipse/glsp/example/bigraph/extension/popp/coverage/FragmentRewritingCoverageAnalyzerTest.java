package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every case of {@link RewritingCoverageAnalyzerTest}, run against {@link FragmentRewritingCoverageAnalyzer}, plus a
 * differential check of each lever combination against {@link DomainCoverageAnalyzer} on random models (n-ary,
 * single-child and childless decompositions, every relation type, both modes).
 */
class FragmentRewritingCoverageAnalyzerTest extends RewritingCoverageAnalyzerTest {

    @Override
    protected CoverageAnalyzer createAnalyzer(Supplier<POPPBigraph> bigraph, RelationGraph relations) {
        return new FragmentRewritingCoverageAnalyzer(bigraph, relations);
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
