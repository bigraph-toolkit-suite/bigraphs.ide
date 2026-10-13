package org.eclipse.glsp.example.bigraph.extension.popp.handler;

import com.google.inject.Inject;
import org.eclipse.glsp.example.bigraph.extension.popp.actions.ValidateModelAction;
import org.eclipse.glsp.example.bigraph.extension.popp.validation.POPPValidationService;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;

import java.util.List;

/** Takes the validation snapshot on the dispatch thread; the result follows asynchronously. */
public class ValidateModelHandler extends AbstractActionHandler<ValidateModelAction> {
    @Inject
    protected POPPValidationService validationService;

    @Override
    protected List<Action> executeAction(final ValidateModelAction action) {
        validationService.runNow();
        return List.of();
    }
}
