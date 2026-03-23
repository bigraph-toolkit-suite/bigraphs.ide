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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.eclipse.glsp.example.bigraph.actions.agent.AgentResponseAction;
import org.eclipse.glsp.example.bigraph.actions.agent.RequestBigraphChildrenAction;
import org.eclipse.glsp.example.bigraph.handler.BigraphQueryHelper;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import com.google.inject.Inject;

public class BigraphChildrenActionHandler extends AbstractActionHandler<RequestBigraphChildrenAction> {

    @Inject
    protected BigraphModelState modelState;

    @Inject
    protected BigraphQueryHelper queryHelper;

    @Override
    public List<Action> executeAction(final RequestBigraphChildrenAction action) {
        Optional<BigraphEntity<?>> entity = queryHelper.resolveEntity(action.getNodeId());

        if (entity.isEmpty()) {
            Map<String, Object> errorPayload = new LinkedHashMap<>();
            errorPayload.put("error", "Node not found: " + action.getNodeId());
            return List.of(new AgentResponseAction(action.getRequestId(), errorPayload));
        }

        if (modelState.getMutableBigraph() == null) {
            Map<String, Object> errorPayload = new LinkedHashMap<>();
            errorPayload.put("error", "No bigraph loaded");
            errorPayload.put("children", List.of());
            return List.of(new AgentResponseAction(action.getRequestId(), errorPayload));
        }

        Collection<BigraphEntity<?>> children = modelState.getMutableBigraph().getChildrenOf(entity.get());

        List<Map<String, Object>> childSummaries = children.stream()
            .map(queryHelper::buildNodeSummary)
            .collect(Collectors.toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("parentGmodelId", queryHelper.findGModelIdForEntity(entity.get()).orElse(null));
        payload.put("children", childSummaries);

        return List.of(new AgentResponseAction(action.getRequestId(), payload));
    }
}
