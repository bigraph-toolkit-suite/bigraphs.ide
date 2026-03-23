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

import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.actions.agent.AgentResponseAction;
import org.eclipse.glsp.example.bigraph.actions.agent.RequestBigraphSignatureAction;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import com.google.inject.Inject;

public class BigraphSignatureActionHandler extends AbstractActionHandler<RequestBigraphSignatureAction> {

    @Inject
    protected BigraphModelState modelState;

    @Override
    public List<Action> executeAction(final RequestBigraphSignatureAction action) {
        if (modelState.getMutableBigraph() == null) {
            return List.of(new AgentResponseAction(action.getRequestId(),
                Map.of("controls", List.of())));
        }
        DynamicSignature sig = modelState.getMutableBigraph().getSignature();

        List<Map<String, Object>> controls = sig.getControls().stream()
            .map(c -> {
                Map<String, Object> control = new LinkedHashMap<>();
                control.put("name", c.getNamedType().stringValue());
                control.put("arity", c.getArity().getValue());
                control.put("kind", c.getControlKind().name());
                return control;
            })
            .collect(Collectors.toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("controls", controls);

        return List.of(new AgentResponseAction(action.getRequestId(), payload));
    }
}
