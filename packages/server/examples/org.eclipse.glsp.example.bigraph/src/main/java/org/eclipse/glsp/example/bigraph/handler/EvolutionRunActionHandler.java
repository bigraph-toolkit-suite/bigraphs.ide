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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.eclipse.glsp.example.bigraph.DemoBigraphCreator;
import org.eclipse.glsp.example.bigraph.actions.EvolutionRunAction;
import org.eclipse.glsp.example.bigraph.actions.EvolutionRunAction.RuleRef;
import org.eclipse.glsp.example.bigraph.actions.EvolutionRunAction.VerificationRef;
import org.eclipse.glsp.example.bigraph.actions.EvolutionFinishedAction;
import org.eclipse.glsp.example.bigraph.actions.EvolutionStartedAction;
import org.eclipse.glsp.example.bigraph.actions.PublishEvolutionStateAction;
import org.eclipse.glsp.example.bigraph.evolution.EvolutionOperation;
import org.eclipse.glsp.example.bigraph.evolution.EvolutionRegistry;
import org.eclipse.glsp.example.bigraph.evolution.RuleApplicationStrategy;
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
                actionDispatcher.dispatch(new EvolutionFinishedAction(operationId, reason));
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

    private void runStep(final EvolutionRunAction action, final EvolutionOperation op) {
        try {
            if (shouldPause(op)) { return; }

            final PureBigraphMutable agentMutable = modelState.getMutableBigraph();
            if (agentMutable == null) {
                if (op != null) {
                    op.markFailed();
                }
                BigraphNotifications.notifyError(actionDispatcher, "Cannot run evolution because no bigraph is loaded.");
                LOGGER.error("No bigraph loaded in model state.");
                return;
            }

            final DynamicSignature signature = agentMutable.getSignature();
            final PureBigraph initial = toImmutable(agentMutable, signature);
            final int maxOps = resolveMaxOps(action);
            final List<PureBigraph[]> parsedRules = loadRules(action.getRules());
            final List<PureBigraph> parsedVerifications = loadVerifications(action.getVerificationBigraphs(), signature);

            final RunContext ctx = new RunContext(action, signature, parsedRules, parsedVerifications, maxOps);
            final PureBigraph finalBigraph = runEvolutionLoop(initial, ctx, op);

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
    private List<PureBigraph[]> loadRules(final List<RuleRef> rules) {
        final List<PureBigraph[]> parsed = new ArrayList<>();
        for (final RuleRef rule : rules) {
            try {
                final PureBigraphMutable rx = BigraphIO.parseBigraphFromFile(new File(rule.getRedex()));
                final PureBigraphMutable rc = BigraphIO.parseBigraphFromFile(new File(rule.getReactum()));
                parsed.add(new PureBigraph[]{
                    toImmutable(rx, rx.getSignature()),
                    toImmutable(rc, rc.getSignature())
                });
            } catch (BigraphLoadException e) {
                LOGGER.warn("Could not load rule {}/{} – skipping. {}", rule.getRedex(), rule.getReactum(), e.getMessage());
                parsed.add(null);
            }
        }
        return parsed;
    }

    /** Parses stop-condition verification bigraphs from disk once, before the loop starts. */
    private List<PureBigraph> loadVerifications(final List<VerificationRef> refs,
                                                final DynamicSignature signature) {
        final List<PureBigraph> result = new ArrayList<>();
        if (refs == null) { return result; }
        for (final VerificationRef ref : refs) {
            try {
                final PureBigraphMutable vb = BigraphIO.parseBigraphFromFile(new File(ref.getPath()));
                result.add(toImmutable(vb, vb.getSignature()));
            } catch (BigraphLoadException e) {
                LOGGER.warn("Could not load verification bigraph {} – skipping. {}", ref.getPath(), e.getMessage());
            }
        }
        LOGGER.info("Loaded {} stop-condition verification bigraph(s).", result.size());
        return result;
    }

    /**
     * Carries the mutable state that is shared across rounds and between
     * the loop body and the post-loop commit.
     */
    private static final class RunContext {
        final EvolutionRunAction action;
        final DynamicSignature signature;
        final List<RuleRef> rules;
        final List<PureBigraph[]> parsedRules;
        final List<PureBigraph> verifications;
        final int maxOps;
        final String evoJsonPath;
        String evoJsonContent;
        String predecessorId;
        int totalApplied = 0;

        RunContext(final EvolutionRunAction action, final DynamicSignature signature,
                   final List<PureBigraph[]> parsedRules, final List<PureBigraph> verifications,
                   final int maxOps) {
            this.action = action;
            this.signature = signature;
            this.rules = action.getRules();
            this.parsedRules = parsedRules;
            this.verifications = verifications;
            this.maxOps = maxOps;
            this.evoJsonPath = action.getEvolutionJsonPath();
        }
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
        // Read evolution.json once before the loop; updated in-memory after each application.
        // Use checkpoint-cursor as the predecessor for the first new operation so that
        // branching from any historic checkpoint is handled correctly.
        ctx.evoJsonContent = readEvolutionJson(ctx.evoJsonPath);
        ctx.predecessorId = extractCursorId(ctx.evoJsonContent);

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
                    final PureBigraph result = tryApplyRule(current, i, ctx);
                    if (result == null) { continue; }

                    progressed = true;
                    recordApplication(result, ctx.rules.get(i), ctx);
                    current = result;
                    nextStartIndex = (i + 1) % n;

                    if (ctx.action.isVisualizeIntermediateSteps()) {
                        publishState(current, ctx, false);
                    }
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

                    final PureBigraph result = tryApplyRule(current, i, ctx);
                    if (result == null) { continue; }

                    progressed = true;
                    recordApplication(result, ctx.rules.get(i), ctx);
                    current = result;

                    if (ctx.action.isVisualizeIntermediateSteps()) {
                        publishState(current, ctx, false);
                    }
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
        final PureBigraph[] pair = ctx.parsedRules.get(ruleIndex);
        if (pair == null) { return null; }

        final RuleRef ruleRef = ctx.rules.get(ruleIndex);
        final PureBigraph result = DemoBigraphCreator.applyRewriteRule(current, pair[0], pair[1], "");
        if (result == null) {
            LOGGER.debug("Rule {} did not match.", ruleRef.getRedex());
            return null;
        }
        LOGGER.info("Applied rule {} (total applied: {})", ruleRef.getRedex(), ctx.totalApplied + 1);
        return result;
    }

    /**
     * Returns true if the current bigraph matches at least one stop-condition verification bigraph.
     * Uses {@link PureBigraphMatcher} directly to check for a match without rewriting, which avoids
     * the "Parent can not be null" error that occurs when the verification bigraph has no sites.
     */
    private boolean matchesVerification(final PureBigraph current, final RunContext ctx) {
        if (ctx.verifications == null || ctx.verifications.isEmpty()) { return false; }
        for (final PureBigraph vb : ctx.verifications) {
            try {
                final ParametricReactionRule<PureBigraph> rule = new ParametricReactionRule<>(vb, vb);
                final PureBigraphMatcher matcher = new PureBigraphMatcher();
                final MatchIterable<?> matches = matcher.match(current, rule);
                if (matches.iterator().hasNext()) {
                    LOGGER.info("Verification bigraph matched – stop condition triggered.");
                    return true;
                }
            } catch (final Exception e) {
                LOGGER.warn("Verification match check failed: {}", e.getMessage());
            }
        }
        return false;
    }

    /**
     * Called after each successful rule application: increments the counter,
     * optionally writes a checkpoint file, and always appends an entry to evolution.json.
     */
    private void recordApplication(final PureBigraph current, final RuleRef ruleRef,
                                   final RunContext ctx) {
        ctx.totalApplied++;
        final String checkpointId = UUID.randomUUID().toString().replace("-", "").substring(0, 15);

        String checkpointRelPath = null;
        if (ctx.action.isCheckpointFileGeneration() && ctx.action.getCheckpointsDir() != null) {
            checkpointRelPath = writeCheckpoint(
                toMutable(current, ctx.signature), ctx.action.getCheckpointsDir(), checkpointId);
        }

        ctx.evoJsonContent = appendOperation(
            ctx.evoJsonContent, checkpointId, ruleRef, ctx.predecessorId,
            checkpointRelPath, ctx.evoJsonPath);
        ctx.predecessorId = checkpointId;
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
     * The signature files are copied from the workspace-bigraph (same directory as sourceFilePath)
     * since the signature is constant throughout an evolution run.
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
            LOGGER.info("Wrote checkpoint to {}", target.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warn("Could not write checkpoint: {}", e.getMessage());
            return null;
        }

        // Copy signature files alongside the checkpoint XMI.
        // The workspace-bigraph lives in the same folder and shares the same signature.
        final String sourceBase = modelState.getSourceFilePath().replace(".xmi", "");
        final String targetBase = target.getAbsolutePath().replace(".xmi", "");
        for (final String sigExt : new String[]{ ".signature.ecore", ".signature.xmi" }) {
            final File sigSrc = new File(sourceBase + sigExt);
            if (sigSrc.exists()) {
                try {
                    java.nio.file.Files.copy(
                        sigSrc.toPath(),
                        new File(targetBase + sigExt).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOGGER.warn("Could not copy signature file {} for checkpoint: {}", sigExt, e.getMessage());
                }
            }
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

    /** Extracts the id of the last entry in the "operations" array, or "" if none. */
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

        // Insert before the closing "]" of the operations array
        final int closingBracket = json.lastIndexOf("]");
        if (closingBracket < 0) { return json; }

        // Check if there are already entries (need a comma separator)
        final int opsStart = json.indexOf("\"operations\"");
        final int arrayOpen = json.indexOf("[", opsStart);
        final String arrayContent = json.substring(arrayOpen + 1, closingBracket).trim();
        final String separator = arrayContent.isEmpty() ? "\n" : ",\n";

        final String updated = json.substring(0, closingBracket) + separator + entry + "\n" +
            json.substring(closingBracket);

        writeJson(updated, evoJsonPath);
        return updated;
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
        return PureBigraphBuilder
            .create(sig, mutable.getMetaModel(), mutable.getInstanceModel())
            .create();
    }

    private PureBigraphMutable toMutable(final PureBigraph immutable, final DynamicSignature sig) {
        return PureBigraphBuilder
            .create(sig, immutable.getMetaModel(), immutable.getInstanceModel())
            .createMutable();
    }
}
