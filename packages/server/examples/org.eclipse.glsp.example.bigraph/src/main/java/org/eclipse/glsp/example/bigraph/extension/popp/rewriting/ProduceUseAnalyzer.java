package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import java.util.Arrays;
import java.util.List;

/**
 * Decides which rule can give which rule a new match: {@code result[r][s]} must be {@code true} whenever a rewrite by
 * {@code r} can create a match of {@code s} that did not exist before. {@code true} everywhere is always sound.
 * See {@link OverlapProduceUseAnalyzer} for one that works on any rule set.
 */
@FunctionalInterface
public interface ProduceUseAnalyzer {
    boolean[][] analyzeRuleSet(List<Rule> rules);

    /** Every rule enables every rule: no pruning, always sound. */
    static ProduceUseAnalyzer everything() {
        return rules -> {
            boolean[][] out = new boolean[rules.size()][rules.size()];
            for (boolean[] row : out) Arrays.fill(row, true);
            return out;
        };
    }

    /** A precomputed matrix (e.g. a domain-specific analysis), indexed like the rule list. */
    static ProduceUseAnalyzer of(boolean[][] enables) {
        return rules -> {
            if (enables.length != rules.size()) throw new IllegalArgumentException("Matrix does not fit the rule set");
            return enables;
        };
    }
}
