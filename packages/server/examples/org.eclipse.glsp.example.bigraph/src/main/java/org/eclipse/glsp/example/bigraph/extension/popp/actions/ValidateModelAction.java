package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.actions.Action;

/**
 * Asks for a validation run of the current model. Sent by the client (e.g. when the palette opens) and dispatched by
 * the server to itself once edits have settled. The result arrives as a {@link ValidationResultAction}, asynchronously.
 */
public class ValidateModelAction extends Action {
    public static final String KIND = "popp.validateModel";

    public ValidateModelAction() {
        super(KIND);
    }
}
