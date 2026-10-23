package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPEventListener;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Model generators shared by the coverage analyzer tests and benchmarks. Not a test class. */
public final class CoverageTestModels {
    private CoverageTestModels() {}

    /**
     * The analyzer for a benchmark/check name: {@code domain} ({@link DomainCoverageAnalyzer}, the reference); a schedule
     * of {@link ScheduledRewritingCoverageAnalyzer} from scratch: {@code rounds} (no optimisation), {@code worklist},
     * {@code worklist-seeded},
     * {@code guards-first}, {@code worklist-guards-seeded-first} (the default, also {@code default}); {@code oracle-<schedule>}, the
     * hits-only replay of that schedule ({@link OracleRewritingCoverageAnalyzer}; {@code oracle} is the default
     * schedule's); or {@code incremental-<schedule>}, the {@link IncrementalCoverageAnalyzer} with that schedule
     * ({@code incremental} with the default one), registered as a listener of the bigraph. Rewriting needs the fixed
     * jLibBig {@code AgentMatcher}, see {@link ScheduledRewritingCoverageAnalyzer#REWRITE_KEEPS_LINKS}.
     */
    public static CoverageAnalyzer analyzer(String name, Synced popp) {
        CoverageInput input = CoverageInput.of(popp.model, popp.bigraph);
        return switch (name) {
            case "domain" -> new DomainCoverageAnalyzer(input);
            case "oracle" -> new OracleRewritingCoverageAnalyzer(input);
            case "default" -> new ScheduledRewritingCoverageAnalyzer(input);
            case "incremental" -> incremental(popp, input, ScheduledRewritingCoverageAnalyzer.Options.DEFAULT);
            default -> name.startsWith("oracle-")
                    ? new OracleRewritingCoverageAnalyzer(input, options(name.substring("oracle-".length())))
                    : name.startsWith("incremental-")
                    ? incremental(popp, input, options(name.substring("incremental-".length())))
                    : new ScheduledRewritingCoverageAnalyzer(input, options(name));
        };
    }

    /** An {@link IncrementalCoverageAnalyzer}, registered as a listener of the model's bigraph. */
    private static CoverageAnalyzer incremental(Synced popp, CoverageInput input, ScheduledRewritingCoverageAnalyzer.Options options) {
        var analyzer = new IncrementalCoverageAnalyzer(input, options);
        popp.bigraph.addListener(analyzer);
        return analyzer;
    }

    /** The schedule for a variant name (see {@link #analyzer}). */
    static ScheduledRewritingCoverageAnalyzer.Options options(String name) {
        return switch (name) {
            case "rounds" -> ScheduledRewritingCoverageAnalyzer.Options.ROUNDS;
            case "worklist" -> ScheduledRewritingCoverageAnalyzer.Options.WORKLIST;
            case "worklist-seeded" -> ScheduledRewritingCoverageAnalyzer.Options.WORKLIST_SEEDED;
            case "guards-first" -> ScheduledRewritingCoverageAnalyzer.Options.GUARDS_FIRST;
            case "worklist-guards-seeded-first" -> ScheduledRewritingCoverageAnalyzer.Options.WORKLIST_GUARDS_SEEDED_FIRST;
            default -> throw new IllegalArgumentException("Unknown analyzer " + name);
        };
    }

    /** A POPP model together with the {@link POPPBigraph} that is kept in sync with it by the model's events. */
    public static final class Synced {
        public final POPPModel model = new POPPModel();
        public final POPPBigraph bigraph;

        public Synced() {
            DynamicSignature sig = POPPBigraphSignature.create();
            PureBigraphMutable mutable = pureBuilder(sig).createMutable();
            this.bigraph = new POPPBigraph(mutable, sig);
            model.addListener(new Forwarder(bigraph));
        }
    }

    /** What the bigraph synchronizer does, without its Guice context. */
    private record Forwarder(POPPBigraph bigraph) implements POPPEventListener {
        @Override
        public void onPOPPEvent(POPPEvent event) {
            if (event instanceof POPPEvent.NodeCreated e) bigraph.addTreeNode(e.node());
            else if (event instanceof POPPEvent.NodeDescriptionChanged e) bigraph.updateDescription(e.node().getId(), e.node().getDescription());
            else if (event instanceof POPPEvent.NodeMoved e) bigraph.updatePosition(e.node().getId(), e.node().getX(), e.node().getY());
            else if (event instanceof POPPEvent.NodeDecompositionTypeChanged e) bigraph.rebuildDecomposition(e.node());
            else if (event instanceof POPPEvent.NodeRemoved e) bigraph.deleteTreeNode(e.node().getId());
            else if (event instanceof POPPEvent.NodeChangedParent e) bigraph.reparentTreeNode(e.node(), e.oldParent(), e.newParent());
            else if (event instanceof POPPEvent.RelationCreated e) bigraph.createRelation(e.relation());
            else if (event instanceof POPPEvent.RelationRemoved e) bigraph.removeRelation(e.relation());
            else throw new IllegalStateException("Unhandled POPPEvent: " + event);
        }
    }

    /**
     * A balanced decomposition tree of success criteria (AND and OR alternating by level, root = AND), 2^depth
     * leaves, every proof produced by the one solution.
     *
     * @param proofs the proof of leaf i is {@code proofs.get(i)}; if not all leaves are proven the list is shorter
     */
    public record Tree(SuccessCriteria root, List<SuccessCriteria> leaves, List<SuccessProof> proofs, Solution solution) {}

    /** With {@code proveAll} every leaf gets a proof, otherwise only the first one does. */
    public static Tree balancedTree(POPPModel model, int depth, boolean proveAll) {
        Solution solution = (Solution) model.createNode(NodeKind.SOLUTION, "solution", 0, 0);
        List<SuccessCriteria> leaves = new ArrayList<>();
        SuccessCriteria root = subtree(model, depth, 0, leaves);

        List<SuccessProof> proofs = new ArrayList<>();
        for (int i = 0; i < leaves.size(); i++) {
            if (!proveAll && i > 0) break;
            SuccessProof proof = (SuccessProof) model.createNode(NodeKind.SUCCESS_PROOF, "proof " + i, 0, 0);
            assertEquals(RelationResult.CREATED, model.relate(proof, solution));
            assertEquals(RelationResult.CREATED, model.relate(leaves.get(i), proof));
            proofs.add(proof);
        }
        return new Tree(root, leaves, proofs, solution);
    }

    private static SuccessCriteria subtree(POPPModel model, int depth, int level, List<SuccessCriteria> leaves) {
        SuccessCriteria node = (SuccessCriteria) model.createNode(NodeKind.SUCCESS_CRITERIA, "c" + level, 0, 0);
        if (depth == 0) {
            leaves.add(node);
            return node;
        }
        node.addChild(subtree(model, depth - 1, level + 1, leaves));
        node.addChild(subtree(model, depth - 1, level + 1, leaves));
        node.setDecompositionType(level % 2 == 0 ? DecompositionType.AND : DecompositionType.OR);
        return node;
    }

    /**
     * A balanced model with all six trees, used by the benchmarks: every tree is a balanced binary decomposition of
     * the given depth (AND and OR alternating by level, root = AND), so each has 2^depth leaves and the model has
     * 6 * (2^(depth+1) - 1) nodes (6, 18, 42, 90 for depth 0..3). Leaf i of each tree is connected to leaf i of the
     * next one: problem -inverts-> goal, goal -causes-> criterion, proof -validates-> criterion, solution -produces->
     * proof, solution -realizes-> goal and problem -causes-> consequence.
     *
     * @param complete with {@code false} only the even criteria leaves are validated, so coverage stays partial
     */
    public static FullModel fullModel(POPPModel model, int depth, boolean complete) {
        Map<NodeKind, TreeNode<?>> roots = new EnumMap<>(NodeKind.class);
        Map<NodeKind, List<TreeNode<?>>> leaves = new EnumMap<>(NodeKind.class);
        for (NodeKind kind : NodeKind.values()) {
            List<TreeNode<?>> l = new ArrayList<>();
            roots.put(kind, tree(model, kind, depth, 0, l));
            leaves.put(kind, l);
        }
        for (int i = 0; i < (1 << depth); i++) {
            TreeNode<?> problem = leaves.get(NodeKind.PROBLEM).get(i), goal = leaves.get(NodeKind.GOAL).get(i),
                    criterion = leaves.get(NodeKind.SUCCESS_CRITERIA).get(i), proof = leaves.get(NodeKind.SUCCESS_PROOF).get(i),
                    solution = leaves.get(NodeKind.SOLUTION).get(i), consequence = leaves.get(NodeKind.CONSEQUENCE).get(i);
            assertEquals(RelationResult.CREATED, model.relate(problem, goal));
            assertEquals(RelationResult.CREATED, model.relate(goal, criterion));
            if (complete || i % 2 == 0) assertEquals(RelationResult.CREATED, model.relate(proof, criterion));
            assertEquals(RelationResult.CREATED, model.relate(solution, proof));
            assertEquals(RelationResult.CREATED, model.relate(solution, goal));
            assertEquals(RelationResult.CREATED, model.relate(problem, consequence));
        }
        return new FullModel(roots, leaves);
    }

    /** The trees of {@link #fullModel}: root and leaves (left to right) per kind. */
    public record FullModel(Map<NodeKind, TreeNode<?>> roots, Map<NodeKind, List<TreeNode<?>>> leaves) {
        public TreeNode<?> root(NodeKind kind) { return roots.get(kind); }
        public TreeNode<?> leaf(NodeKind kind, int i) { return leaves.get(kind).get(i); }
    }

    private static TreeNode<?> tree(POPPModel model, NodeKind kind, int depth, int level, List<TreeNode<?>> leaves) {
        TreeNode<?> node = model.createNode(kind, kind.getName() + " " + level + "." + leaves.size(), 0, 0);
        if (depth == 0) {
            leaves.add(node);
            return node;
        }
        model.decompose(node, tree(model, kind, depth - 1, level + 1, leaves));
        model.decompose(node, tree(model, kind, depth - 1, level + 1, leaves));
        node.setDecompositionType(level % 2 == 0 ? DecompositionType.AND : DecompositionType.OR);
        return node;
    }
}
