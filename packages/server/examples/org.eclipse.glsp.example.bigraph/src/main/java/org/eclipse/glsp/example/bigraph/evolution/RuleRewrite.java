/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eclipse.glsp.example.bigraph.evolution;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.exceptions.InvalidReactionRuleException;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.reactivesystem.BigraphMatch;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.core.reactivesystem.TrackingMap;
import org.bigraphs.framework.simulation.matching.AbstractBigraphMatcher;
import org.bigraphs.framework.simulation.matching.MatchIterable;
import org.bigraphs.framework.simulation.matching.pure.PureReactiveSystem;

/**
 * Applies a workspace rewrite rule. Experiment: every rule gets an identity
 * tracking map so same-named redex/reactum parts keep the matched agent names.
 */
public final class RuleRewrite {

    private static final Logger LOGGER = LogManager.getLogger(RuleRewrite.class);

    private RuleRewrite() {}

    public static AppliedRewrite apply(final PureBigraph agent, final PureBigraph redex,
                                       final PureBigraph reactum, final String ruleLabel) {
        try {
            ParametricReactionRule<PureBigraph> rule = ruleLabel != null && !ruleLabel.isEmpty()
                ? new ParametricReactionRule<>(redex, reactum).withLabel(ruleLabel)
                : new ParametricReactionRule<>(redex, reactum);
            rule.withTrackingMap(identityTrackingMap(redex, reactum));

            PureReactiveSystem rs = new PureReactiveSystem();
            rs.setAgent(agent);
            rs.addReactionRule(rule);

            MatchIterable<?> matches = AbstractBigraphMatcher.create(PureBigraph.class).match(agent, rule);
            Iterator<?> iterator = matches.iterator();
            if (!iterator.hasNext()) {
                return null;
            }
            @SuppressWarnings("unchecked")
            BigraphMatch<PureBigraph> match = (BigraphMatch<PureBigraph>) iterator.next();
            PureBigraph result = rs.buildParametricReaction(agent, match, rule);
            return result == null ? null : new AppliedRewrite(result, match);
        } catch (InvalidReactionRuleException e) {
            LOGGER.error("Invalid reaction rule: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            LOGGER.error("Error applying rewrite rule: {}", e.getMessage(), e);
            return null;
        }
    }

    static TrackingMap identityTrackingMap(final PureBigraph redex, final PureBigraph reactum) {
        TrackingMap map = new TrackingMap();
        Set<String> redexNodes = new HashSet<>();
        for (BigraphEntity.NodeEntity<?> node : redex.getNodes()) {
            redexNodes.add(node.getName());
        }
        for (BigraphEntity.NodeEntity<?> node : reactum.getNodes()) {
            String name = node.getName();
            map.put(name, redexNodes.contains(name) ? name : "");
        }
        Set<String> redexLinks = new HashSet<>();
        for (BigraphEntity.Link link : redex.getAllLinks()) {
            if (link.getName() != null && !link.getName().isEmpty()) {
                redexLinks.add(link.getName());
            }
        }
        for (BigraphEntity.Link link : reactum.getAllLinks()) {
            String name = link.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            map.put(name, redexLinks.contains(name) ? name : "");
            map.addLinkNames(name);
        }
        return map;
    }
}
