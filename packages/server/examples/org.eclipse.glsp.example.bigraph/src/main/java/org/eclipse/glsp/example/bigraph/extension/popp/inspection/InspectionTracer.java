package org.eclipse.glsp.example.bigraph.extension.popp.inspection;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageMode;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageReason;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelFactory;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.*;

/** Flattens a {@link CoverageReason} tree into the element ids to highlight and the gaps to report. */
public final class InspectionTracer {
    private final POPPGModelFactory factory;

    public InspectionTracer(POPPGModelFactory factory) {
        this.factory = factory;
    }

    public InspectionResult trace(POPPModel model, TreeNode<?> node, CoverageMode mode) {
        CoverageReason reason = model.getCoverage().explain(node, mode);

        Set<String> nodes = new LinkedHashSet<>();
        Set<String> edges = new LinkedHashSet<>();
        Map<String, String> coverage = new LinkedHashMap<>();
        List<InspectionResult.GapInfo> gaps = new ArrayList<>();
        walk(reason, nodes, edges, coverage, gaps);

        return new InspectionResult(node.getId(), node.getDescription(), reason.coverage().name(), mode.name(),
                List.copyOf(nodes), List.copyOf(edges), coverage, gaps);
    }

    private void walk(CoverageReason reason, Set<String> nodes, Set<String> edges,
                      Map<String, String> coverage, List<InspectionResult.GapInfo> gaps) {
        String id = reason.node().getId();
        if (!nodes.add(id)) {
            return; // shared sub-structure already visited
        }
        coverage.put(id, reason.coverage().name());

        switch (reason) {
            case CoverageReason.Intrinsic e -> {
            }
            case CoverageReason.Decomposition d -> {
                for (CoverageReason child : d.children()) {
                    edges.add(factory.toDecompositionEdgeId(d.node(), child.node()));
                    walk(child, nodes, edges, coverage, gaps);
                }
            }
            case CoverageReason.Link l -> {
                edges.add(factory.toRelationEdgeId(l.relation()));
                walk(l.coveringReason(), nodes, edges, coverage, gaps);
            }
            case CoverageReason.NotCovered n -> n.gaps().forEach(g -> gaps.add(
                    new InspectionResult.GapInfo(id, n.node().getKind().name() + " " + g.describe())));
        }
    }
}