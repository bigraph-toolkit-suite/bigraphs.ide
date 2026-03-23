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

package org.eclipse.glsp.example.bigraph.views.graph;

import java.util.Map;
import java.util.Optional;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.Port;

/**
 * Centralizes lookup policy for registry-backed ids.
 *
 * Extracted from GraphBigraphView to reduce complexity in the main view.
 */
final class GraphRegistryLookups {
    private final Map<String, BigraphEntity<?>> gModelIdToEntity;
    private final Map<BigraphEntity<?>, String> entityToGModelId;
    private final Map<Port, String> portToNodeGModelId;

    GraphRegistryLookups(final Map<String, BigraphEntity<?>> gModelIdToEntity,
            final Map<BigraphEntity<?>, String> entityToGModelId,
            final Map<Port, String> portToNodeGModelId) {
        this.gModelIdToEntity = gModelIdToEntity;
        this.entityToGModelId = entityToGModelId;
        this.portToNodeGModelId = portToNodeGModelId;
    }

    Optional<BigraphEntity<?>> getBigraphEntityForGModelId(final String gModelId) {
        return Optional.ofNullable(gModelIdToEntity.get(gModelId));
    }

    Optional<String> getGModelIdForEntity(final BigraphEntity<?> entity) {
        return Optional.ofNullable(entityToGModelId.get(entity));
    }

    Optional<String> getNodeGModelIdForPort(final Port port) {
        return Optional.ofNullable(portToNodeGModelId.get(port));
    }

    // Kept for compatibility with existing call sites that expect NodeEntity typing.
    @SuppressWarnings("unused")
    Optional<String> getNodeGModelIdForEntity(final NodeEntity<?> nodeEntity) {
        return getGModelIdForEntity(nodeEntity);
    }
}

