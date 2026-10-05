package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.actions.RequestAction;

public class RequestCoverageReportAction extends RequestAction<CoverageReportAction> {
    public static final String KIND = "popp.requestCoverageReport";
    private String mode;

    public RequestCoverageReportAction() {
        super(KIND);
    }

    public String getMode() { return mode; }
    public void setMode(final String mode) { this.mode = mode; }
}