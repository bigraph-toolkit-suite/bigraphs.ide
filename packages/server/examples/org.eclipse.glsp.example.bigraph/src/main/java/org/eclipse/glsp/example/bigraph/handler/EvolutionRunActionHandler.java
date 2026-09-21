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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.simulation.matching.MatchIterable;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatcher;
import org.eclipse.glsp.example.bigraph.actions.EvolutionFinishedAction;
import org.eclipse.glsp.example.bigraph.actions.EvolutionRunAction;
import org.eclipse.glsp.example.bigraph.actions.EvolutionRunAction.RuleRef;
import org.eclipse.glsp.example.bigraph.actions.EvolutionRunAction.VerificationRef;
import org.eclipse.glsp.example.bigraph.actions.EvolutionStartedAction;
import org.eclipse.glsp.example.bigraph.actions.PublishEvolutionStateAction;
import org.eclipse.glsp.example.bigraph.evolution.AppliedRewrite;
import org.eclipse.glsp.example.bigraph.evolution.EvolutionOperation;
import org.eclipse.glsp.example.bigraph.evolution.EvolutionRegistry;
import org.eclipse.glsp.example.bigraph.evolution.EvolutionSignatures;
import org.eclipse.glsp.example.bigraph.evolution.RuleApplicationStrategy;
import org.eclipse.glsp.example.bigraph.evolution.RuleRewrite;
import org.eclipse.glsp.example.bigraph.extensions.EvolutionRunApi;
import org.eclipse.glsp.example.bigraph.extensions.EvolutionRunHook;
import org.eclipse.glsp.example.bigraph.extensions.ExtensionList;
import org.eclipse.glsp.example.bigraph.extensions.IdeExtension;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.example.bigraph.model.BigraphIO;
import org.eclipse.glsp.example.bigraph.model.BigraphLoadException;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.ActionDispatcher;

import com.google.inject.Inject;

/**
 * Handles {@link EvolutionRunAction} dispatched from the Evolution Manager.
 *
 * <ul>
 *   <li><b>play</b>: starts the work in a background thread, registers it in
 *       {@link EvolutionRegistry}, and immediately returns an {@link EvolutionStartedAction}
 *       carrying the generated {@code operationId} back to the client.</li>
 *   <li><b>pause</b>: looks up the operation by the {@code operationId} supplied in the
 *       action and requests a pause.</li>
 * </ul>
 */
public class EvolutionRunActionHandler extends AbstractActionHandler<EvolutionRunAction> {

    private static final Logger LOGGER = LogManager.getLogger(EvolutionRunActionHandler.class);

    @Inject
    protected BigraphModelState modelState;

    @Inject
    protected ActionDispatcher actionDispatcher;

    @Override
    public List<Action> executeAction(final EvolutionRunAction action) {
        final String type = action.getActionType();
        LOGGER.info("EvolutionRunAction – actionType={}, operationId={}", type, action.getOperationId());

        if ("pause".equals(type)) {
            return handlePause(action);
        }
        return startOperation(action);
    }

    // -----------------------------------------------------------------------
    // pause
    // -----------------------------------------------------------------------

    private List<Action> handlePause(final EvolutionRunAction action) {
        final String opId = action.getOperationId();
        if (opId == null || opId.isBlank()) {
            LOGGER.warn("Pause received but no operationId supplied.");
            return List.of();
        }
        final boolean paused = EvolutionRegistry.getInstance().pause(opId);
        LOGGER.info("Pause requested for operationId={} – accepted={}", opId, paused);
        return List.of();
    }

    // -----------------------------------------------------------------------
    // play
    // -----------------------------------------------------------------------

    private List<Action> startOperation(final EvolutionRunAction action) {
        if (action.getRules() == null || action.getRules().isEmpty()) {
            LOGGER.error("No rules in action.");
            return List.of();
        }

        final EvolutionRegistry registry = EvolutionRegistry.getInstance();
        final EvolutionOperation operation = new EvolutionOperation(UUID.randomUUID().toString(), action.getActionType());
        final String operationId = registry.registerExclusive(operation);
        if (operationId == null) {
            final String activeOperationId = registry.getActiveOperationId();
            LOGGER.warn("Rejected evolution start because operation {} is still active.", activeOperationId);
            BigraphNotifications.notifyWarning(
                actionDispatcher,
                "Another evolution run is already active. Pause it or wait for it to finish before starting a new one.");
            return List.of();
        }

        final Thread worker = new Thread(() -> {
            final EvolutionOperation op = registry.get(operationId);
            try {
                runStep(action, op);
            } finally {
                final EvolutionOperation.State state = op != null ? op.getState() : null;
                final String reason = state == EvolutionOperation.State.PAUSED
                    ? "paused"
                    : state == EvolutionOperation.State.FAILED
                        ? "failed"
                        : "completed";
                actionDispatcher.dispatch(new EvolutionFinishedAction(
                    operationId, reason, op != null ? op.getExtensionResults() : null));
                registry.remove(operationId);
                LOGGER.info("Operation {} {} / removed from registry.", operationId, reason);
            }
        });
        worker.setDaemon(true);
        worker.setName("evo-" + operationId.substring(0, 8));
        worker.start();

        LOGGER.info("Started evolution operation {} in thread {}", operationId, worker.getName());
        return List.of(new EvolutionStartedAction(operationId, action.getActionType()));
    }

    // -----------------------------------------------------------------------
    // core logic (runs in background thread)
    // -----------------------------------------------------------------------

    /** Package-visible so tests can run the loop without spinning a background thread. */
    void runStep(final EvolutionRunAction action, final EvolutionOperation op) {
        try {
            if (shouldPause(op)) { return; }

            PureBigraphMutable agentMutable = modelState.getMutableBigraph();
            if (agentMutable == null) {
                if (op != null) {
                    op.markFailed();
                }
                BigraphNotifications.notifyError(actionDispatcher, "Cannot run evolution because no bigraph is loaded.");
                LOGGER.error("No bigraph loaded in model state.");
                return;
            }

            final int maxOps = resolveMaxOps(action);
            final List<PureBigraphMutable[]> ruleMutables = loadRuleMutables(action.getRules());
            final List<LoadedVerification> verificationMutables =
                loadVerificationMutables(action.getVerificationBigraphs());

            final DynamicSignature signature = mergeSignatures(agentMutable.getSignature(), ruleMutables, verificationMutables);
            LOGGER.info("Evolution signature union has {} controls.", signature.getControls().size());
            final PureBigraph initial = toImmutable(agentMutable, signature);
            final List<PureBigraph[]> parsedRules = toImmutableRules(ruleMutables, signature);
            final List<GoalBigraph> parsedVerifications = toImmutableVerifications(verificationMutables, signature);

            final RunContext ctx = new RunContext(action, signature, parsedRules, parsedVerifications, maxOps, op);
            // Read evolution.json once before the run; updated in-memory after each application.
            // Use checkpoint-cursor as the predecessor for the first new operation so that
            // branching from any historic checkpoint is handled correctly.
            ctx.evoJsonContent = readEvolutionJson(ctx.evoJsonPath);
            ctx.predecessorId = extractCursorId(ctx.evoJsonContent);
            ctx.startCheckpointId = ctx.predecessorId == null ? "" : ctx.predecessorId;
            ctx.walkedEdges.putAll(collectWalkedEdges(ctx.evoJsonContent));

            final EvolutionRunHook hook = findRunHook(action);
            final PureBigraph finalBigraph = hook != null
                ? hook.run(initial, ctx)
                : runEvolutionLoop(initial, ctx, op);

            commitFinalState(finalBigraph, ctx);

        } catch (Exception e) {
            final Throwable failure = unwrapCompletionException(e);
            if (op != null) {
                op.markFailed();
            }
            LOGGER.error("Error in evolution background thread", failure);
            BigraphNotifications.notifyError(actionDispatcher, "Evolution run failed: " + describeFailure(failure));
        }
    }

    /** Determines the maximum number of rule applications for this invocation. */
    private int resolveMaxOps(final EvolutionRunAction action) {
        return action.isMaxOperationsEnabled()
            ? Math.max(1, action.getMaxOperations())
            : Integer.MAX_VALUE;
    }

    /** Parses all redex/reactum pairs from disk once, before the loop starts. */
    private List<PureBigraphMutable[]> loadRuleMutables(final List<RuleRef> rules) {
        final List<PureBigraphMutable[]> parsed = new ArrayList<>();
        for (final RuleRef rule : rules) {
            try {
                final PureBigraphMutable rx = BigraphIO.parseBigraphFromFile(new File(rule.getRedex()));
                final PureBigraphMutable rc = BigraphIO.parseBigraphFromFile(new File(rule.getReactum()));
                parsed.add(new PureBigraphMutable[]{rx, rc});
            } catch (BigraphLoadException e) {
                LOGGER.warn("Could not load rule {}/{} – skipping. {}", rule.getRedex(), rule.getReactum(), e.getMessage());
                parsed.add(null);
            }
        }
        return parsed;
    }

    /** Parses stop-condition / goal verification bigraphs from disk once, before the loop starts. */
    private List<LoadedVerification> loadVerificationMutables(final List<VerificationRef> refs) {
        final List<LoadedVerification> result = new ArrayList<>();
        if (refs == null) { return result; }
        for (final VerificationRef ref : refs) {
            try {
                result.add(new LoadedVerification(ref.getId(), BigraphIO.parseBigraphFromFile(new File(ref.getPath()))));
            } catch (BigraphLoadException e) {
                LOGGER.warn("Could not load verification bigraph {} – skipping. {}", ref.getPath(), e.getMessage());
            }
        }
        LOGGER.info("Loaded {} stop-condition verification bigraph(s).", result.size());
        return result;
    }

    private DynamicSignature mergeSignatures(final DynamicSignature agentSignature,
                                             final List<PureBigraphMutable[]> rules,
                                             final List<LoadedVerification> verifications) {
        final List<DynamicSignature> signatures = new ArrayList<>();
        signatures.add(agentSignature);
        for (final PureBigraphMutable[] pair : rules) {
            if (pair == null) { continue; }
            signatures.add(pair[0].getSignature());
            signatures.add(pair[1].getSignature());
        }
        for (final LoadedVerification verification : verifications) {
            signatures.add(verification.mutable.getSignature());
        }
        return EvolutionSignatures.unionLeft(signatures);
    }

    private List<PureBigraph[]> toImmutableRules(final List<PureBigraphMutable[]> rules,
                                                 final DynamicSignature signature) {
        final List<PureBigraph[]> parsed = new ArrayList<>();
        for (final PureBigraphMutable[] pair : rules) {
            if (pair == null) {
                parsed.add(null);
                continue;
            }
            try {
                final PureBigraphMutable redex = toMutableSamePackage(
                    EvolutionSignatures.rebind(pair[0], signature), signature);
                final PureBigraphMutable reactum = toMutableSamePackage(
                    EvolutionSignatures.rebind(pair[1], signature), signature);
                parsed.add(new PureBigraph[]{
                    toImmutableSamePackage(redex, signature),
                    toImmutableSamePackage(reactum, signature)
                });
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to rebind reaction rule onto the merged signature", e);
            }
        }
        return parsed;
    }

    private List<GoalBigraph> toImmutableVerifications(final List<LoadedVerification> verifications,
                                                       final DynamicSignature signature) {
        final List<GoalBigraph> parsed = new ArrayList<>();
        for (final LoadedVerification verification : verifications) {
            parsed.add(new GoalBigraph(verification.id, toImmutable(verification.mutable, signature)));
        }
        return parsed;
    }

    /**
     * Carries the mutable state that is shared across rounds and between
     * the loop body and the post-loop commit. Doubles as the
     * {@link EvolutionRunApi} facade handed to extension run hooks.
     */
    private final class RunContext implements EvolutionRunApi {
        final EvolutionRunAction action;
        final DynamicSignature signature;
        final List<RuleRef> rules;
        final List<PureBigraph[]> parsedRules;
        final List<GoalBigraph> verifications;
        final int maxOps;
        final EvolutionOperation op;
        final String evoJsonPath;
        String evoJsonContent;
        String predecessorId;
        String startCheckpointId = "";
        int totalApplied = 0;
        /** {@code predecessor + '\\0' + ruleId} → existing child checkpoint id. */
        final Map<String, String> walkedEdges = new HashMap<>();

        RunContext(final EvolutionRunAction action, final DynamicSignature signature,
                   final List<PureBigraph[]> parsedRules, final List<GoalBigraph> verifications,
                   final int maxOps, final EvolutionOperation op) {
            this.action = action;
            this.signature = signature;
            this.rules = action.getRules();
            this.parsedRules = parsedRules;
            this.verifications = verifications;
            this.maxOps = maxOps;
            this.op = op;
            this.evoJsonPath = action.getEvolutionJsonPath();
        }

        @Override
        public EvolutionRunAction getAction() { return action; }

        @Override
        public int getRuleCount() { return rules.size(); }

        @Override
        public String getRuleId(final int ruleIndex) {
            return EvolutionRunActionHandler.this.ruleId(rules.get(ruleIndex));
        }

        @Override
        public String getRuleName(final int ruleIndex) {
            return EvolutionRunActionHandler.this.ruleName(rules.get(ruleIndex));
        }

        @Override
        public boolean hasWalkedEdge(final String predecessorCheckpointId, final int ruleIndex) {
            return walkedEdges.containsKey(walkedEdgeKey(predecessorCheckpointId, getRuleId(ruleIndex)));
        }

        @Override
        public PureBigraph tryApplyRule(final PureBigraph current, final int ruleIndex) {
            final AppliedRewrite applied = tryApplyRuleWithMatch(current, ruleIndex);
            return applied == null ? null : applied.result();
        }

        @Override
        public AppliedRewrite tryApplyRuleWithMatch(final PureBigraph current, final int ruleIndex) {
            return EvolutionRunActionHandler.this.tryApplyRuleWithMatch(current, ruleIndex, this);
        }

        @Override
        public String recordApplication(final PureBigraph result, final int ruleIndex,
                                        final String predecessorCheckpointId) {
            return EvolutionRunActionHandler.this.recordApplication(
                result, rules.get(ruleIndex), this, predecessorCheckpointId);
        }

        @Override
        public List<String> matchingGoalIds(final PureBigraph bigraph) {
            return EvolutionRunActionHandler.this.matchingGoalIds(bigraph, this);
        }

        @Override
        public boolean shouldPause() {
            return EvolutionRunActionHandler.this.shouldPause(op);
        }

        @Override
        public int getTotalApplied() { return totalApplied; }

        @Override
        public int getMaxOperations() { return maxOps; }

        @Override
        public String getStartCheckpointId() { return startCheckpointId; }

        @Override
        public void setPredecessorId(final String checkpointId) {
            this.predecessorId = checkpointId;
        }

        @Override
        public void publishState(final PureBigraph bigraph, final boolean persistToWorkspace) {
            EvolutionRunActionHandler.this.publishState(bigraph, this, persistToWorkspace);
        }

        @Override
        public void putExtensionResult(final String extensionId, final Object result) {
            if (op != null) {
                op.putExtensionResult(extensionId, result);
            }
        }

        @Override
        public void writeEvolutionJsonRootValue(final String key, final String rawJsonValue) {
            evoJsonContent = upsertRootValue(evoJsonContent, key, rawJsonValue, evoJsonPath);
        }
    }

    private static final class LoadedVerification {
        final String id;
        final PureBigraphMutable mutable;

        LoadedVerification(final String id, final PureBigraphMutable mutable) {
            this.id = id;
            this.mutable = mutable;
        }
    }

    private static final class GoalBigraph {
        final String id;
        final PureBigraph bigraph;

        GoalBigraph(final String id, final PureBigraph bigraph) {
            this.id = id == null ? "" : id;
            this.bigraph = bigraph;
        }
    }

    /**
     * Returns the first extension run hook claiming this action, or {@code null}
     * when no registered extension wants to replace the core evolution loop.
     */
    private EvolutionRunHook findRunHook(final EvolutionRunAction action) {
        for (final IdeExtension extension : ExtensionList.getInstance().getExtensions()) {
            final Optional<EvolutionRunHook> hook = extension.getEvolutionRunHook();
            if (hook.isPresent() && hook.get().claims(action)) {
                LOGGER.info("Extension '{}' takes over the evolution run.", extension.getId());
                return hook.get();
            }
        }
        return null;
    }

    /**
     * Applies rewrite rules until no rule matches in a full scan (for the chosen strategy),
     * a stop condition matches, pause is requested, or the operation cap is reached.
     *
     * <p><b>first-first</b>: each step scans rules in order from index 0 and applies at most
     * one rule (the first that matches), then repeats.</p>
     * <p><b>round-robin</b>: each step scans from a rotating start index and applies at most
     * one rule (the first that matches), advancing the start after each success.</p>
     */
    private PureBigraph runEvolutionLoop(final PureBigraph initial,
                                         final RunContext ctx,
                                         final EvolutionOperation op) {
        PureBigraph current = initial;
        final boolean roundRobin = RuleApplicationStrategy.fromWire(ctx.action.getRuleApplicationStrategy())
            == RuleApplicationStrategy.ROUND_ROBIN;
        int nextStartIndex = 0;

        if (matchesVerification(current, ctx)) { return current; }

        while (ctx.totalApplied < ctx.maxOps) {
            if (shouldPause(op)) { return current; }

            boolean progressed = false;

            if (roundRobin) {
                final int n = ctx.rules.size();
                for (int o = 0; o < n; o++) {
                    if (shouldPause(op)) { return current; }
                    if (ctx.totalApplied >= ctx.maxOps) { return current; }
                    final int i = (nextStartIndex + o) % n;
                    if (ctx.hasWalkedEdge(ctx.predecessorId, i)) { continue; }
                    final PureBigraph result = tryApplyRule(current, i, ctx);
                    if (result == null) { continue; }

                    progressed = true;
                    recordApplication(result, ctx.rules.get(i), ctx);
                    current = result;
                    nextStartIndex = (i + 1) % n;

                    if (shouldPause(op)) { return current; }
                    if (matchesVerification(current, ctx)) {
                        LOGGER.info("Stop condition matched after {} rule application(s) – halting evolution.", ctx.totalApplied);
                        return current;
                    }
                    break;
                }
            } else {
                for (int i = 0; i < ctx.rules.size(); i++) {
                    if (shouldPause(op)) { return current; }
                    if (ctx.totalApplied >= ctx.maxOps) { return current; }
                    if (ctx.hasWalkedEdge(ctx.predecessorId, i)) { continue; }

                    final PureBigraph result = tryApplyRule(current, i, ctx);
                    if (result == null) { continue; }

                    progressed = true;
                    recordApplication(result, ctx.rules.get(i), ctx);
                    current = result;

                    if (shouldPause(op)) { return current; }
                    if (matchesVerification(current, ctx)) {
                        LOGGER.info("Stop condition matched after {} rule application(s) – halting evolution.", ctx.totalApplied);
                        return current;
                    }
                    break;
                }
            }

            if (!progressed) {
                LOGGER.info("Evolution loop finished. Total rule applications: {}", ctx.totalApplied);
                return current;
            }
        }

        LOGGER.info("Evolution loop finished. Total rule applications: {}", ctx.totalApplied);
        return current;
    }

    /**
     * Attempts to apply rule {@code i} to {@code current}.
     * Returns the rewritten bigraph on success, or {@code null} if the rule did not match.
     */
    private PureBigraph tryApplyRule(final PureBigraph current, final int ruleIndex,
                                     final RunContext ctx) {
        final AppliedRewrite applied = tryApplyRuleWithMatch(current, ruleIndex, ctx);
        return applied == null ? null : applied.result();
    }

    private AppliedRewrite tryApplyRuleWithMatch(final PureBigraph current, final int ruleIndex,
                                                 final RunContext ctx) {
        final PureBigraph[] pair = ctx.parsedRules.get(ruleIndex);
        if (pair == null) { return null; }

        final RuleRef ruleRef = ctx.rules.get(ruleIndex);
        final AppliedRewrite applied = RuleRewrite.apply(
            current, pair[0], pair[1], ruleRef.getId() != null ? ruleRef.getId() : ruleRef.getRedex());
        if (applied == null) {
            LOGGER.debug("Rule {} did not match.", ruleRef.getRedex());
            return null;
        }
        LOGGER.info("Applied rule {} (total applied: {})", ruleRef.getRedex(), ctx.totalApplied + 1);
        return applied;
    }

    /**
     * Returns true if the current bigraph matches at least one stop-condition verification bigraph.
     * Uses {@link PureBigraphMatcher} directly to check for a match without rewriting, which avoids
     * the "Parent can not be null" error that occurs when the verification bigraph has no sites.
     */
    private boolean matchesVerification(final PureBigraph current, final RunContext ctx) {
        return !matchingGoalIds(current, ctx).isEmpty();
    }

    /**
     * Returns the ids of every verification/goal bigraph that matches {@code current}.
     */
    private List<String> matchingGoalIds(final PureBigraph current, final RunContext ctx) {
        final List<String> matched = new ArrayList<>();
        if (ctx.verifications == null || ctx.verifications.isEmpty()) { return matched; }
        for (final GoalBigraph goal : ctx.verifications) {
            if (matchesGoal(current, goal.bigraph)) {
                if (!goal.id.isBlank()) {
                    matched.add(goal.id);
                }
                LOGGER.info("Verification bigraph '{}' matched.", goal.id);
            }
        }
        return matched;
    }

    private boolean matchesGoal(final PureBigraph current, final PureBigraph goal) {
        try {
            final ParametricReactionRule<PureBigraph> rule = new ParametricReactionRule<>(goal, goal);
            final PureBigraphMatcher matcher = new PureBigraphMatcher();
            final MatchIterable<?> matches = matcher.match(current, rule);
            return matches.iterator().hasNext();
        } catch (final Exception e) {
            LOGGER.warn("Verification match check failed: {}", e.getMessage());
            return false;
        }
    }

    private String ruleId(final RuleRef ruleRef) {
        if (ruleRef.getId() != null && !ruleRef.getId().isBlank()) {
            return ruleRef.getId();
        }
        return new File(ruleRef.getRedex()).getName();
    }

    private String ruleName(final RuleRef ruleRef) {
        if (ruleRef.getLabel() != null && !ruleRef.getLabel().isBlank()) {
            return ruleRef.getLabel();
        }
        return ruleId(ruleRef);
    }

    /**
     * Called after each successful rule application: increments the counter,
     * optionally writes a checkpoint file, appends an entry to evolution.json
     * when the edge is new, and always updates the live checkpoint-cursor.
     */
    private void recordApplication(final PureBigraph current, final RuleRef ruleRef,
                                   final RunContext ctx) {
        recordApplication(current, ruleRef, ctx, ctx.predecessorId);
    }

    private String recordApplication(final PureBigraph current, final RuleRef ruleRef,
                                     final RunContext ctx, final String predecessorId) {
        final String key = walkedEdgeKey(predecessorId, ruleId(ruleRef));
        final String existing = ctx.walkedEdges.get(key);
        if (existing != null) {
            LOGGER.debug("Reusing existing tree edge {} --{}--> {}.", predecessorId, ruleId(ruleRef), existing);
            ctx.predecessorId = existing;
            // Replay walks an existing path: still persist the cursor so the tree
            // highlight can follow each step even though no new operation is appended.
            persistLiveCursor(ctx, existing);
            return existing;
        }

        ctx.totalApplied++;
        final String checkpointId = UUID.randomUUID().toString().replace("-", "").substring(0, 15);

        String checkpointRelPath = null;
        if (ctx.action.isCheckpointFileGeneration() && ctx.action.getCheckpointsDir() != null) {
            checkpointRelPath = writeCheckpoint(
                toMutable(current, ctx.signature), ctx.action.getCheckpointsDir(), checkpointId);
        }

        ctx.evoJsonContent = appendOperation(
            ctx.evoJsonContent, checkpointId, ruleRef, predecessorId,
            checkpointRelPath, ctx.evoJsonPath);
        ctx.walkedEdges.put(key, checkpointId);
        ctx.predecessorId = checkpointId;
        persistLiveCursor(ctx, checkpointId);
        return checkpointId;
    }

    /**
     * Writes {@code checkpoint-cursor} so the evolution-tree watcher can highlight
     * the current step during a live run (new node or reused edge).
     */
    private void persistLiveCursor(final RunContext ctx, final String checkpointId) {
        if (checkpointId == null || checkpointId.isBlank() || ctx.evoJsonPath == null) {
            return;
        }
        if (checkpointId.equals(extractCursorId(ctx.evoJsonContent))) {
            return;
        }
        ctx.evoJsonContent = upsertRootValue(
            ctx.evoJsonContent, "checkpoint-cursor", "\"" + checkpointId + "\"", ctx.evoJsonPath);
    }

    /**
     * Writes the final bigraph back to workspace-bigraph.xmi, updates checkpoint-cursor
     * in evolution.json, and refreshes the GLSP canvas.
     */
    private void commitFinalState(final PureBigraph finalBigraph, final RunContext ctx) {
        publishState(finalBigraph, ctx, true);
    }

    // -----------------------------------------------------------------------
    // checkpoint file writing
    // -----------------------------------------------------------------------

    /**
     * Writes a checkpoint bigraph (3 files: .xmi + .signature.ecore + .signature.xmi)
     * to the checkpoints directory and returns the relative path (e.g. "checkpoints/abc.xmi").
     * The companion signature is the controls actually used in this snapshot, not the
     * in-memory union of every rule.
     */
    private String writeCheckpoint(final PureBigraphMutable bigraph,
                                   final String checkpointsDir,
                                   final String checkpointId) {
        final File dir = new File(checkpointsDir);
        if (!dir.exists()) { dir.mkdirs(); }
        final String fileName = "checkpoint-" + checkpointId + ".xmi";
        final File target = new File(dir, fileName);
        try {
            BigraphIO.writeToFile(bigraph, target);
            BigraphIO.writeSignatureToFile(EvolutionSignatures.usedControls(bigraph), target.getAbsolutePath());
            LOGGER.info("Wrote checkpoint to {}", target.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warn("Could not write checkpoint: {}", e.getMessage());
            return null;
        }

        return "checkpoints/" + fileName;
    }

    // -----------------------------------------------------------------------
    // evolution.json helpers (minimal JSON manipulation without extra deps)
    // -----------------------------------------------------------------------

    private String readEvolutionJson(final String path) {
        if (path == null) { return null; }
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not read evolution.json at {}: {}", path, e.getMessage());
            return null;
        }
    }

    static String walkedEdgeKey(final String predecessorId, final String ruleId) {
        return (predecessorId == null ? "" : predecessorId) + '\0' + (ruleId == null ? "" : ruleId);
    }

    /**
     * Edges already stored in {@code evolution.json}: each
     * {@code (predecessor, rule)} pair mapped to the child checkpoint id.
     */
    private Map<String, String> collectWalkedEdges(final String json) {
        final Map<String, String> edges = new HashMap<>();
        if (json == null) { return edges; }
        final int opsKey = indexOfRootKey(json, "\"operations\"");
        if (opsKey < 0) { return edges; }
        final int arrayOpen = json.indexOf('[', opsKey);
        if (arrayOpen < 0) { return edges; }
        final int arrayEnd = findValueEnd(json, arrayOpen);
        int cursor = arrayOpen + 1;
        while (cursor < arrayEnd) {
            final int objStart = json.indexOf('{', cursor);
            if (objStart < 0 || objStart >= arrayEnd) { break; }
            final int objEnd = findValueEnd(json, objStart);
            final String id = extractObjectStringField(json, objStart, objEnd, "id");
            final String predecessor = extractObjectStringField(json, objStart, objEnd, "predecessor");
            final String rule = extractObjectStringField(json, objStart, objEnd, "rule");
            if (id != null && rule != null && !rule.isBlank()) {
                edges.putIfAbsent(walkedEdgeKey(predecessor, rule), id);
            }
            cursor = objEnd;
        }
        return edges;
    }

    /** Quoted string value of {@code field} inside a JSON object, or {@code null}. */
    private String extractObjectStringField(final String json, final int objStart, final int objEnd,
                                            final String field) {
        final String quoted = "\"" + field + "\"";
        int from = objStart;
        while (from < objEnd) {
            final int found = json.indexOf(quoted, from);
            if (found < 0 || found >= objEnd) { return null; }
            if (nextNonWhitespace(json, found + quoted.length()) == ':') {
                int valueStart = json.indexOf(':', found + quoted.length()) + 1;
                while (valueStart < objEnd && Character.isWhitespace(json.charAt(valueStart))) {
                    valueStart++;
                }
                if (valueStart >= objEnd || json.charAt(valueStart) != '"') { return null; }
                final int valueEnd = findValueEnd(json, valueStart);
                return json.substring(valueStart + 1, valueEnd - 1);
            }
            from = found + 1;
        }
        return null;
    }

    /** Reads the value of the "checkpoint-cursor" field from the JSON string. */
    private String extractCursorId(final String json) {
        if (json == null) { return ""; }
        final String key = "\"checkpoint-cursor\"";
        final int idx = json.lastIndexOf(key);
        if (idx < 0) { return ""; }
        final int colon = json.indexOf(":", idx + key.length());
        if (colon < 0) { return ""; }
        final int q1 = json.indexOf("\"", colon + 1);
        final int q2 = json.indexOf("\"", q1 + 1);
        if (q1 < 0 || q2 < 0) { return ""; }
        return json.substring(q1 + 1, q2);
    }

    /**
     * Appends a new operation entry to the "operations" array in the JSON string,
     * writes it back to disk, and returns the updated JSON string.
     */
    private String appendOperation(final String json, final String checkpointId,
                                   final RuleRef ruleRef, final String predecessorId,
                                   final String resultPath, final String evoJsonPath) {
        if (json == null || evoJsonPath == null) { return json; }

        final String date = Instant.now().toString();
        final String result = resultPath != null ? "\"" + resultPath + "\"" : "null";
        final String ruleId = ruleRef.getId() != null ? ruleRef.getId() : new File(ruleRef.getRedex()).getName();

        final String entry = String.format(
            "    {\n" +
            "        \"id\" : \"%s\",\n" +
            "        \"type\" : \"rule\",\n" +
            "        \"date\" : \"%s\",\n" +
            "        \"predecessor\" : \"%s\",\n" +
            "        \"result\" : %s,\n" +
            "        \"rule\" : \"%s\"\n" +
            "    }",
            checkpointId, date, predecessorId, result, ruleId);

        // Insert before the closing "]" of the operations array. The array end is
        // located structurally because other root keys (e.g. goalPaths) may also
        // contain "]" further down in the document.
        final int opsStart = indexOfRootKey(json, "\"operations\"");
        if (opsStart < 0) { return json; }
        final int arrayOpen = json.indexOf("[", opsStart);
        if (arrayOpen < 0) { return json; }
        final int closingBracket = findValueEnd(json, arrayOpen) - 1;
        if (closingBracket < arrayOpen) { return json; }

        // Check if there are already entries (need a comma separator)
        final String arrayContent = json.substring(arrayOpen + 1, closingBracket).trim();
        final String separator = arrayContent.isEmpty() ? "\n" : ",\n";

        final String updated = json.substring(0, closingBracket) + separator + entry + "\n" +
            json.substring(closingBracket);

        writeJson(updated, evoJsonPath);
        return updated;
    }

    /**
     * Inserts or replaces a root-level key with a raw JSON value, writes the
     * document back to disk, and returns the updated JSON string.
     */
    private String upsertRootValue(final String json, final String key,
                                   final String rawJsonValue, final String evoJsonPath) {
        if (json == null || evoJsonPath == null) { return json; }

        final String quotedKey = "\"" + key + "\"";
        final String updated;
        final int keyIdx = indexOfRootKey(json, quotedKey);
        if (keyIdx >= 0) {
            final int colon = json.indexOf(':', keyIdx + quotedKey.length());
            if (colon < 0) { return json; }
            int valueStart = colon + 1;
            while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
                valueStart++;
            }
            final int valueEnd = findValueEnd(json, valueStart);
            updated = json.substring(0, valueStart) + rawJsonValue + json.substring(valueEnd);
        } else {
            final int closingBrace = json.lastIndexOf('}');
            if (closingBrace < 0) { return json; }
            final String head = json.substring(0, closingBrace).stripTrailing();
            final boolean needsComma = !head.endsWith("{");
            updated = head + (needsComma ? "," : "") + "\n    " + quotedKey + " : " + rawJsonValue + "\n"
                + json.substring(closingBrace);
        }

        writeJson(updated, evoJsonPath);
        return updated;
    }

    /**
     * Finds the given quoted key at nesting depth 1 (a key of the root object),
     * skipping matches inside strings or nested structures. Returns -1 if absent.
     */
    private int indexOfRootKey(final String json, final String quotedKey) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (inString) {
                if (c == '\\') { i++; } else if (c == '"') { inString = false; }
                continue;
            }
            switch (c) {
                case '"':
                    if (depth == 1 && json.startsWith(quotedKey, i)
                        && nextNonWhitespace(json, i + quotedKey.length()) == ':') {
                        return i;
                    }
                    inString = true;
                    break;
                case '{':
                case '[':
                    depth++;
                    break;
                case '}':
                case ']':
                    depth--;
                    break;
                default:
                    break;
            }
        }
        return -1;
    }

    /**
     * Returns the exclusive end index of the JSON value starting at
     * {@code valueStart} (object, array, string or literal), string-escape aware.
     */
    private int findValueEnd(final String json, final int valueStart) {
        final char first = json.charAt(valueStart);
        if (first == '{' || first == '[') {
            int depth = 0;
            boolean inString = false;
            for (int i = valueStart; i < json.length(); i++) {
                final char c = json.charAt(i);
                if (inString) {
                    if (c == '\\') { i++; } else if (c == '"') { inString = false; }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) { return i + 1; }
                }
            }
            return json.length();
        }
        if (first == '"') {
            for (int i = valueStart + 1; i < json.length(); i++) {
                final char c = json.charAt(i);
                if (c == '\\') { i++; } else if (c == '"') { return i + 1; }
            }
            return json.length();
        }
        int i = valueStart;
        while (i < json.length() && ",}]".indexOf(json.charAt(i)) < 0
            && !Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }

    private char nextNonWhitespace(final String json, final int from) {
        for (int i = from; i < json.length(); i++) {
            if (!Character.isWhitespace(json.charAt(i))) { return json.charAt(i); }
        }
        return '\0';
    }

    private void writeJson(final String content, final String path) {
        try {
            Files.write(Paths.get(path), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warn("Could not write evolution.json to {}: {}", path, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------


    /** Returns true and marks the operation as PAUSED if a pause was requested. */
    private boolean shouldPause(final EvolutionOperation op) {
        if (op != null && op.isPauseRequested()) {
            op.markPaused();
            LOGGER.info("Operation {} paused.", op.getOperationId());
            return true;
        }
        return false;
    }

    private void publishState(final PureBigraph bigraph, final RunContext ctx, final boolean persistToWorkspace) {
        actionDispatcher.dispatch(new PublishEvolutionStateAction(
            toMutable(bigraph, ctx.signature),
            persistToWorkspace,
            ctx.predecessorId,
            ctx.evoJsonPath)).join();
    }

    private Throwable unwrapCompletionException(final Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private String describeFailure(final Throwable throwable) {
        final String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    private PureBigraph toImmutable(final PureBigraphMutable mutable, final DynamicSignature sig) {
        try {
            return EvolutionSignatures.rebind(mutable, sig);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to rebind bigraph onto the merged evolution signature", e);
        }
    }

    private PureBigraph toImmutableSamePackage(final PureBigraphMutable mutable, final DynamicSignature sig) {
        return PureBigraphBuilder
            .create(sig, mutable.getMetaModel(), mutable.getInstanceModel())
            .create();
    }

    private PureBigraphMutable toMutableSamePackage(final PureBigraph immutable, final DynamicSignature sig) {
        return PureBigraphBuilder
            .create(sig, immutable.getMetaModel(), immutable.getInstanceModel())
            .createMutable();
    }

    private PureBigraphMutable toMutable(final PureBigraph immutable, final DynamicSignature sig) {
        try {
            final PureBigraph rebound = EvolutionSignatures.rebind(immutable, sig);
            return toMutableSamePackage(rebound, sig);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to rebind evolved bigraph onto the merged signature", e);
        }
    }
}
