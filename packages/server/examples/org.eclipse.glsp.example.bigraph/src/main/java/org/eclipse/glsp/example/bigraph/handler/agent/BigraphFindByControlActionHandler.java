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

package org.eclipse.glsp.example.bigraph.handler.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.actions.agent.AgentResponseAction;
import org.eclipse.glsp.example.bigraph.actions.agent.RequestBigraphFindByControlAction;
import org.eclipse.glsp.example.bigraph.handler.BigraphQueryHelper;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import com.google.inject.Inject;

public class BigraphFindByControlActionHandler extends AbstractActionHandler<RequestBigraphFindByControlAction> {

    @Inject
    protected BigraphModelState modelState;

    @Inject
    protected BigraphQueryHelper queryHelper;

    @Override
    public List<Action> executeAction(final RequestBigraphFindByControlAction action) {
        String targetControl = action.getControl();
        if (modelState.getMutableBigraph() == null) {
            return List.of(new AgentResponseAction(action.getRequestId(),
                Map.of("control", targetControl, "matchCount", 0, "matches", List.<Map<String, Object>>of())));
        }

        List<Map<String, Object>> matches = modelState.getMutableBigraph().getNodes().stream()
            .filter(n -> {
                String controlName = ((NodeEntity<DynamicControl>) n).getControl().getNamedType().stringValue();
                return controlName.equalsIgnoreCase(targetControl);
            })
            .map(n -> queryHelper.buildNodeSummary(n))
            .collect(Collectors.toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("control", targetControl);
        payload.put("matchCount", matches.size());
        payload.put("matches", matches);

        return List.of(new AgentResponseAction(action.getRequestId(), payload));
    }
}
