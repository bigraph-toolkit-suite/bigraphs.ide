package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the coverage report: the coverage of every root and the size of the trees per kind. */
public final class CoverageReportBuilder {

    /** A root node of a coverable kind (see {@link Coverage#isCoverable}) and its coverage. */
    public record RootStatus(String id, String kind, String description, String coverage) {}

    /**
     * Size of the trees of one kind (every kind, coverable or not).
     *
     * @param roots  number of root nodes, i.e. of trees
     * @param nodes  number of nodes of the kind
     * @param height levels of the highest tree
     * @param width  leaves of the widest tree
     */
    public record TreeMetrics(int roots, int nodes, int height, int width) {}

    /** {@code trees} is keyed by {@link NodeKind} name; kinds without nodes are absent. */
    public record CoverageReport(String mode, List<RootStatus> roots,
                                 Map<String, TreeMetrics> trees) {}

    private CoverageReportBuilder() {}

    /** The report for {@code model}, with coverage from {@code analyzer} (which must analyse that model). */
    public static CoverageReport build(POPPModel model, CoverageAnalyzer analyzer, CoverageMode mode) {
        CoverageSession session = analyzer.session(mode);
        List<TreeNode<?>> nodes = new ArrayList<>(model.getAllNodes());

        List<RootStatus> roots = new ArrayList<>();
        Map<String, Map<String, Integer>> byKind = new HashMap<>();
        for (TreeNode<?> n : nodes) {
            if (!Coverage.isCoverable(n)) {
                continue;
            }
            Coverage c = session.coverage(n);
            byKind.computeIfAbsent(n.getKind().name(), k -> new HashMap<>()).merge(c.name(), 1, Integer::sum);
            if (n.getParent().isEmpty()) {
                roots.add(new RootStatus(n.getId(), n.getKind().name(), n.getDescription(), c.name()));
            }
        }

        return new CoverageReport(mode.name(), roots, metrics(nodes));
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
                    roots.stream().mapToInt(TreeNode::getWidth).max().orElse(0)));
        }
        return trees;
    }
}