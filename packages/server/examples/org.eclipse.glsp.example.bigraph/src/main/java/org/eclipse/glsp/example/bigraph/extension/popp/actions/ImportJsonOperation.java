package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.operations.Operation;

/**
 * Sent by the client to replace the current POPP model with the content of a POPP JSON document
 * ({@link org.eclipse.glsp.example.bigraph.extension.popp.io.POPPJson}).
 */
public class ImportJsonOperation extends Operation {
    public static final String KIND = "popp.importJson";

    private String json;

    public ImportJsonOperation() {
        super(KIND);
    }

    public ImportJsonOperation(final String json) {
        super(KIND);
        this.json = json;
    }

    public String getJson() { return json; }
}
