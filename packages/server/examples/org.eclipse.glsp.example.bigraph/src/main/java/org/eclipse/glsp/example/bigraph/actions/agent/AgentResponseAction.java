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

package org.eclipse.glsp.example.bigraph.actions.agent;

import java.util.Map;

import org.eclipse.glsp.server.actions.ResponseAction;

/**
 * Base response for all agent query responses.
 * Carries a generic Map payload so we don't need a separate POJO for each response.
 */
public class AgentResponseAction extends ResponseAction {

    public static final String KIND = "bigraph.agentResponse";

    private Map<String, Object> payload;

    public AgentResponseAction() {
        super(KIND);
    }

    public AgentResponseAction(final String responseId, final Map<String, Object> payload) {
        super(KIND);
        this.payload = payload;
        setResponseId(responseId);
    }

    public Map<String, Object> getPayload() { return payload; }

    public void setPayload(final Map<String, Object> payload) { this.payload = payload; }
}
