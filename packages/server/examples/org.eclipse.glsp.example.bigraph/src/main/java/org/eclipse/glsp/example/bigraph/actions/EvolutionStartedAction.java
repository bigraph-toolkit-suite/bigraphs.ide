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
 * Sent from the server to the client immediately after a play operation is
 * started in a background thread. Carries the generated {@code operationId} so
 * the client can later pause it.
 *
 * Extends {@link ResponseAction} so the {@code ClientActionForwarder} automatically
 * forwards it to the client without requiring a server-side handler registration.
 */
public class EvolutionStartedAction extends ResponseAction {
    public static final String KIND = "bigraph.evolutionStarted";

    private String operationId;
    /** "play" – the type that was started */
    private String actionType;

    public EvolutionStartedAction() {
        super(KIND);
    }

    public EvolutionStartedAction(final String operationId, final String actionType) {
        super(KIND);
        this.operationId = operationId;
        this.actionType = actionType;
    }

    public String getOperationId() { return operationId; }
    public String getActionType()  { return actionType; }
}
