package org.eclipse.glsp.example.bigraph.handler;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.bigraphWithOneNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.inject;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.initializedState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.eclipse.glsp.example.bigraph.actions.PublishEvolutionStateAction;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishEvolutionStateActionHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void intermediatePublicationRefreshesModelOnly() throws Exception {
        PureBigraphMutable bigraph = bigraphWithOneNode("Agent", "n1");
        BigraphModelState state = initializedState(bigraph);
        var handler = new org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.TestablePublishEvolutionStateActionHandler();
        inject(handler, "modelState", state);

        var action = new PublishEvolutionStateAction(bigraph, false, null, null);
        var result = handler.executeForTest(action);

        assertEquals(1, result.size());
        Action a = result.get(0);
        assertTrue(a instanceof UpdateModelAction);
        assertNotNull(((UpdateModelAction) a).getNewRoot());
    }

    @Test
    void finalPublicationPersistsFileAndUpdatesCheckpointCursor() throws Exception {
        Path xmiPath = tempDir.resolve("workspace.xmi");
        org.eclipse.glsp.example.bigraph.model.BigraphIO.createEmptyBigraphFile(xmiPath.toFile());
        Path evoJsonPath = tempDir.resolve("evolution.json");
        Files.writeString(evoJsonPath, "{\n  \"checkpoint-cursor\" : \"old-id\"\n}");

        PureBigraphMutable bigraph = bigraphWithOneNode("Agent", "evolved");
        BigraphModelState state = initializedState(bigraph);
        state.setSourceFilePath(xmiPath.toAbsolutePath().toString());

        var handler = new org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.TestablePublishEvolutionStateActionHandler();
        inject(handler, "modelState", state);

        String newCursorId = "new-checkpoint-123";
        var action = new PublishEvolutionStateAction(bigraph, true, newCursorId, evoJsonPath.toString());
        handler.executeForTest(action);

        assertTrue(Files.exists(xmiPath));
        String evoContent = Files.readString(evoJsonPath, StandardCharsets.UTF_8);
        assertTrue(evoContent.contains(newCursorId));
    }
}
