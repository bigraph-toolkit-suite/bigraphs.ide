package org.eclipse.glsp.example.bigraph.extension.popp.inspection;

import java.util.List;
import java.util.Map;

/** What the client needs to highlight the evidence behind one node's coverage. All ids are GModel element ids. */
public record InspectionResult(String nodeId, String label, String coverage, String mode,
                               List<String> pathNodeIds, List<String> pathEdgeIds,
                               Map<String, String> nodeCoverage, List<GapInfo> gaps) {

    public record GapInfo(String nodeId, String message) {
    }

    public static InspectionResult unknown(String nodeId, String mode) {
        return new InspectionResult(nodeId, "", "UNKNOWN", mode, List.of(), List.of(), Map.of(), List.of());
    }
}