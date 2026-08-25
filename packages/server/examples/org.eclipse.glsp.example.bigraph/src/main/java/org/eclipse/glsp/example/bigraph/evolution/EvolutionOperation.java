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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a single running evolution operation (play).
 * The background thread transitions through {@link State} values and checks
 * {@link #isPauseRequested()} at safe checkpoints.
 */
public class EvolutionOperation {

    public enum State {
        RUNNING,
        PAUSE_REQUESTED,
        PAUSED,
        FAILED
    }

    private final String operationId;
    private final String actionType;
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
    /**
     * Results contributed by extension run hooks, keyed by extension id.
     * Published to the client via {@code EvolutionFinishedAction.extensionResults}
     * when the run ends. {@code null} until the first result is stored.
     */
    private volatile Map<String, Object> extensionResults;

    public EvolutionOperation(final String operationId, final String actionType) {
        this.operationId = operationId;
        this.actionType  = actionType;
    }

    public String getOperationId() { return operationId; }
    public String getActionType()  { return actionType; }

    public State getState() { return state.get(); }

    /** Returns {@code true} if a pause has been requested but not yet applied. */
    public boolean isPauseRequested() {
        return state.get() == State.PAUSE_REQUESTED;
    }

    /** Called by the background thread once it has actually stopped. */
    public void markPaused() {
        state.compareAndSet(State.PAUSE_REQUESTED, State.PAUSED);
    }

    /** Marks the operation as failed unless it has already paused. */
    public void markFailed() {
        state.updateAndGet(current -> current == State.PAUSED ? current : State.FAILED);
    }

    /** Request the operation to pause at the next safe checkpoint. */
    public void requestPause() {
        state.compareAndSet(State.RUNNING, State.PAUSE_REQUESTED);
    }

    /** Stores a result payload for the given extension id (insertion-ordered). */
    public synchronized void putExtensionResult(final String extensionId, final Object result) {
        if (extensionResults == null) {
            extensionResults = Collections.synchronizedMap(new LinkedHashMap<>());
        }
        extensionResults.put(extensionId, result);
    }

    /** @return results keyed by extension id, or {@code null} when no hook stored any. */
    public Map<String, Object> getExtensionResults() {
        return extensionResults;
    }
}
