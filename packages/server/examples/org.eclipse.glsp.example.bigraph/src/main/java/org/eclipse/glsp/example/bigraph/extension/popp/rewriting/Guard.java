package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;

import java.util.Objects;

/**
 * A pattern that embeds into the redex of every rule it guards, so a guarded rule can only match if the pattern does.
 * It is answered by the matcher, like a rule. Rules share a guard by sharing the object, a {@link GuardEvaluator}
 * matches a shared guard once until a rewrite may have changed its answer.
 */
public final class Guard {
    private final String label;
    private final ParametricReactionRule<PureBigraph> pattern;

    private Guard(String label, ParametricReactionRule<PureBigraph> pattern) {
        this.label = Objects.requireNonNull(label);
        this.pattern = Objects.requireNonNull(pattern);
    }

    /** A guard that holds if and only if {@code pattern} occurs in the agent. */
    public static Guard of(String label, PureBigraph pattern) {
        try {
            return new Guard(label, new ParametricReactionRule<>(pattern, pattern));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a valid guard pattern: " + label, e);
        }
    }

    public String label() { return label; }

    /** The pattern, as an identity rule (what the matcher takes). */
    public ParametricReactionRule<PureBigraph> pattern() { return pattern; }

    @Override
    public String toString() { return "Guard[" + label + "]"; }
}
