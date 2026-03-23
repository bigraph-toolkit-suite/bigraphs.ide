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

package org.eclipse.glsp.example.bigraph.handler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.Edge;
import org.bigraphs.framework.core.impl.BigraphEntity.InnerName;
import org.bigraphs.framework.core.impl.BigraphEntity.Link;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.OuterName;
import org.bigraphs.framework.core.impl.BigraphEntity.Port;
import org.bigraphs.framework.core.impl.BigraphEntity.RootEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.SiteEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.example.bigraph.model.BigraphNodeIdentity;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.graph.GNode;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Central helper for resolving GModel IDs to BigraphEntities and querying
 * the bigraph model. Used by all agent-facing ActionHandlers.
 */
@Singleton
public class BigraphQueryHelper {

    @Inject
    protected BigraphModelState modelState;

    /**
     * Find a GModel element by its ID, searching recursively through the GModelRoot.
     */
    public Optional<GModelElement> findGModelElement(final String gmodelId) {
        GModelRoot root = modelState.getRoot();
        if (root == null) {
            return Optional.empty();
        }
        return findRecursive(root, gmodelId);
    }

    private Optional<GModelElement> findRecursive(final GModelElement element, final String id) {
        if (element.getId().equals(id)) {
            return Optional.of(element);
        }
        for (GModelElement child : element.getChildren()) {
            Optional<GModelElement> found = findRecursive(child, id);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve a GModel ID to a BigraphEntity using the active view's mapping,
     * falling back to a structural search if necessary.
     */
    public Optional<BigraphEntity<?>> resolveEntity(final String gmodelId) {
        if (gmodelId == null || gmodelId.isBlank()) {
            return Optional.empty();
        }

        final String lookupId = gmodelId.trim();
        Optional<BigraphEntity<?>> fromView = modelState.getActiveView()
            .getBigraphEntityForGModelId(lookupId);
        if (fromView.isPresent()) {
            return fromView;
        }

        PureBigraphMutable bigraph = modelState.getMutableBigraph();
        Optional<BigraphEntity<?>> byStableIdentity = resolveNodeByStableIdentity(lookupId, bigraph);
        if (byStableIdentity.isPresent()) {
            return byStableIdentity;
        }

        return resolveByIteration(lookupId);
    }

    @SuppressWarnings("unused")
    private Optional<BigraphEntity<?>> resolveByIteration(final String gmodelId) {
        Optional<GModelElement> gElement = findGModelElement(gmodelId);
        if (gElement.isEmpty()) {
            return Optional.empty();
        }
        GModelElement el = gElement.get();
        String type = el.getType();
        PureBigraphMutable bigraph = modelState.getMutableBigraph();
        if (bigraph == null) {
            return Optional.empty();
        }

        if (BigraphModelTypes.BIGRAPH_ROOT.equals(type)) {
            String label = getArg(el, "label");
            int index = parseIndex(label, "R");
            if (index < 0) {
                return Optional.empty();
            }
            return Optional.ofNullable(bigraph.getRoots().stream()
                .filter(r -> r.getIndex() == index)
                .map(r -> (BigraphEntity<?>) r)
                .findFirst().orElse(null));
        }
        if (BigraphModelTypes.SITE.equals(type)) {
            String label = getArg(el, "label");
            int index = parseIndex(label, "S");
            if (index < 0) {
                return Optional.empty();
            }
            return Optional.ofNullable(bigraph.getSites().stream()
                .filter(s -> s.getIndex() == index)
                .map(s -> (BigraphEntity<?>) s)
                .findFirst().orElse(null));
        }
        if (BigraphModelTypes.BIGRAPH_NODE.equals(type)) {
            String stableNodeId = getArg(el, BigraphNodeIdentity.GMODEL_ARG_KEY);
            if (stableNodeId != null) {
                Optional<BigraphEntity<?>> byStableId = resolveNodeByStableIdentity(stableNodeId, bigraph);
                if (byStableId.isPresent()) {
                    return byStableId;
                }
            }

            String name = getArg(el, "name");
            if (name == null) {
                String label = getArg(el, "label");
                name = label != null && label.contains(":") ? label.split(":")[0] : label;
            }
            final String nodeName = name;
            if (nodeName == null || nodeName.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(bigraph.getNodes().stream()
                .filter(n -> nodeName.equals(n.getName()))
                .map(n -> (BigraphEntity<?>) n)
                .findFirst().orElse(null));
        }
        if (BigraphModelTypes.HYPEREDGE.equals(type)) {
            String name = getArg(el, "name");
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(bigraph.getEdges().stream()
                .filter(e -> e.getName().equals(name))
                .map(e -> (BigraphEntity<?>) e)
                .findFirst().orElse(null));
        }
        if (BigraphModelTypes.OUTER_NAME.equals(type)) {
            String name = getArg(el, "name");
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(bigraph.getOuterNames().stream()
                .filter(outerName -> name.equals(outerName.getName()))
                .map(outerName -> (BigraphEntity<?>) outerName)
                .findFirst().orElse(null));
        }
        if (BigraphModelTypes.INNER_NAME.equals(type)) {
            String name = getArg(el, "name");
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(bigraph.getInnerNames().stream()
                .filter(innerName -> name.equals(innerName.getName()))
                .map(innerName -> (BigraphEntity<?>) innerName)
                .findFirst().orElse(null));
        }
        return Optional.empty();
    }

    /**
     * Build a summary DTO for a bigraph entity, including its GModel ID.
     */
    public Map<String, Object> buildNodeSummary(final BigraphEntity<?> entity) {
        PureBigraphMutable bigraph = modelState.getMutableBigraph();
        Map<String, Object> summary = new LinkedHashMap<>();

        final Optional<String> gmodelId = findGModelIdForEntity(entity);
        putOptionalId(summary, "gmodelId", gmodelId);

        if (entity instanceof RootEntity) {
            RootEntity root = (RootEntity) entity;
            summary.put("type", "root");
            summary.put("label", "R" + root.getIndex());
            summary.put("childCount", bigraph.getChildrenOf(root).size());
        } else if (entity instanceof NodeEntity) {
            @SuppressWarnings("unchecked")
            NodeEntity<DynamicControl> node = (NodeEntity<DynamicControl>) entity;
            summary.put("type", "node");
            summary.put("name", node.getName());
            summary.put("stableId", BigraphNodeIdentity.getStableId(node)
                .orElseThrow(() -> new IllegalStateException(
                    "Missing glspNodeId on NodeEntity while building node summary: " + node.getName())));
            summary.put("controlType", node.getControl().getNamedType().stringValue());
            summary.put("arity", node.getControl().getArity().getValue());
            summary.put("childCount", bigraph.getChildrenOf(node).size());
            summary.put("label", node.getName() + ":" + node.getControl().getNamedType().stringValue());
        } else if (entity instanceof SiteEntity) {
            SiteEntity site = (SiteEntity) entity;
            summary.put("type", "site");
            summary.put("label", "S" + site.getIndex());
            summary.put("index", site.getIndex());
            summary.put("childCount", bigraph.getChildrenOf(site).size());
        }

        return summary;
    }

    /**
     * Build detailed info for a node, including ports and link information.
     */
    public Map<String, Object> buildNodeDetail(final BigraphEntity<?> entity) {
        PureBigraphMutable bigraph = modelState.getMutableBigraph();
        Map<String, Object> detail = new LinkedHashMap<>(buildNodeSummary(entity));

        BigraphEntity<?> parent = bigraph.getParent(entity);
        if (parent != null) {
            putOptionalId(detail, "parentGmodelId", findGModelIdForEntity(parent));
            detail.put("parentLabel", getLabelForEntity(parent));
        }

        Collection<BigraphEntity<?>> children = bigraph.getChildrenOf(entity);
        List<Map<String, Object>> childSummaries = children.stream()
            .map(this::buildNodeSummary)
            .collect(Collectors.toList());
        detail.put("children", childSummaries);

        if (entity instanceof NodeEntity) {
            @SuppressWarnings("unchecked")
            NodeEntity<DynamicControl> node = (NodeEntity<DynamicControl>) entity;
            List<Map<String, Object>> portInfos = new ArrayList<>();
            List<Port> ports = bigraph.getPorts(node);
            for (Port port : ports) {
                Map<String, Object> portInfo = new LinkedHashMap<>();
                portInfo.put("index", port.getIndex());
                Link link = bigraph.getLinkOfPoint(port);
                if (link != null) {
                    portInfo.put("linkName", link.getName());
                    putOptionalId(portInfo, "linkGmodelId", findGModelIdForEntity(link));
                } else {
                    portInfo.put("linkName", null);
                    portInfo.put("linkGmodelId", null);
                }
                portInfos.add(portInfo);
            }
            detail.put("ports", portInfos);
        }

        List<Map<String, String>> path = new ArrayList<>();
        BigraphEntity<?> current = entity;
        while (current != null) {
            Map<String, String> step = new LinkedHashMap<>();
            step.put("gmodelId", findGModelIdForEntity(current).orElse(null));
            step.put("label", getLabelForEntity(current));
            path.add(0, step);
            current = bigraph.getParent(current);
        }
        detail.put("path", path);

        return detail;
    }

    /**
     * Find the GModel ID for a given BigraphEntity using the active view's mapping
     * when available, falling back to a search in the GModel if necessary.
     */
    public Optional<String> findGModelIdForEntity(final BigraphEntity<?> entity) {
        Optional<String> fromView = modelState.getActiveView().getGModelIdForEntity(entity);
        if (fromView.isPresent()) {
            return fromView;
        }
        return findGModelIdBySearch(entity);
    }

    private Optional<String> findGModelIdBySearch(final BigraphEntity<?> entity) {
        GModelRoot root = modelState.getRoot();
        if (root == null) {
            return Optional.empty();
        }
        List<GModelElement> all = collectAllElements(root);

        if (entity instanceof RootEntity) {
            int index = ((RootEntity) entity).getIndex();
            String label = "R" + index;
            return findElementIdByTypeAndArg(all, BigraphModelTypes.BIGRAPH_ROOT, "label", label);
        }
        if (entity instanceof NodeEntity) {
            NodeEntity<?> node = (NodeEntity<?>) entity;
            return BigraphNodeIdentity.getStableId(node)
                .flatMap(stableNodeId -> findElementIdByTypeAndArg(
                    all, BigraphModelTypes.BIGRAPH_NODE, BigraphNodeIdentity.GMODEL_ARG_KEY, stableNodeId));
        }
        if (entity instanceof SiteEntity) {
            int index = ((SiteEntity) entity).getIndex();
            String label = "S" + index;
            return findElementIdByTypeAndArg(all, BigraphModelTypes.SITE, "label", label);
        }
        if (entity instanceof Edge) {
            String name = ((Edge) entity).getName();
            return findElementIdByTypeAndArg(all, BigraphModelTypes.HYPEREDGE, "name", name);
        }
        if (entity instanceof OuterName) {
            String name = ((OuterName) entity).getName();
            return findElementIdByTypeAndArg(all, BigraphModelTypes.OUTER_NAME, "name", name);
        }
        if (entity instanceof InnerName) {
            String name = ((InnerName) entity).getName();
            return findElementIdByTypeAndArg(all, BigraphModelTypes.INNER_NAME, "name", name);
        }
        return Optional.empty();
    }

    private String getLabelForEntity(final BigraphEntity<?> entity) {
        if (entity instanceof RootEntity) {
            return "R" + ((RootEntity) entity).getIndex();
        }
        if (entity instanceof NodeEntity) {
            NodeEntity<DynamicControl> node = (NodeEntity<DynamicControl>) entity;
            return node.getName() + ":" + node.getControl().getNamedType().stringValue();
        }
        if (entity instanceof SiteEntity) {
            return "S" + ((SiteEntity) entity).getIndex();
        }
        if (entity instanceof Edge) {
            return ((Edge) entity).getName();
        }
        if (entity instanceof OuterName) {
            return ((OuterName) entity).getName();
        }
        if (entity instanceof InnerName) {
            return ((InnerName) entity).getName();
        }
        return entity != null ? entity.getClass().getSimpleName() : null;
    }

    public String getArg(final GModelElement el, final String key) {
        if (el.getArgs() == null) {
            return null;
        }
        Object val = el.getArgs().get(key);
        return val != null ? val.toString() : null;
    }

    public Optional<String> findOwnerNodeGModelIdForPort(final Port port) {
        return findPortOwner(port).flatMap(this::findGModelIdForEntity);
    }

    public Optional<NodeEntity<DynamicControl>> findPortOwner(final Port port) {
        PureBigraphMutable bigraph = modelState.getMutableBigraph();
        if (bigraph == null || port == null) {
            return Optional.empty();
        }

        return bigraph.getNodes().stream()
            .filter(node -> bigraph.getPorts(node).stream().anyMatch(port::equals))
            .findFirst();
    }

    private int parseIndex(final String label, final String prefix) {
        if (label == null) {
            return -1;
        }
        try {
            return Integer.parseInt(label.replace(prefix, "").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private List<GModelElement> collectAllElements(final GModelElement root) {
        List<GModelElement> result = new ArrayList<>();
        result.add(root);
        for (GModelElement child : root.getChildren()) {
            result.addAll(collectAllElements(child));
        }
        return result;
    }

    private Optional<BigraphEntity<?>> resolveNodeByStableIdentity(final String candidate,
                                                                   final PureBigraphMutable bigraph) {
        if (bigraph == null || candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(bigraph.getNodes().stream()
            .filter(node -> BigraphNodeIdentity.getStableId(node)
                .filter(stableId -> BigraphNodeIdentity.toGModelId(stableId).equals(candidate))
                .isPresent())
            .map(node -> (BigraphEntity<?>) node)
            .findFirst()
            .orElse(null));
    }

    private Optional<String> findElementIdByTypeAndArg(final List<GModelElement> elements,
                                                       final String type,
                                                       final String argKey,
                                                       final String argValue) {
        if (argValue == null || argValue.isBlank()) {
            return Optional.empty();
        }

        return elements.stream()
            .filter(el -> type.equals(el.getType()))
            .filter(el -> argValue.equals(getArg(el, argKey)))
            .map(GModelElement::getId)
            .findFirst();
    }

    private void putOptionalId(final Map<String, Object> target,
                               final String key,
                               final Optional<String> value) {
        target.put(key, value.orElse(null));
    }

}
