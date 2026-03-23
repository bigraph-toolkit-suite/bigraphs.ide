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

package org.eclipse.glsp.example.bigraph.handler;

import java.io.File;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.simulation.matching.MatchIterable;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatcher;
import org.eclipse.glsp.example.bigraph.actions.VerifyBigraphAction;
import org.eclipse.glsp.example.bigraph.actions.VerifyBigraphResultAction;
import org.eclipse.glsp.example.bigraph.model.BigraphIO;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import com.google.inject.Inject;

/**
 * Checks whether the current bigraph (or an explicit checkpoint) matches a
 * given verification bigraph pattern and returns a {@link VerifyBigraphResultAction}.
 *
 * Uses {@link PureBigraphMatcher} directly so we only need a match iterator
 * (no rewriting), which avoids the "Parent can not be null" error that occurs
 * when trying to apply an identity rule to a bigraph without sites.
 */
public class VerifyBigraphActionHandler extends AbstractActionHandler<VerifyBigraphAction> {

    private static final Logger LOGGER = LogManager.getLogger(VerifyBigraphActionHandler.class);

    @Inject
    protected BigraphModelState modelState;

    @Override
    public List<Action> executeAction(final VerifyBigraphAction action) {
        final String verificationId   = action.getVerificationId();
        final String verificationPath = action.getVerificationPath();
        final String checkpointPath   = action.getCheckpointPath();

        if (verificationPath == null || verificationPath.isBlank()) {
            return List.of(new VerifyBigraphResultAction(verificationId, false, "No verification path provided."));
        }

        // Load the verification bigraph (acts as redex pattern)
        final PureBigraphMutable vbMutable = BigraphIO.parseBigraphFromFile(new File(verificationPath));
        if (vbMutable == null) {
            LOGGER.warn("Could not load verification bigraph: {}", verificationPath);
            return List.of(new VerifyBigraphResultAction(verificationId, false, "Could not load verification bigraph."));
        }
        final PureBigraph vb = toImmutable(vbMutable, vbMutable.getSignature());

        // Load the bigraph to check against (checkpoint or current workspace bigraph)
        PureBigraph agent = null;
        if (checkpointPath != null && !checkpointPath.isBlank()) {
            final PureBigraphMutable cpMutable = BigraphIO.parseBigraphFromFile(new File(checkpointPath));
            if (cpMutable != null) {
                agent = toImmutable(cpMutable, cpMutable.getSignature());
            } else {
                LOGGER.warn("Could not load checkpoint bigraph: {}, falling back to workspace bigraph.", checkpointPath);
            }
        }
        if (agent == null) {
            final PureBigraphMutable ws = modelState.getMutableBigraph();
            if (ws == null) {
                return List.of(new VerifyBigraphResultAction(verificationId, false, "No bigraph loaded."));
            }
            agent = toImmutable(ws, ws.getSignature());
        }

        // Use PureBigraphMatcher directly — only checks for a match, does not rewrite.
        // This avoids the "Parent can not be null" error that the identity-rule approach
        // triggers when the verification bigraph has no sites.
        final boolean matched = hasMatch(agent, vb);

        LOGGER.info("Verification check for id={}: matched={}", verificationId, matched);
        return List.of(new VerifyBigraphResultAction(
            verificationId,
            matched,
            matched ? "Verification bigraph matched." : "Verification bigraph did not match."
        ));
    }

    /**
     * Returns true if {@code redex} appears as a sub-bigraph inside {@code agent}.
     * Creates a dummy reactum equal to the redex so the rule is valid, then only
     * asks the matcher whether at least one match exists.
     */
    private boolean hasMatch(final PureBigraph agent, final PureBigraph redex) {
        try {
            final ParametricReactionRule<PureBigraph> rule = new ParametricReactionRule<>(redex, redex);
            final PureBigraphMatcher matcher = new PureBigraphMatcher();
            final MatchIterable<?> matches = matcher.match(agent, rule);
            return matches.iterator().hasNext();
        } catch (final Exception e) {
            LOGGER.warn("Match check failed: {}", e.getMessage());
            return false;
        }
    }

    private PureBigraph toImmutable(final PureBigraphMutable mutable, final DynamicSignature sig) {
        return PureBigraphBuilder
            .create(sig, mutable.getMetaModel(), mutable.getInstanceModel())
            .create();
    }
}
