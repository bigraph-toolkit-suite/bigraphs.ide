package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.actions.Action;

/** Asks for the current POPP model as a POPP JSON document; answered with a {@link JsonExportAction}. */
public class RequestJsonExportAction extends Action {
    public static final String KIND = "popp.requestJsonExport";

    public RequestJsonExportAction() {
        super(KIND);
    }
}
