package org.eclipse.glsp.example.bigraph.extension.popp.validation;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSynchronizer;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPTestSession;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.ValidateModelAction;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.ValidationResultAction;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class POPPValidationServiceTest {

    /**
     * GLSP instantiates every action handler, and with it this service, when a client session starts, before any
     * model is loaded. At that point the synchronizer cannot be constructed (no bigraph yet), so the service must not
     * create it eagerly. Regression test for the session-start NullPointerException.
     */
    @Test
    void creatingTheServiceDoesNotCreateTheSynchronizerOrTheDispatcher() {
        Injector injector = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(POPPExtensionContext.class).toProvider(() -> {
                    throw new AssertionError("context must not be needed to construct the service");
                });
                bind(POPPBigraphSynchronizer.class).toProvider(() -> {
                    throw new AssertionError("synchronizer created at session start");
                });
                bind(ActionDispatcher.class).toProvider(() -> {
                    throw new AssertionError("dispatcher resolved at session start");
                });
            }
        });
        // the context is a plain constructor argument (it exists at session start); the other two must stay lazy
        assertNotNull(new POPPValidationService(null,
                injector.getProvider(POPPBigraphSynchronizer.class),
                injector.getProvider(ActionDispatcher.class)));
    }

    // -- the service in a session ----------------------------------------------------------------------------

    private static POPPTestSession settled() {
        var session = new POPPTestSession();
        // loading schedules one run; wait for it so the tests below see only their own
        assertTrue(session.dispatcher.await(ValidateModelAction.class,
                a -> true, 3000).isPresent(), "loading schedules a validation");
        session.dispatcher.clear();
        return session;
    }

    @Test
    void runNowValidatesTheCurrentBigraphAndPushesTheResult() {
        var session = settled();
        var goal = session.model().createNode(NodeKind.GOAL, "goal", 0, 0);
        var g = session.bigraph;
        var container = session.popp().findPOPPContainer(session.popp().getDefaultRoot());
        g.addNode(container, session.popp().getSignature().getControlByName("GoalOr"), "stray");

        session.validationService.runNow();

        var result = session.dispatcher.await(
                ValidationResultAction.class,
                a -> !a.getReport().valid(), 5000).orElseThrow();
        assertEquals(session.model().getVersion(), result.getReport().modelVersion());
        assertEquals("CHAIN_OUTSIDE_DECOMPOSITION", result.getReport().issues().getFirst().code());
        assertEquals("ERROR", result.getReport().issues().getFirst().severity());
        assertNotNull(goal);
    }

    @Test
    void withoutAModelRunNowDoesNothing() throws InterruptedException {
        var session = POPPTestSession.withoutModel();
        session.validationService.runNow();
        Thread.sleep(300);
        assertTrue(session.dispatcher.dispatched(
                ValidationResultAction.class).isEmpty());
    }

    @Test
    void aBurstOfStructuralEditsSchedulesOneRunAfterItSettles() throws InterruptedException {
        var session = settled();
        var model = session.model();
        for (int i = 0; i < 5; i++) {
            model.createNode(NodeKind.GOAL, "g" + i, 0, 0);
        }

        assertTrue(session.dispatcher.await(ValidateModelAction.class,
                a -> true, 3000).isPresent());
        Thread.sleep(2 * POPPValidationService.DEBOUNCE_MS);
        assertEquals(1, session.dispatcher.dispatched(
                ValidateModelAction.class).size());
    }

    @Test
    void movesAndDescriptionEditsDoNotScheduleARun() throws InterruptedException {
        var session = settled();
        var goal = session.model().createNode(NodeKind.GOAL, "g", 0, 0);
        session.dispatcher.await(ValidateModelAction.class,
                a -> true, 3000);
        session.dispatcher.clear();

        goal.move(10, 10);
        goal.setDescription("renamed");
        Thread.sleep(3 * POPPValidationService.DEBOUNCE_MS);

        assertTrue(session.dispatcher.dispatched(
                ValidateModelAction.class).isEmpty());
    }
}
