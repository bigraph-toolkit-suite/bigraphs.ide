package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.actions.RequestAction;

/**
 * Asks for every node that is not covered in {@code mode}, of the kinds the mode reports gaps for. Answered with a
 * {@link CoverageGapsAction}.
 */
public class RequestCoverageGapsAction extends RequestAction<CoverageGapsAction> {
    public static final String KIND = "popp.requestCoverageGaps";

    private String mode;

    public RequestCoverageGapsAction() {
        super(KIND);
    }

    public String getMode() { return mode; }
    public void setMode(final String mode) { this.mode = mode; }
}
