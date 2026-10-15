package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPSpecificEventListener;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.ArrayList;
import java.util.List;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Model generators shared by the coverage analyzer tests and benchmarks. Not a test class. */
public final class CoverageTestModels {
    private CoverageTestModels() {}

    /**
     * The analyzer for a benchmark/check name: {@code domain}; the full-agent {@link RewritingCoverageAnalyzer} as
     * {@code rewriting-naive} (no pruning), {@code rewriting-census} (census only) or {@code rewriting} (census and
     * link guard); or one of the {@code fragment*} variants of {@link FragmentRewritingCoverageAnalyzer}.
     */
    static CoverageAnalyzer analyzer(String name, Synced popp) {
        CoverageInput input = CoverageInput.of(popp.model, popp.bigraph);

        return switch (name) {
            case "domain" -> new DomainCoverageAnalyzer(input);
            case "rewriting" -> new RewritingCoverageAnalyzer(input);
            case "rewriting-naive" -> new RewritingCoverageAnalyzer(input,
                    RewritingCoverageAnalyzer.Pruning.NONE);
            case "rewriting-census" -> new RewritingCoverageAnalyzer(input,
                    RewritingCoverageAnalyzer.Pruning.CENSUS);
            // fragment evaluation of the same rules, one lever at a time (see FragmentRewritingCoverageAnalyzer.Options)
            case "fragment-rounds" -> fragment(popp, false, false, false, false, false);
            case "fragment-pruned" -> fragment(popp, true, false, false, false, false);
            case "fragment-worklist" -> fragment(popp, true, true, false, false, false);
            case "fragment-slim" -> fragment(popp, true, true, true, false, false);
            case "fragment" -> fragment(popp, true, true, true, true, false);
            case "fragment-memo" -> fragment(popp, true, true, true, true, true);
            default -> throw new IllegalArgumentException("Unknown analyzer " + name);
        };
    }

    private static CoverageAnalyzer fragment(Synced popp, boolean localPrune, boolean incremental, boolean slim,
                                             boolean firstMatch, boolean memo) {
        return new FragmentRewritingCoverageAnalyzer(CoverageInput.of(popp.model, popp.bigraph),
                new FragmentRewritingCoverageAnalyzer.Options(localPrune, incremental, slim, firstMatch, memo));
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

    private static final class Forwarder extends POPPSpecificEventListener {
        private final POPPBigraph bigraph;
        Forwarder(POPPBigraph bigraph) { this.bigraph = bigraph; }
        @Override protected void onNodeCreated(POPPEvent.NodeCreated e) { bigraph.addTreeNode(e.node()); }
        @Override protected void onNodeDescriptionChanged(POPPEvent.NodeDescriptionChanged e) {
            bigraph.updateDescription(e.node().getId(), e.node().getDescription());
        }
        @Override protected void onNodeMoved(POPPEvent.NodeMoved e) {
            bigraph.updatePosition(e.node().getId(), e.node().getX(), e.node().getY());
        }
        @Override protected void onNodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {
            bigraph.rebuildDecomposition(e.node());
        }
        @Override protected void onNodeRemoved(POPPEvent.NodeRemoved e) { bigraph.deleteTreeNode(e.node().getId()); }
        @Override protected void onNodeChangedParent(POPPEvent.NodeChangedParent e) {
            bigraph.reparentTreeNode(e.node(), e.oldParent(), e.newParent());
        }
        @Override protected void onRelationCreated(POPPEvent.RelationCreated e) { bigraph.createRelation(e.relation()); }
        @Override protected void onRelationRemoved(POPPEvent.RelationRemoved e) { bigraph.removeRelation(e.relation()); }
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
}