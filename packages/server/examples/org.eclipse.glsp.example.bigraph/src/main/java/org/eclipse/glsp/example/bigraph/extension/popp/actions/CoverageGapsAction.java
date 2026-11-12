package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.server.actions.ResponseAction;

import java.util.List;

/** Answer to {@link RequestCoverageGapsAction}: the gaps in the requested mode. */
public class CoverageGapsAction extends ResponseAction {
    public static final String KIND = "popp.coverageGaps";

    private String mode;
    private List<CoverageGapDto> gaps = List.of();

    public CoverageGapsAction() {
        super(KIND);
    }

    public CoverageGapsAction(final String mode, final List<CoverageGapDto> gaps) {
        super(KIND);
        this.mode = mode;
        this.gaps = gaps;
    }

    public String getMode() { return mode; }
    public List<CoverageGapDto> getGaps() { return gaps; }
}
