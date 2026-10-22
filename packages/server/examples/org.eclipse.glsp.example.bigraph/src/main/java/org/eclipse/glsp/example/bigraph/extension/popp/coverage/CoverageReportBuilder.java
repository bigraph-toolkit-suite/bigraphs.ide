package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;
import java.util.Set;

/**
 * Builds the coverage report: a summary per coverage mode (independent of the selected mode, so the task's questions
 * are always answered), the coverage of every root in the selected mode and the size of the trees per kind.
 */
public final class CoverageReportBuilder {

    /**
     * A root node and its coverage, for the coverable kinds (see {@link Coverage#isCoverable}) the mode is about: its
     * targets and the kinds between its emitters and targets ({@link CoverageMode#gapKinds()}).
     */
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

    /**
     * How well the trees a mode asks about are covered in that mode; one per {@link CoverageMode#presets() preset}.
     *
     * @param mode     {@link CoverageMode#name()}
     * @param kind     {@link NodeKind} name of the tree the mode asks about, {@code null} for a mode about all coverable
     *                 trees (planning, verify)
     * @param coverage coverage of the roots of those trees, the lowest one; {@code null} without any such tree
     * @param counts   number of nodes of those kinds per {@link Coverage} name
     */
    public record ModeSummary(String mode, String kind, String coverage, Map<String, Integer> counts) {}

    /**
     * {@code modes} summarises every preset, independent of the selected {@code mode}; {@code roots} are the roots in the
     * selected mode. {@code trees} is keyed by {@link NodeKind} name; kinds without nodes are absent.
     */
    public record CoverageReport(String mode, List<ModeSummary> modes, List<RootStatus> roots,
                                 Map<String, TreeMetrics> trees) {
        public static CoverageReport empty(CoverageMode mode) {
            return new CoverageReport(mode.name(), List.of(), List.of(), Map.of());
        }
    }

    private CoverageReportBuilder() {}

    /** The report for {@code model}, with coverage from {@code analyzer} (which must analyse that model). */
    public static CoverageReport build(POPPModel model, CoverageAnalyzer analyzer, CoverageMode mode) {
        // every preset for the summary plus the selected mode, evaluated in parallel
        List<CoverageMode> modes = new ArrayList<>(CoverageMode.presets());
        modes.add(mode);
        Map<CoverageMode, CoverageSession> sessions = analyzer.sessions(modes);
        CoverageSession session = sessions.get(mode);
        List<TreeNode<?>> nodes = new ArrayList<>(model.getAllNodes());

        List<RootStatus> roots = new ArrayList<>();
        Map<String, Map<String, Integer>> byKind = new HashMap<>();
        Set<NodeKind> shown = EnumSet.copyOf(mode.gapKinds());
        shown.addAll(mode.targets());
        for (TreeNode<?> n : nodes) {
            if (!shown.contains(n.getKind()) || !Coverage.isCoverable(n)) {
                continue;
            }
            Coverage c = session.coverage(n);
            byKind.computeIfAbsent(n.getKind().name(), k -> new HashMap<>()).merge(c.name(), 1, Integer::sum);
            if (n.getParent().isEmpty()) {
                roots.add(new RootStatus(n.getId(), n.getKind().name(), n.getDescription(), c.name()));
            }
        }

        return new CoverageReport(mode.name(), summaries(nodes, sessions), roots, metrics(nodes));
    }

    /** A {@link ModeSummary} per preset; one session each (cached per model revision by the analyzer). */
    public static List<ModeSummary> summaries(List<TreeNode<?>> nodes, CoverageAnalyzer analyzer) {
        return summaries(nodes, analyzer.sessions(CoverageMode.presets()));
    }

    /** A {@link ModeSummary} per preset, from the given sessions (which must contain every preset). */
    private static List<ModeSummary> summaries(List<TreeNode<?>> nodes, Map<CoverageMode, CoverageSession> sessions) {
        List<ModeSummary> out = new ArrayList<>();
        for (CoverageMode mode : CoverageMode.presets()) {
            CoverageSession session = sessions.get(mode);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Coverage c : Coverage.values()) counts.put(c.name(), 0);
            Coverage root = null;
            for (TreeNode<?> n : nodes) {
                if (!mode.isTarget(n.getKind()) || !Coverage.isCoverable(n)) continue;
                Coverage c = session.coverage(n);
                counts.merge(c.name(), 1, Integer::sum);
                if (n.getParent().isEmpty()) root = root == null || c.compareTo(root) < 0 ? c : root;
            }
            String kind = mode.targets().size() == 1 ? mode.targets().iterator().next().name() : null;
            out.add(new ModeSummary(mode.name(), kind, root == null ? null : root.name(), counts));
        }
        return out;
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