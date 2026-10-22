package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.*;

/**
 * Random POPP models for differential checks of the rewriting analyzers against {@link DomainCoverageAnalyzer}.
 * Unlike {@link CoverageTestModels#balancedTree}, they contain every node kind and relation type, n-ary and
 * single-child AND/OR decompositions (inner gate nodes), NONE leaves, and unrelated nodes. The model refuses a
 * decomposition type on a leaf, but a file can still contain an empty gate node, so some leaves get one written
 * directly into the bigraph (the model keeps them NONE).
 * <p>
 * {@link #main} runs the check outside JUnit: {@code CoverageRandomModels <analyzer,...> [models=200] [maxNodes=30]}.
 */
final class CoverageRandomModels {
    private CoverageRandomModels() {}

    /** Builds a random model into {@code popp} (whose bigraph follows the model's events). */
    static void build(Synced popp, Random rnd, int maxNodes) {
        POPPModel model = popp.model;
        Map<NodeKind, List<TreeNode<?>>> byKind = new EnumMap<>(NodeKind.class);
        int n = 1 + rnd.nextInt(maxNodes);
        // a random subset of 2..6 kinds, so that trees get deep and wide enough for inner chains and PARTIAL
        List<NodeKind> kinds = new ArrayList<>(List.of(NodeKind.values()));
        Collections.shuffle(kinds, rnd);
        kinds = kinds.subList(0, 2 + rnd.nextInt(kinds.size() - 1));
        for (int i = 0; i < n; i++) {
            NodeKind kind = kinds.get(rnd.nextInt(kinds.size()));
            TreeNode<?> node = model.createNode(kind, kind + " " + i, 0, 0);
            List<TreeNode<?>> same = byKind.computeIfAbsent(kind, k -> new ArrayList<>());
            // attach below an earlier node of the same kind (trees are single-kind), or stay a root
            if (!same.isEmpty() && rnd.nextInt(3) > 0) {
                // half of the time one of the first three nodes: wide decompositions (inner gate nodes)
                TreeNode<?> parent = same.get(rnd.nextInt(rnd.nextBoolean() ? Math.min(3, same.size()) : same.size()));
                model.decompose(parent, node);
            }
            same.add(node);
        }
        for (List<TreeNode<?>> nodes : byKind.values()) {
            for (TreeNode<?> node : nodes) {
                DecompositionType t = switch (rnd.nextInt(4)) {
                    case 0 -> DecompositionType.OR;
                    case 1 -> node.getChildren().isEmpty() ? DecompositionType.NONE : DecompositionType.AND;
                    default -> node.getChildren().isEmpty() && rnd.nextInt(3) == 0 ? DecompositionType.NONE
                            : DecompositionType.AND;
                };
                if (!node.getChildren().isEmpty()) {
                    node.setDecompositionType(t == DecompositionType.NONE ? DecompositionType.AND : t);
                } else if (t != DecompositionType.NONE) {
                    emptyChain(popp, node, t); // as a hand-edited file could have it
                }
            }
        }
        double density = 0.1 + rnd.nextDouble() * 0.4;
        for (RelationType type : RelationType.values()) {
            for (RelationType.NodeTypePair pair : type.getSupportedNodeTypePair()) {
                for (TreeNode<?> from : byKind.getOrDefault(pair.from(), List.of())) {
                    for (TreeNode<?> to : byKind.getOrDefault(pair.to(), List.of())) {
                        if (rnd.nextDouble() < density) model.relate(from, to);
                    }
                }
            }
        }
    }

    /** Adds an empty gate node of {@code type} under {@code node}, in the bigraph only. */
    static void emptyChain(Synced popp, TreeNode<?> node, DecompositionType type) {
        var g = popp.bigraph.getBigraph();
        var parent = popp.bigraph.getById(node.getId()).orElseThrow();
        String control = org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature
                .getGateControl(node.getKind(), type).controlName();
        g.addNode(parent, popp.bigraph.getSignature().getControlByName(control), node.getId() + "_DECOMP");
    }

    /** Number of (model, mode) pairs on which {@code analyzer} disagrees with Domain on at least one node. */
    static int mismatches(String analyzer, int models, int maxNodes, long seed, StringBuilder report) {
        int bad = 0;
        for (int i = 0; i < models; i++) {
            Synced popp = new Synced();
            build(popp, new Random(seed + i), maxNodes);
            for (CoverageMode mode : CoverageMode.presets()) {
                CoverageSession expected = new DomainCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph)).session(mode);
                CoverageSession actual = CoverageTestModels.analyzer(analyzer, popp).session(mode);
                List<String> diff = new ArrayList<>();
                for (TreeNode<?> node : popp.model.getAllNodes()) {
                    if (expected.coverage(node) != actual.coverage(node)) {
                        diff.add(node.getDescription() + " " + expected.coverage(node) + "/" + actual.coverage(node));
                    }
                    actual.explain(node); // must not throw
                }
                if (!diff.isEmpty()) {
                    bad++;
                    if (report != null) report.append(String.format("seed %d %s: expected/actual %s%n", seed + i, mode, diff));
                }
            }
        }
        return bad;
    }

    public static void main(String[] args) {
        int models = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        int maxNodes = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        for (String analyzer : args[0].split(",")) {
            StringBuilder report = new StringBuilder();
            long start = System.nanoTime();
            int bad = mismatches(analyzer, models, maxNodes, 1000, report);
            System.out.printf("[random] %-18s %d models x %d modes, up to %d nodes: %d mismatching (%d s)%n",
                    analyzer, models, CoverageMode.presets().size(), maxNodes, bad, (System.nanoTime() - start) / 1_000_000_000L);
            System.out.print(report.toString().lines().limit(5).map(l -> "[random]   " + l + "\n").reduce("", String::concat));
        }
    }
}
