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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.eclipse.glsp.example.bigraph.actions.agent.AgentResponseAction;
import org.eclipse.glsp.example.bigraph.actions.agent.RequestBigraphNodeInfoAction;
import org.eclipse.glsp.example.bigraph.handler.BigraphQueryHelper;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import com.google.inject.Inject;

public class BigraphNodeInfoActionHandler extends AbstractActionHandler<RequestBigraphNodeInfoAction> {

    @Inject
    protected BigraphQueryHelper queryHelper;

    @Override
    public List<Action> executeAction(final RequestBigraphNodeInfoAction action) {
        Optional<BigraphEntity<?>> entity = queryHelper.resolveEntity(action.getNodeId());

        if (entity.isEmpty()) {
            return List.of(new AgentResponseAction(
                action.getRequestId(),
                Map.of("error", "Node not found: " + action.getNodeId())));
        }

        Map<String, Object> detail = queryHelper.buildNodeDetail(entity.get());

        return List.of(new AgentResponseAction(action.getRequestId(), detail));
    }
}
