package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelTypes;
import org.eclipse.glsp.example.bigraph.extension.popp.state.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.state.POPPExtensionState;
import org.eclipse.glsp.example.bigraph.extensions.VariantGate;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.actions.SetDirtyStateAction;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;

import java.util.List;

/**
 * Base for handlers that change the POPP model. Ignores the action (with a notification) unless the POPP variant is
 * active, turns a {@link POPPValidationException} or any other exception into an error notification, and after a
 * successful change marks the model dirty and sends the client an {@link UpdateModelAction}.
 */
public abstract class AbstractPOPPActionHandler<A extends Action> extends AbstractActionHandler<A> {

    protected final Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected POPPExtensionContext context;

    @Inject
    protected ActionDispatcher actionDispatcher;

    @Inject
    protected VariantGate variantGate;

    protected POPPExtensionState state() {
        return context.getOwnState();
    }

    @Override
    protected final List<Action> executeAction(A action) {
        if (!variantGate.isActive(POPPGModelTypes.VARIANT)) {
            logger.debug("POPP handler {} ignored: active variant is '{}'.",
                    getClass().getSimpleName(), variantGate.activeVariantId());
            BigraphNotifications.notifyError(actionDispatcher,
                    "POPP operation ignored: current view is '"
                            + variantGate.activeVariantId() + "'.");
            return List.of();
        }

        POPPExtensionState state = state();

        boolean updatedRequired = false;
        try {
            updatedRequired = applyMutation(action);
        } catch (POPPValidationException ve) {
            logger.warn("POPP operation rejected: {}", ve.getMessage());
            BigraphNotifications.notifyError(actionDispatcher,
                    "POPP: " + ve.getMessage());
            return List.of();
        } catch (Exception e) {
            logger.error("POPP operation failed", e);
            BigraphNotifications.notifyError(actionDispatcher,
                    "POPP operation failed: " + e.getMessage());
            return List.of();
        }

        if (updatedRequired){
            actionDispatcher.dispatch(new SetDirtyStateAction(true, "operation"));

            return List.of(new UpdateModelAction(state.getGModel().getRoot(), false));
        }
        return List.of();
    }

    /** Applies the action to the model; returns whether something changed (then the client is updated). */
    protected abstract boolean applyMutation(A action);
}
