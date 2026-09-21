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

package org.eclipse.glsp.example.bigraph.extensions;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.eclipse.glsp.example.bigraph.actions.EvolutionRunAction;
import org.eclipse.glsp.example.bigraph.evolution.AppliedRewrite;

/**
 * Facade over the core evolution machinery, handed to an
 * {@link EvolutionRunHook} when an extension takes over a run.
 *
 * <p>The API deliberately exposes rules by index instead of leaking the
 * parsed rule internals: a hook drives the run (which rule to try on which
 * bigraph, in which order) while the core keeps ownership of matching,
 * checkpoint files and {@code evolution.json} bookkeeping.</p>
 */
public interface EvolutionRunApi {

    /** The action that started this run (wire payload from the client). */
    EvolutionRunAction getAction();

    /**
     * Convenience accessor for this extension's entry in
     * {@link EvolutionRunAction#getExtensionOptions()}. Returns an empty map
     * when the client sent no options for {@code extensionId}.
     */
    @SuppressWarnings("unchecked")
    default Map<String, Object> getOptions(final String extensionId) {
        final Object options = getAction().getExtensionOptions().get(extensionId);
        return options instanceof Map ? (Map<String, Object>) options : Collections.emptyMap();
    }

    /** Number of rewrite rules in this run (indices {@code 0..count-1}). */
    int getRuleCount();

    /** Stable id of rule {@code ruleIndex} (falls back to the redex file name). */
    String getRuleId(int ruleIndex);

    /**
     * Display name of rule {@code ruleIndex} (the label the user sees,
     * e.g. {@code forward_alloc}). Falls back to {@link #getRuleId} when
     * the client sent no label.
     */
    String getRuleName(int ruleIndex);

    /**
     * Whether {@code evolution.json} already has an operation that applied
     * rule {@code ruleIndex} from {@code predecessorCheckpointId}. Hooks that
     * grow the tree (evolution, exploration) should skip matching in that case;
     * replay hooks must not, because they re-apply an existing path on purpose.
     */
    boolean hasWalkedEdge(String predecessorCheckpointId, int ruleIndex);

    /**
     * Attempts to apply rule {@code ruleIndex} to {@code current}.
     * Returns the rewritten bigraph, or {@code null} when the rule did not match.
     * Does <em>not</em> record anything — call {@link #recordApplication} on success.
     */
    PureBigraph tryApplyRule(PureBigraph current, int ruleIndex);

    /**
     * Same as {@link #tryApplyRule} but keeps the match that produced the result.
     * {@code null} when the rule did not match.
     */
    AppliedRewrite tryApplyRuleWithMatch(PureBigraph current, int ruleIndex);

    /**
     * Records a successful application of rule {@code ruleIndex}: increments the
     * operation counter, optionally writes a checkpoint file, and appends an
     * operation entry (child of {@code predecessorCheckpointId}) to
     * {@code evolution.json}. Also advances the run's predecessor and the
     * live {@code checkpoint-cursor} so the tree highlight can follow the step.
     *
     * @return the id of the newly created checkpoint, or the existing child
     *         if this {@code (predecessor, rule)} edge is already in the tree
     */
    String recordApplication(PureBigraph result, int ruleIndex, String predecessorCheckpointId);

    /** Ids of every verification/goal bigraph matching {@code bigraph}. */
    List<String> matchingGoalIds(PureBigraph bigraph);

    /**
     * Checks whether the user requested a pause and, if so, marks the operation
     * as paused. Hooks must consult this at every safe point and stop when it
     * returns {@code true}.
     */
    boolean shouldPause();

    /** Rule applications performed so far in this run. */
    int getTotalApplied();

    /** Maximum rule applications for this run ({@code Integer.MAX_VALUE} when unbounded). */
    int getMaxOperations();

    /** The {@code checkpoint-cursor} id read from {@code evolution.json} at run start. */
    String getStartCheckpointId();

    /**
     * Overrides the predecessor used by {@code commitFinalState} when the run
     * ends — e.g. an exploration resets it to {@link #getStartCheckpointId()}
     * so the workspace cursor stays put.
     */
    void setPredecessorId(String checkpointId);

    /**
     * Publishes {@code bigraph} to the GLSP canvas. With
     * {@code persistToWorkspace} the state is also written to
     * {@code workspace-bigraph.xmi} and the cursor is updated; without it the
     * canvas just follows along (live view of intermediate steps).
     */
    void publishState(PureBigraph bigraph, boolean persistToWorkspace);

    /**
     * Stores a result payload for {@code extensionId}, delivered to the client
     * via {@code EvolutionFinishedAction.extensionResults} when the run ends.
     */
    void putExtensionResult(String extensionId, Object result);

    /**
     * Inserts or replaces a root-level key in {@code evolution.json} with the
     * given raw JSON value (object, array or literal) and writes the file.
     */
    void writeEvolutionJsonRootValue(String key, String rawJsonValue);
}
