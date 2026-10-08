package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Model generators shared by the coverage analyzer tests. Not a test class. */
final class CoverageTestModels {
    private CoverageTestModels() {}

    /**
     * A balanced decomposition tree of success criteria (AND and OR alternating by level, root = AND), 2^depth
     * leaves, every proof produced by the one solution.
     *
     * @param proofs the proof of leaf i is {@code proofs.get(i)}; if not all leaves are proven the list is shorter
     */
    record Tree(SuccessCriteria root, List<SuccessCriteria> leaves, List<SuccessProof> proofs, Solution solution) {}

    /** With {@code proveAll} every leaf gets a proof, otherwise only the first one does. */
    static Tree balancedTree(POPPModel model, int depth, boolean proveAll) {
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