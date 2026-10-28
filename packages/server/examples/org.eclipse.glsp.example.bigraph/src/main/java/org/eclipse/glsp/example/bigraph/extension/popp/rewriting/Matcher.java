package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatch;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatcher;

import java.util.Iterator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** How the agent is searched for a match of a rule or of a pattern guard. */
@FunctionalInterface
public interface Matcher {
    Optional<PureBigraphMatch> first(PureBigraph agent, ParametricReactionRule<PureBigraph> rule);

    /**
     * The number of occurrences of {@code pattern} in the agent: distinct sets of agent nodes it embeds onto (all
     * matches enumerated, symmetric patterns and different splits of the parameters give one occurrence).
     */
    default int occurrences(PureBigraph agent, ParametricReactionRule<PureBigraph> pattern) {
        Set<Set<String>> images = new HashSet<>();
        for (PureBigraphMatch match : new PureBigraphMatcher().matchAll(agent, pattern)) {
            Set<String> image = new HashSet<>();
            match.getJLibMatchResult().emb_nodes.forEach((patternNode, agentNode) -> image.add(agentNode.getEditable().getName()));
            images.add(image);
        }
        return images.size();
    }

    /** {@code matchFirst}: stops at the first match. Needs a framework version in which it works. */
    static Matcher matchFirst() {
        return (agent, rule) -> {
            Iterator<PureBigraphMatch> it = new PureBigraphMatcher().matchFirst(agent, rule).iterator();
            return it.hasNext() ? Optional.of(it.next()) : Optional.empty();
        };
    }

    /** {@code matchAll}, taking the first match: slower, works with every framework version. */
    static Matcher matchAll() {
        return (agent, rule) -> {
            Iterator<PureBigraphMatch> it = new PureBigraphMatcher().matchAll(agent, rule).iterator();
            return it.hasNext() ? Optional.of(it.next()) : Optional.empty();
        };
    }
}
