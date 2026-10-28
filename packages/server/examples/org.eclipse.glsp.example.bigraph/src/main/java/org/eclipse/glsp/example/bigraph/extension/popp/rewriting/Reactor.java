package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatch;
import org.bigraphs.framework.simulation.matching.pure.PureReactiveSystem;

/** Applies a rule at a match: the agent after the rewrite. */
@FunctionalInterface
public interface Reactor {
    PureBigraph apply(PureBigraph agent, PureBigraphMatch match, ParametricReactionRule<PureBigraph> rule);

    /** The framework's parametric reaction (jLibBig underneath). */
    static Reactor parametric() {
        return (agent, match, rule) -> new PureReactiveSystem().buildParametricReaction(agent, match, rule);
    }
}
