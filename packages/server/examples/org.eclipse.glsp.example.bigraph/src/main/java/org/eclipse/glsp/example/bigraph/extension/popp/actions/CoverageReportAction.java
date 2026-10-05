package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageReportBuilder;
import org.eclipse.glsp.server.actions.ResponseAction;

public class CoverageReportAction extends ResponseAction {
    public static final String KIND = "popp.coverageReport";
    private CoverageReportBuilder.CoverageReport report;

    public CoverageReportAction() {
        super(KIND);
    }

    public CoverageReportAction(final CoverageReportBuilder.CoverageReport report) {
        super(KIND);
        this.report = report;
    }

    public CoverageReportBuilder.CoverageReport getReport() { return report; }
    public void setReport(final CoverageReportBuilder.CoverageReport report) { this.report = report; }
}