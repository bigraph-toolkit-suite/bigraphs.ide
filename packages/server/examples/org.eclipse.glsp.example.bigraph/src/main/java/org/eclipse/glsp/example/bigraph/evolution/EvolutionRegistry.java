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

package org.eclipse.glsp.example.bigraph.evolution;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide singleton registry that tracks running evolution operations.
 * Other action handlers can look up an operation by id and request a pause.
 */
public final class EvolutionRegistry {

    private static final EvolutionRegistry INSTANCE = new EvolutionRegistry();

    private final ConcurrentHashMap<String, EvolutionOperation> operations = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeOperationId = new AtomicReference<>();

    private EvolutionRegistry() {}

    public static EvolutionRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers the operation only if no other evolution run is active.
     *
     * @return the operation id if registration succeeded, or {@code null} when another run is active
     */
    public String registerExclusive(final EvolutionOperation op) {
        final String id = op.getOperationId();
        if (!activeOperationId.compareAndSet(null, id)) {
            return null;
        }
        final EvolutionOperation previous = operations.putIfAbsent(id, op);
        if (previous != null) {
            activeOperationId.compareAndSet(id, null);
            throw new IllegalStateException("Duplicate evolution operation id: " + id);
        }
        return id;
    }

    /** Returns the operation for the given id, or {@code null} if not found. */
    public EvolutionOperation get(final String operationId) {
        return operations.get(operationId);
    }

    /** Removes the operation from the registry (called when it finishes or is cancelled). */
    public void remove(final String operationId) {
        operations.remove(operationId);
        activeOperationId.compareAndSet(operationId, null);
    }

    /** Requests a pause of the operation with the given id. */
    public boolean pause(final String operationId) {
        final EvolutionOperation op = operations.get(operationId);
        if (op == null) {
            return false;
        }
        op.requestPause();
        return true;
    }

    /** Returns the currently active operation id, or {@code null} if none is running. */
    public String getActiveOperationId() {
        final String id = activeOperationId.get();
        return id != null && operations.containsKey(id) ? id : null;
    }
}
