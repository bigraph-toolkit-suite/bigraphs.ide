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

package org.eclipse.glsp.example.bigraph.actions;

import org.eclipse.glsp.server.actions.ResponseAction;

/**
 * Sent from the server to the client when an evolution operation has finished or been paused.
 * The client uses this to restore the Play/Step buttons and hide the Pause button.
 */
public class EvolutionFinishedAction extends ResponseAction {
    public static final String KIND = "bigraph.evolutionFinished";

    private String operationId;
    /** "completed" or "paused" */
    private String reason;

    public EvolutionFinishedAction() {
        super(KIND);
    }

    public EvolutionFinishedAction(final String operationId, final String reason) {
        super(KIND);
        this.operationId = operationId;
        this.reason = reason;
    }

    public String getOperationId() { return operationId; }
    public String getReason()      { return reason; }
}
