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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.Link;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.Port;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.eclipse.glsp.example.bigraph.actions.agent.AgentResponseAction;
import org.eclipse.glsp.example.bigraph.actions.agent.RequestBigraphNeighborsAction;
import org.eclipse.glsp.example.bigraph.handler.BigraphQueryHelper;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import com.google.inject.Inject;

public class BigraphNeighborsActionHandler extends AbstractActionHandler<RequestBigraphNeighborsAction> {

    @Inject
    protected BigraphModelState modelState;

    @Inject
    protected BigraphQueryHelper queryHelper;

    @Override
    public List<Action> executeAction(final RequestBigraphNeighborsAction action) {
        Optional<BigraphEntity<?>> entityOpt = queryHelper.resolveEntity(action.getNodeId());

        if (entityOpt.isEmpty()) {
            return List.of(new AgentResponseAction(
                action.getRequestId(),
                Map.of("error", "Node not found: " + action.getNodeId())));
        }

        BigraphEntity<?> entity = entityOpt.get();
        PureBigraphMutable bigraph = modelState.getMutableBigraph();

        if (!(entity instanceof NodeEntity)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("neighbors", List.of());
            payload.put("note", "Only nodes have link-graph neighbors");
            return List.of(new AgentResponseAction(action.getRequestId(), payload));
        }

        @SuppressWarnings("unchecked")
        NodeEntity<DynamicControl> node = (NodeEntity<DynamicControl>) entity;

        Set<Link> connectedLinks = new HashSet<>();
        for (Port port : bigraph.getPorts(node)) {
            Link link = bigraph.getLinkOfPoint(port);
            if (link != null) {
                connectedLinks.add(link);
            }
        }

        Set<BigraphEntity<?>> neighborEntities = new HashSet<>();
        for (Link link : connectedLinks) {
            Collection<BigraphEntity<?>> points = bigraph.getPointsFromLink(link);
            for (BigraphEntity<?> point : points) {
                if (point instanceof Port) {
                    queryHelper.findPortOwner((Port) point)
                        .filter(ownerNode -> !ownerNode.equals(node))
                        .ifPresent(neighborEntities::add);
                }
            }
        }

        List<Map<String, Object>> neighbors = neighborEntities.stream()
            .map(queryHelper::buildNodeSummary)
            .collect(Collectors.toList());

        List<Map<String, Object>> viaLinks = connectedLinks.stream()
            .map(l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", l.getName());
                m.put("gmodelId", queryHelper.findGModelIdForEntity(l).orElse(null));
                return m;
            })
            .collect(Collectors.toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeGmodelId", queryHelper.findGModelIdForEntity(node).orElse(null));
        payload.put("neighborCount", neighbors.size());
        payload.put("neighbors", neighbors);
        payload.put("viaLinks", viaLinks);

        return List.of(new AgentResponseAction(action.getRequestId(), payload));
    }
}
