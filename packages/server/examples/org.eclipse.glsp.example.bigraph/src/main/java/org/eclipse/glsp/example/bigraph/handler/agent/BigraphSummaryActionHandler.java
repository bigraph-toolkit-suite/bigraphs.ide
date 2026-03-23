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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.eclipse.glsp.example.bigraph.actions.agent.AgentResponseAction;
import org.eclipse.glsp.example.bigraph.actions.agent.RequestBigraphSummaryAction;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import com.google.inject.Inject;

public class BigraphSummaryActionHandler extends AbstractActionHandler<RequestBigraphSummaryAction> {

    @Inject
    protected BigraphModelState modelState;

    @Override
    public List<Action> executeAction(final RequestBigraphSummaryAction action) {
        PureBigraphMutable bigraph = modelState.getMutableBigraph();
        System.out.println("[bigraph-glsp] Summary handler invoked, requestId=" + action.getRequestId()
            + ", mutableBigraph=" + (bigraph != null));
        if (bigraph == null) {
            return List.of(new AgentResponseAction(action.getRequestId(),
                Map.of("error", "No bigraph loaded")));
        }

        Set<String> controlNames = bigraph.getSignature().getControls().stream()
            .map(c -> c.getNamedType().stringValue())
            .collect(Collectors.toSet());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalRoots", bigraph.getRoots().size());
        payload.put("totalNodes", bigraph.getNodes().size());
        payload.put("totalSites", bigraph.getSites().size());
        payload.put("totalEdges", bigraph.getEdges().size());
        payload.put("totalOuterNames", bigraph.getOuterNames().size());
        payload.put("totalInnerNames", bigraph.getInnerNames().size());
        payload.put("controls", new ArrayList<>(controlNames));
        payload.put("maxDepth", computeMaxDepth(bigraph));

        System.out.println("[bigraph-glsp] Summary computed: roots=" + bigraph.getRoots().size()
            + ", nodes=" + bigraph.getNodes().size() + ", requestId=" + action.getRequestId());
        return List.of(new AgentResponseAction(action.getRequestId(), payload));
    }

    private int computeMaxDepth(final PureBigraphMutable bigraph) {
        int max = 0;
        for (BigraphEntity.RootEntity root : bigraph.getRoots()) {
            max = Math.max(max, computeDepth(bigraph, root, 0));
        }
        return max;
    }

    private int computeDepth(final PureBigraphMutable bigraph, final BigraphEntity<?> entity, final int current) {
        int max = current;
        for (BigraphEntity<?> child : bigraph.getChildrenOf(entity)) {
            max = Math.max(max, computeDepth(bigraph, child, current + 1));
        }
        return max;
    }
}
