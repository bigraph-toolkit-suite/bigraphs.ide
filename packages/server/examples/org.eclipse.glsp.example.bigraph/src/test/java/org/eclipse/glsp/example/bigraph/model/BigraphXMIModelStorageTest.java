package org.eclipse.glsp.example.bigraph.model;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.createValidXmiFixture;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.inject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.RecordingActionDispatcher;
import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.TestableBigraphXMIModelStorage;
import org.eclipse.glsp.server.actions.MessageAction;
import org.eclipse.glsp.server.types.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BigraphXMIModelStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void loadSourceModelFallsBackToEmptyBigraphOnLoadFailure() throws Exception {
        Path invalidInput = tempDir.resolve("diagram.signature.xmi");
        Files.writeString(invalidInput, "<signature/>");

        BigraphModelState state = new BigraphModelState();
        state.init();
        RecordingActionDispatcher dispatcher = new RecordingActionDispatcher();
        TestableBigraphXMIModelStorage storage = new TestableBigraphXMIModelStorage();
        inject(storage, "modelState", state);
        inject(storage, "actionDispatcher", dispatcher);

        var root = storage.loadForTest(invalidInput.toFile(), state);

        assertTrue(root.isPresent());
        assertNotNull(state.getMutableBigraph());
        assertEquals(1, state.getMutableBigraph().getRoots().size());
        assertNull(state.getSourceFilePath());
        assertNull(state.getPendingSignature());

        MessageAction error = dispatcher.dispatchedOfType(MessageAction.class).stream()
            .findFirst()
            .orElseThrow();
        assertEquals(Severity.toString(Severity.ERROR), error.getSeverity());
        assertTrue(error.getMessage().contains("Cannot open signature files directly"));
    }

    @Test
    void loadSourceModelRecreatesMissingCompanionSignatureFiles() throws Exception {
        Path bigraphPath = tempDir.resolve("workspace.xmi");
        BigraphIO.createEmptyBigraphFile(bigraphPath.toFile());
        Files.delete(tempDir.resolve("workspace.signature.ecore"));
        Files.delete(tempDir.resolve("workspace.signature.xmi"));

        BigraphModelState state = new BigraphModelState();
        state.init();
        RecordingActionDispatcher dispatcher = new RecordingActionDispatcher();
        TestableBigraphXMIModelStorage storage = new TestableBigraphXMIModelStorage();
        inject(storage, "modelState", state);
        inject(storage, "actionDispatcher", dispatcher);

        var root = storage.loadForTest(bigraphPath.toFile(), state);

        assertTrue(root.isPresent());
        assertEquals(bigraphPath.toAbsolutePath().toString(), state.getSourceFilePath());
        assertTrue(Files.exists(tempDir.resolve("workspace.signature.ecore")));
        assertTrue(Files.exists(tempDir.resolve("workspace.signature.xmi")));
        assertTrue(dispatcher.dispatchedOfType(MessageAction.class).isEmpty());
    }

    @Test
    void loadSourceModelFallsBackOnCorruptXmiContent() throws Exception {
        Path corruptPath = createValidXmiFixture(tempDir, "corrupt");
        Files.writeString(corruptPath, "corrupt content that is not valid XMI");

        BigraphModelState state = new BigraphModelState();
        state.init();
        RecordingActionDispatcher dispatcher = new RecordingActionDispatcher();
        TestableBigraphXMIModelStorage storage = new TestableBigraphXMIModelStorage();
        inject(storage, "modelState", state);
        inject(storage, "actionDispatcher", dispatcher);

        var root = storage.loadForTest(corruptPath.toFile(), state);

        assertTrue(root.isPresent());
        assertNotNull(state.getMutableBigraph());
        assertEquals(1, state.getMutableBigraph().getRoots().size());
        assertNull(state.getSourceFilePath());
        assertFalse(dispatcher.dispatchedOfType(MessageAction.class).isEmpty());
    }
}
