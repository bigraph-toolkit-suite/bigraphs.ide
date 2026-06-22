package org.eclipse.glsp.example.bigraph.handler;

import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.ControlSpec;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.addNode;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.emptyBigraph;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.firstRoot;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.initializedState;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.inject;
import static org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.signature;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.actions.EvolutionRunAction;
import org.eclipse.glsp.example.bigraph.actions.PublishEvolutionStateAction;
import org.eclipse.glsp.example.bigraph.evolution.EvolutionOperation;
import org.eclipse.glsp.example.bigraph.evolution.EvolutionRegistry;
import org.eclipse.glsp.example.bigraph.model.BigraphIO;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.RecordingActionDispatcher;
import org.eclipse.glsp.example.bigraph.testsupport.BigraphTestSupport.TestablePublishEvolutionStateActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.MessageAction;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;
import org.eclipse.glsp.server.types.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvolutionRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void publishEvolutionStateRefreshesModelWithoutPersistingIntermediateResults() throws Exception {
        DynamicSignature signature = signature(new ControlSpec("Room", 0));
        BigraphModelState state = initializedState(emptyBigraph(signature));
        Path workspaceFile = tempDir.resolve("workspace.xmi");
        Path evolutionJson = tempDir.resolve("evolution.json");
        Files.writeString(workspaceFile, "unchanged");
        Files.writeString(evolutionJson, "{\n  \"operations\" : []\n}\n");
        state.setSourceFilePath(workspaceFile.toString());

        PureBigraphMutable updated = emptyBigraph(signature);
        addNode(updated, firstRoot(updated), "Room", "room");

        TestablePublishEvolutionStateActionHandler handler = new TestablePublishEvolutionStateActionHandler();
        inject(handler, "modelState", state);

        List<Action> actions = handler.executeForTest(
            new PublishEvolutionStateAction(updated, false, "cursor-1", evolutionJson.toString()));

        assertEquals(1, state.getMutableBigraph().getNodes().size());
        assertEquals(1, actions.size());
        assertTrue(actions.get(0) instanceof UpdateModelAction);
        assertEquals("unchanged", Files.readString(workspaceFile));
        assertEquals("{\n  \"operations\" : []\n}\n", Files.readString(evolutionJson));
    }

    @Test
    void publishEvolutionStatePersistsFinalStateAndCheckpointCursor() throws Exception {
        DynamicSignature signature = signature(new ControlSpec("Room", 0));
        BigraphModelState state = initializedState(emptyBigraph(signature));
        Path workspaceFile = tempDir.resolve("workspace.xmi");
        Path evolutionJson = tempDir.resolve("evolution.json");
        Files.writeString(evolutionJson, "{\n  \"operations\" : []\n}\n");
        state.setSourceFilePath(workspaceFile.toString());

        PureBigraphMutable updated = emptyBigraph(signature);
        addNode(updated, firstRoot(updated), "Room", "room");
        BigraphIO.writeSignatureToFile(updated.getSignature(), workspaceFile.toString());

        TestablePublishEvolutionStateActionHandler handler = new TestablePublishEvolutionStateActionHandler();
        inject(handler, "modelState", state);

        List<Action> actions = handler.executeForTest(
            new PublishEvolutionStateAction(updated, true, "cursor-42", evolutionJson.toString()));

        assertEquals(1, actions.size());
        assertTrue(actions.get(0) instanceof UpdateModelAction);
        assertTrue(Files.size(workspaceFile) > 0L);
        assertEquals(1, BigraphIO.parseBigraphFromFile(workspaceFile.toFile()).getNodes().size());
        assertTrue(Files.readString(evolutionJson).contains("\"checkpoint-cursor\" : \"cursor-42\""));
    }

    @Test
    void evolutionRunHandlerRejectsStartingAnotherRunWhileOneIsActive() {
        EvolutionRegistry registry = EvolutionRegistry.getInstance();
        clearRegistry(registry);
        registry.registerExclusive(new EvolutionOperation("busy-op", "play"));

        RecordingActionDispatcher dispatcher = new RecordingActionDispatcher();
        EvolutionRunActionHandler handler = new EvolutionRunActionHandler();
        inject(handler, "actionDispatcher", dispatcher);

        EvolutionRunAction action = new EvolutionRunAction();
        inject(action, "actionType", "play");
        inject(action, "rules", List.of(ruleRef("rule-1")));

        List<Action> actions = handler.executeAction(action);

        assertTrue(actions.isEmpty());
        MessageAction warning = dispatcher.dispatchedOfType(MessageAction.class).stream()
            .findFirst()
            .orElseThrow();
        assertEquals(Severity.toString(Severity.WARNING), warning.getSeverity());
        assertTrue(warning.getMessage().contains("Another evolution run is already active"));

        registry.remove("busy-op");
    }

    private EvolutionRunAction.RuleRef ruleRef(final String id) {
        EvolutionRunAction.RuleRef ruleRef = new EvolutionRunAction.RuleRef();
        inject(ruleRef, "id", id);
        inject(ruleRef, "redex", tempDir.resolve(id + "-redex.xmi").toString());
        inject(ruleRef, "reactum", tempDir.resolve(id + "-reactum.xmi").toString());
        return ruleRef;
    }

    private void clearRegistry(final EvolutionRegistry registry) {
        String activeId = registry.getActiveOperationId();
        if (activeId != null) {
            registry.remove(activeId);
        }
    }
}
