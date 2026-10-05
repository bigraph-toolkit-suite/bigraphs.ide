package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-model report: root coverage per tree (are Problems covered by Goals and proofs, Goals by criteria?),
 * link ratios per metamodel relation, and tree size metrics. Derived from the metamodel, nothing hardcoded per kind.
 */
public final class CoverageReportBuilder {

    public record RootStatus(String id, String kind, String description, String coverage) {
    }

    /** One relation type between two kinds. Only the side whose coverage depends on the link is restricted to leaves. */
    public record LayerReport(String label, String relationType, String fromKind, String toKind,
                              int fromTotal, int fromLinked, int toTotal, int toLinked,
                              List<String> fromUnlinked, List<String> toUnlinked) {
    }

    public record TreeMetrics(int roots, int nodes, int height, int width, int maxBranching) {
    }

    public record CoverageReport(String mode, List<RootStatus> roots,
                                 Map<String, Map<String, Integer>> coverageByKind,
                                 List<LayerReport> layers, Map<String, TreeMetrics> trees) {
    }

    private CoverageReportBuilder() {
    }

    public static CoverageReport build(POPPModel model, CoverageMode mode) {
        CoverageSession session = model.getCoverage().session(mode);
        List<TreeNode<?>> nodes = new ArrayList<>(model.getAllNodes());

        List<RootStatus> roots = new ArrayList<>();
        Map<String, Map<String, Integer>> byKind = new HashMap<>();
        for (TreeNode<?> n : nodes) {
            if (!DomainCoverageAnalyzer.isCoverable(n)) {
                continue;
            }
            Coverage c = session.coverage(n);
            byKind.computeIfAbsent(n.getKind().name(), k -> new HashMap<>()).merge(c.name(), 1, Integer::sum);
            if (n.getParent().isEmpty()) {
                roots.add(new RootStatus(n.getId(), n.getKind().name(), n.getDescription(), c.name()));
            }
        }

        return new CoverageReport(mode.name(), roots, byKind, layers(model, nodes), metrics(nodes));
    }

    private static List<LayerReport> layers(POPPModel model, List<TreeNode<?>> nodes) {
        List<LayerReport> layers = new ArrayList<>();
        for (RelationType type : RelationType.values()) {
            boolean fromDependent = type.getCoveragePropagation() == RelationType.CoveragePropagation.BACKWARD;
            boolean toDependent = type.getCoveragePropagation() == RelationType.CoveragePropagation.FORWARD;
            for (RelationType.NodeTypePair pair : type.getSupportedNodeTypePair()) {
                List<TreeNode<?>> from = nodes.stream()
                        .filter(n -> n.getKind().equals(pair.from()))
                        .filter(n -> !fromDependent || n.getChildren().isEmpty()).toList();
                List<TreeNode<?>> to = nodes.stream()
                        .filter(n -> n.getKind().equals(pair.to()))
                        .filter(n -> !toDependent || n.getChildren().isEmpty()).toList();
                List<String> fromUnlinked = from.stream()
                        .filter(n -> model.getRelations().outgoing(n, type).isEmpty()).map(TreeNode::getId).toList();
                List<String> toUnlinked = to.stream()
                        .filter(n -> model.getRelations().incoming(n, type).isEmpty()).map(TreeNode::getId).toList();
                layers.add(new LayerReport(
                        pair.from().getName() + "-" + type.name().toLowerCase() + "->"
                                + pair.to().getName(),
                        type.name(), pair.from().getName(), pair.to().getName(),
                        from.size(), from.size() - fromUnlinked.size(),
                        to.size(), to.size() - toUnlinked.size(),
                        fromUnlinked, toUnlinked));
            }
        }
        return layers;
    }

    private static Map<String, TreeMetrics> metrics(List<TreeNode<?>> nodes) {
        Map<String, TreeMetrics> trees = new HashMap<>();
        for (NodeKind kind : NodeKind.values()) {
            List<TreeNode<?>> ofKind = nodes.stream().filter(n -> n.getKind() == kind).toList();
            if (ofKind.isEmpty()) {
                continue;
            }
            List<TreeNode<?>> roots = ofKind.stream().filter(n -> n.getParent().isEmpty()).toList();
            trees.put(kind.name(), new TreeMetrics(
                    roots.size(),
                    ofKind.size(),
                    roots.stream().mapToInt(TreeNode::getHeight).max().orElse(0),
                    roots.stream().mapToInt(TreeNode::getWidth).max().orElse(0),
                    ofKind.stream().mapToInt(n -> n.getChildren().size()).max().orElse(0)));
        }
        return trees;
    }
}

