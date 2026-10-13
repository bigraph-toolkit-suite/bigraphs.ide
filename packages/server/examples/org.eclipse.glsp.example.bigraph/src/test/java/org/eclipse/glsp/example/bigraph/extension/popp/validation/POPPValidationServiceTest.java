package org.eclipse.glsp.example.bigraph.extension.popp.validation;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSynchronizer;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
