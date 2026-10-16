package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.actions.RequestAction;

/**
 * Asks for every node of a coverable kind that is currently not covered in the given mode; each gap says whether it
 * is a root cause.
 *
 * @see org.eclipse.glsp.example.bigraph.extension.popp.actions.CoverageGapsAction
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
