package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.actions.RequestAction;

public class RequestTraceabilityReportAction extends RequestAction<TraceabilityReportAction> {
    public static final String KIND = "popp.requestTraceabilityReport";
    private String mode;

    public RequestTraceabilityReportAction() {
        super(KIND);
    }

    public String getMode() { return mode; }
    public void setMode(final String mode) { this.mode = mode; }
}