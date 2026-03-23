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

package org.eclipse.glsp.example.bigraph.model;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;

/**
 * Provides a stable, persisted identity for bigraph nodes.
 *
 * Node names are user-editable labels, so they cannot safely serve as lookup
 * keys for the GModel or layout persistence.
 */
public final class BigraphNodeIdentity {

    public static final String ATTRIBUTE_KEY = "glspNodeId";
    public static final String GMODEL_ARG_KEY = ATTRIBUTE_KEY;

    private BigraphNodeIdentity() {}

    public static Optional<String> getStableId(final NodeEntity<?> node) {
        if (node == null || node.getAttributes() == null) {
            return Optional.empty();
        }

        final Object rawValue = node.getAttributes().get(ATTRIBUTE_KEY);
        if (rawValue == null) {
            return Optional.empty();
        }

        final String stableId = rawValue.toString().trim();
        return stableId.isEmpty() ? Optional.empty() : Optional.of(stableId);
    }

    public static String getOrCreateStableId(final NodeEntity<?> node) {
        return getStableId(node).orElseGet(() -> {
            final String generatedId = UUID.randomUUID().toString();
            // Bigraph's NodeEntity.getAttributes() returns a map view/copy backed by EMF.
            // To guarantee persistence, we must write back via setAttributes(...).
            final Map<String, Object> attributes = attributesOf(node);
            attributes.put(ATTRIBUTE_KEY, generatedId);
            node.setAttributes(attributes);
            return generatedId;
        });
    }

    public static String toGModelId(final NodeEntity<?> node) {
        return toGModelId(getOrCreateStableId(node));
    }

    public static String toGModelId(final String stableId) {
        return "node_" + stableId;
    }

    /**
     * Key for persisting node positions in the {@code .bigraph-meta} file.
     *
     * Important: this must be property-based (not ID-based) because in our current
     * runtime the node UUID changes between editor opens.
     */
    public static String toNodePositionKey(final NodeEntity<?> node, final DynamicControl control) {
        final String nodeName = node != null && node.getName() != null ? node.getName() : "";
        final String controlName = control != null && control.getNamedType() != null
            ? control.getNamedType().stringValue()
            : "";
        return toNodePositionKey(nodeName, controlName);
    }

    /**
     * Property-based node position key (used for meta-file persistence).
     */
    public static String toNodePositionKey(final String nodeName, final String controlName) {
        final String safeNodeName = nodeName != null ? nodeName : "";
        final String safeControlName = controlName != null ? controlName : "";
        return safeNodeName + "|" + safeControlName;
    }

    private static Map<String, Object> attributesOf(final NodeEntity<?> node) {
        final Map<String, Object> attributes = node.getAttributes();
        if (attributes == null) {
            throw new IllegalStateException("Node attributes are not initialized");
        }
        return attributes;
    }
}
