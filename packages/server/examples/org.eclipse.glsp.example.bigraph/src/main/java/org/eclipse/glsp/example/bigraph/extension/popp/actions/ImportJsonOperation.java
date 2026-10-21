package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.operations.Operation;

/** Replaces the current POPP model with the content of a POPP JSON document ({@code POPPJson}). */
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
    public void setJson(final String json) { this.json = json; }
}
