package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A rule of a {@link MonotoneFixpointRewriter}: a reaction rule, tried only if all its {@code guards} hold (no guards:
 * always tried). Rules are identified by object, so the same reaction rule may occur in two rules.
 */
public record Rule(String name, ParametricReactionRule<PureBigraph> reaction, List<Guard> guards) {
    public Rule {
        Objects.requireNonNull(name);
        Objects.requireNonNull(reaction);
        guards = List.copyOf(guards);
    }

    public static Rule of(String name, ParametricReactionRule<PureBigraph> reaction) {
        return new Rule(name, reaction, List.of());
    }

    public static Rule guarded(String name, ParametricReactionRule<PureBigraph> reaction, Guard... guards) {
        if (guards.length == 0) throw new IllegalArgumentException("No guard for " + name);
        return new Rule(name, reaction, List.of(guards));
    }

    /** This rule with {@code more} guards added after its own (duplicates by object are dropped). */
    public Rule withGuards(List<Guard> more) {
        List<Guard> all = new ArrayList<>(guards);
        for (Guard guard : more) if (all.stream().noneMatch(existing -> existing == guard)) all.add(guard);
        return new Rule(name, reaction, all);
    }

    /** This rule without guards. */
    public Rule withoutGuards() {
        return new Rule(name, reaction, List.of());
    }

    // identity, not structure: a record would compare the reaction rules and guard lists
    @Override
    public boolean equals(Object other) { return this == other; }

    @Override
    public int hashCode() { return System.identityHashCode(this); }

    @Override
    public String toString() { return "Rule[" + name + "]"; }
}
