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

package org.eclipse.glsp.example.bigraph.actions;

import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.eclipse.glsp.server.actions.Action;

/**
 * Internal server-side action that publishes an evolution result on the GLSP dispatcher thread.
 */
public class PublishEvolutionStateAction extends Action {
    public static final String KIND = "bigraph.internal.publishEvolutionState";

    private PureBigraphMutable mutableBigraph;
    private boolean persistToWorkspace;
    private String checkpointCursorId;
    private String evolutionJsonPath;

    public PublishEvolutionStateAction() {
        super(KIND);
    }

    public PublishEvolutionStateAction(
        final PureBigraphMutable mutableBigraph,
        final boolean persistToWorkspace,
        final String checkpointCursorId,
        final String evolutionJsonPath
    ) {
        super(KIND);
        this.mutableBigraph = mutableBigraph;
        this.persistToWorkspace = persistToWorkspace;
        this.checkpointCursorId = checkpointCursorId;
        this.evolutionJsonPath = evolutionJsonPath;
    }

    public PureBigraphMutable getMutableBigraph() {
        return mutableBigraph;
    }

    public boolean isPersistToWorkspace() {
        return persistToWorkspace;
    }

    public String getCheckpointCursorId() {
        return checkpointCursorId;
    }

    public String getEvolutionJsonPath() {
        return evolutionJsonPath;
    }
}
