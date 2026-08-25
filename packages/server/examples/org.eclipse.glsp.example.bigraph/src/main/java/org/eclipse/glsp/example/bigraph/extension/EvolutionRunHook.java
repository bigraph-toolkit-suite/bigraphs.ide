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

package org.eclipse.glsp.example.bigraph.extensions;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.eclipse.glsp.example.bigraph.actions.EvolutionRunAction;

/**
 * Strategy contributed by an {@link IdeExtension} that replaces the core
 * evolution loop for runs it claims (e.g. exploration, operation replay).
 *
 * <p>The core {@code EvolutionRunActionHandler} asks every registered
 * extension for a hook before starting a run; the first hook whose
 * {@link #claims(EvolutionRunAction)} returns {@code true} takes over.
 * Everything around the loop — thread management, pause bookkeeping,
 * signature merging, final-state commit, finished notification — stays
 * in the core.</p>
 */
public interface EvolutionRunHook {

    /**
     * Whether this hook wants to drive the given run. Implementations
     * typically check their entry in
     * {@link EvolutionRunAction#getExtensionOptions()}.
     */
    boolean claims(EvolutionRunAction action);

    /**
     * Drives the run. Implementations apply rules via {@link EvolutionRunApi}
     * and must honour {@link EvolutionRunApi#shouldPause()} and
     * {@link EvolutionRunApi#getMaxOperations()}.
     *
     * @param initial the loaded workspace bigraph (rebased onto the merged signature)
     * @param api     core services for this run
     * @return the bigraph to commit as the final state (may be {@code initial})
     * @throws Exception any failure — the core marks the operation as failed
     */
    PureBigraph run(PureBigraph initial, EvolutionRunApi api) throws Exception;
}
