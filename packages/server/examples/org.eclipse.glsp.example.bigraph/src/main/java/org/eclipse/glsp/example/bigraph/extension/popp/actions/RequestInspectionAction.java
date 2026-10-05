package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.actions.RequestAction;

public class RequestInspectionAction extends RequestAction<InspectionResultAction> {
    public static final String KIND = "popp.requestInspection";
    private String nodeId;
    private String mode;

    public RequestInspectionAction() {
        super(KIND);
    }

    public String getNodeId() { return nodeId; }
    public void setNodeId(final String nodeId) { this.nodeId = nodeId; }
    public String getMode() { return mode; }
    public void setMode(final String mode) { this.mode = mode; }
}