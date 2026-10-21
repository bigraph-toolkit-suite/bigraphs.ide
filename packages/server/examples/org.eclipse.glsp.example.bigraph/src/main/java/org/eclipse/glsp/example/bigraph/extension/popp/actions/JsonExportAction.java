package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.actions.Action;

/**
 * The current POPP model as a POPP JSON document ({@code POPPJson}), with a suggested file name. The client hands it
 * to the VS Code extension, which asks where to save it.
 */
public class JsonExportAction extends Action {
    public static final String KIND = "popp.jsonExport";

    private String json;
    private String fileName;

    public JsonExportAction() {
        super(KIND);
    }

    public JsonExportAction(final String json, final String fileName) {
        super(KIND);
        this.json = json;
        this.fileName = fileName;
    }

    public String getJson() { return json; }
    public void setJson(final String json) { this.json = json; }
    public String getFileName() { return fileName; }
    public void setFileName(final String fileName) { this.fileName = fileName; }
}
