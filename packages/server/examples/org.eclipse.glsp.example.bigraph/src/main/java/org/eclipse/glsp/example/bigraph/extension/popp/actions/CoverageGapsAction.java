package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.actions.ResponseAction;

import java.util.List;

/**
 * Answer to {@link RequestCoverageGapsAction}.
 *
 * @see org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageGap
 */
public class CoverageGapsAction extends ResponseAction {
    public static final String KIND = "popp.coverageGaps";

    /**
     * @param nodeId    POPP node id (= GModel id)
     * @param label     kind and description, e.g. {@code goal "Ship v2"}
     * @param coverage  {@code UNCOVERED} or {@code PARTIAL}
     * @param rootCause nothing beneath or behind the node could still contribute: this is where to act
     * @param message   what is missing (root causes only, otherwise empty), e.g.
     *                  "needs a success criteria (causes)", several joined with " or "
     */
    public record Gap(String nodeId, String label, String coverage, boolean rootCause, String message) {}

    private String mode;
    private List<Gap> gaps = List.of();

    public CoverageGapsAction() {
        super(KIND);
    }

    public CoverageGapsAction(final String mode, final List<Gap> gaps) {
        super(KIND);
        this.mode = mode;
        this.gaps = gaps;
    }

    public String getMode() { return mode; }
    public void setMode(final String mode) { this.mode = mode; }
    public List<Gap> getGaps() { return gaps; }
    public void setGaps(final List<Gap> gaps) { this.gaps = gaps; }
}
