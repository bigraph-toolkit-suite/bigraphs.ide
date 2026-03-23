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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.eclipse.glsp.example.bigraph.actions.PublishEvolutionStateAction;
import org.eclipse.glsp.example.bigraph.model.BigraphIO;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;

import com.google.inject.Inject;

/**
 * Applies evolution publications on the GLSP dispatcher thread so shared model state changes stay serialized.
 */
public class PublishEvolutionStateActionHandler extends AbstractActionHandler<PublishEvolutionStateAction> {
    private static final Logger LOGGER = LogManager.getLogger(PublishEvolutionStateActionHandler.class);

    @Inject
    protected BigraphModelState modelState;

    @Override
    protected List<Action> executeAction(final PublishEvolutionStateAction action) {
        final PureBigraphMutable mutableBigraph = action.getMutableBigraph();
        if (mutableBigraph == null) {
            LOGGER.warn("Skipping evolution publication because no bigraph was supplied.");
            return none();
        }

        if (action.isPersistToWorkspace()) {
            persistFinalState(mutableBigraph, action.getCheckpointCursorId(), action.getEvolutionJsonPath());
        }

        final org.eclipse.glsp.graph.GModelRoot newRoot =
            modelState.initializeBigraphModel(mutableBigraph, modelState.getMetaInformation());
        return List.of(new UpdateModelAction(newRoot, false));
    }

    private void persistFinalState(final PureBigraphMutable mutableBigraph,
                                   final String checkpointCursorId,
                                   final String evolutionJsonPath) {
        final String sourceFilePath = modelState.getSourceFilePath();
        if (sourceFilePath == null || sourceFilePath.isBlank()) {
            throw new IllegalStateException("Cannot persist evolution result because the source file path is unknown.");
        }

        try {
            BigraphIO.writeToFile(mutableBigraph, new File(sourceFilePath));
            LOGGER.info("Wrote final evolution result to {}", sourceFilePath);

            if (checkpointCursorId != null && !checkpointCursorId.isBlank()
                && evolutionJsonPath != null && !evolutionJsonPath.isBlank()) {
                updateCheckpointCursor(checkpointCursorId, evolutionJsonPath);
            }
        } catch (final IOException e) {
            throw new RuntimeException("Failed to persist evolution result", e);
        }
    }

    private void updateCheckpointCursor(final String checkpointCursorId,
                                        final String evolutionJsonPath) throws IOException {
        final String json = new String(Files.readAllBytes(Paths.get(evolutionJsonPath)), StandardCharsets.UTF_8);
        final String updated = setCheckpointCursor(json, checkpointCursorId);
        Files.write(Paths.get(evolutionJsonPath), updated.getBytes(StandardCharsets.UTF_8));
    }

    private String setCheckpointCursor(final String json, final String id) {
        final String cursorKey = "\"checkpoint-cursor\"";
        final int idx = json.indexOf(cursorKey);
        if (idx >= 0) {
            final int colon = json.indexOf(":", idx);
            final int lineEnd = json.indexOf("\n", colon);
            final int end = lineEnd >= 0 ? lineEnd : json.length();
            return json.substring(0, colon + 1) + " \"" + id + "\"" + json.substring(end);
        }

        final int closingBrace = json.lastIndexOf("}");
        if (closingBrace < 0) {
            return json;
        }

        final String before = json.substring(0, closingBrace);
        final String trimmedBefore = before.stripTrailing();
        return trimmedBefore + ",\n    " + cursorKey + " : \"" + id + "\"\n}";
    }
}
