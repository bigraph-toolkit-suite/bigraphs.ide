package org.eclipse.glsp.example.bigraph.extension.popp.actions;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.TraceabilityReportBuilder;
import org.eclipse.glsp.server.actions.ResponseAction;

public class TraceabilityReportAction extends ResponseAction {
    public static final String KIND = "popp.traceabilityReport";
    private TraceabilityReportBuilder.TraceabilityReport report;

    public TraceabilityReportAction() {
        super(KIND);
    }

    public TraceabilityReportAction(final TraceabilityReportBuilder.TraceabilityReport report) {
        super(KIND);
        this.report = report;
    }

    public TraceabilityReportBuilder.TraceabilityReport getReport() { return report; }
    public void setReport(final TraceabilityReportBuilder.TraceabilityReport report) { this.report = report; }
}