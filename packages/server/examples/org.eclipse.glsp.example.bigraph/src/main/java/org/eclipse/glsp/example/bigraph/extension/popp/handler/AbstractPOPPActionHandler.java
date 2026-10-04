package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtension;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionState;
import org.eclipse.glsp.example.bigraph.extensions.VariantGate;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.actions.SetDirtyStateAction;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;

import java.util.List;

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
        if (!variantGate.isActive(POPPExtension.POPP_VARIANT_ID)) {
            logger.debug("POPP handler {} ignored: active variant is '{}'.",
                    getClass().getSimpleName(), variantGate.activeVariantId());
            BigraphNotifications.notifyError(actionDispatcher,
                    "POPP operation ignored: current view is '"
                            + variantGate.activeVariantId() + "'.");
            return List.of();
        }

        POPPExtensionState state = state();

        try {
            applyMutation(action);
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

        actionDispatcher.dispatch(new SetDirtyStateAction(true, "operation"));

        return List.of(new UpdateModelAction(state.getGModel().getRoot(), false));
    }

    protected abstract void applyMutation(A action);
}
