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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.Edge;
import org.bigraphs.framework.core.impl.BigraphEntity.InnerName;
import org.bigraphs.framework.core.impl.BigraphEntity.Link;
import org.bigraphs.framework.core.impl.BigraphEntity.OuterName;
import org.bigraphs.framework.core.impl.BigraphEntity.Port;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.eclipse.glsp.example.bigraph.actions.agent.AgentResponseAction;
import org.eclipse.glsp.example.bigraph.actions.agent.RequestBigraphLinksAction;
import org.eclipse.glsp.example.bigraph.handler.BigraphQueryHelper;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import com.google.inject.Inject;

public class BigraphLinksActionHandler extends AbstractActionHandler<RequestBigraphLinksAction> {

    @Inject
    protected BigraphModelState modelState;

    @Inject
    protected BigraphQueryHelper queryHelper;

    @Override
    public List<Action> executeAction(final RequestBigraphLinksAction action) {
        PureBigraphMutable bigraph = modelState.getMutableBigraph();
        if (bigraph == null) {
            return List.of(new AgentResponseAction(action.getRequestId(),
                Map.of("totalLinks", 0, "links", List.of())));
        }

        List<Map<String, Object>> links = new ArrayList<>();

        for (Edge edge : bigraph.getEdges()) {
            links.add(buildLinkInfo(bigraph, edge, "edge"));
        }
        for (OuterName outerName : bigraph.getOuterNames()) {
            links.add(buildLinkInfo(bigraph, outerName, "outerName"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalLinks", links.size());
        payload.put("links", links);

        return List.of(new AgentResponseAction(action.getRequestId(), payload));
    }

    private Map<String, Object> buildLinkInfo(
        final PureBigraphMutable bigraph,
        final Link link,
        final String linkType) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", link.getName());
        info.put("linkType", linkType);
        info.put("gmodelId", queryHelper.findGModelIdForEntity(link).orElse(null));

        Collection<BigraphEntity<?>> points = bigraph.getPointsFromLink(link);
        List<Map<String, Object>> connectedPoints = new ArrayList<>();

        for (BigraphEntity<?> point : points) {
            if (point instanceof Port) {
                Port port = (Port) point;
                Map<String, Object> portEntry = new LinkedHashMap<>();
                portEntry.put("type", "port");
                portEntry.put("portIndex", port.getIndex());
                portEntry.put("ownerNodeGmodelId", queryHelper.findOwnerNodeGModelIdForPort(port).orElse(null));
                connectedPoints.add(portEntry);
            } else if (point instanceof InnerName) {
                InnerName inner = (InnerName) point;
                Map<String, Object> innerEntry = new LinkedHashMap<>();
                innerEntry.put("type", "innerName");
                innerEntry.put("name", inner.getName());
                innerEntry.put("gmodelId", queryHelper.findGModelIdForEntity(inner).orElse(null));
                connectedPoints.add(innerEntry);
            }
        }

        info.put("connectedPoints", connectedPoints);
        return info;
    }
}
