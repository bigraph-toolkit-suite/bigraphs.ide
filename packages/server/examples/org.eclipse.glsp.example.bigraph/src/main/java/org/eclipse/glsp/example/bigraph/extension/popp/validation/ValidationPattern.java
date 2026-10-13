package org.eclipse.glsp.example.bigraph.extension.popp.validation;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A structural defect, stated as a bigraph pattern: wherever the redex of {@link #rule} occurs in the POPP bigraph,
 * the defect is present. The reactum is the redex again, the rule is only ever matched, never applied.
 *
 * @param name      unique name, used in logs and tests
 * @param code      what an occurrence means
 * @param rule      redex (= reactum) to match
 * @param required  control name to minimum count: if the bigraph has fewer, the pattern cannot occur and is skipped
 * @param subjects  names of redex nodes whose matched agent nodes are reported (and mapped to POPP node ids)
 * @param message   builds the message from the matched agent nodes' descriptions, in the order of {@code subjects}
 * @param aggregate merge all occurrences into one issue (e.g. every root of one kind), instead of one per occurrence
 * @param minOccurrences the defect needs at least this many occurrences (2 for "more than one root of a kind")
 */
public record ValidationPattern(String name, ValidationIssue.Code code, ParametricReactionRule<PureBigraph> rule,
                                Map<String, Integer> required, List<String> subjects,
                                Function<List<String>, String> message, boolean aggregate, int minOccurrences) {
}
