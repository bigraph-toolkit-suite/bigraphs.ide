package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageTestModels.Synced;
import org.eclipse.glsp.example.bigraph.extension.popp.io.POPPJson;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a benchmark model ({@link BenchmarkSupport#model}) as a POPP JSON document, laid out as one column of trees
 * per node kind, so that the same model can be imported into the editor for the frontend memory measurement
 * ({@code benchmark-plots/measure-frontend-memory.sh}).
 * <p>
 * {@code ExportBenchmarkModel <depth> <out.json>}
 */
public final class ExportBenchmarkModel {
    private static final double COLUMN = 260, ROW = 90, LEAF = 160;

    public static void main(String[] args) throws IOException {
        int depth = Integer.parseInt(args[0]);
        Synced popp = BenchmarkSupport.model(depth);
        double x = 0;
        for (NodeKind kind : NodeKind.values()) {
            for (TreeNode<?> node : popp.model.getAllNodes()) {
                if (node.getKind() == kind && node.getParent().isEmpty()) x = layout(node, x, 0) + COLUMN;
            }
        }
        Files.writeString(Path.of(args[1]), POPPJson.write(popp.model));
        System.out.println("wrote " + popp.model.getAllNodes().size() + " nodes to " + args[1]);
    }

    /** Places the leaves of {@code node} side by side from {@code left}, each parent above its children. Returns the right edge. */
    private static double layout(TreeNode<?> node, double left, int level) {
        if (node.getChildren().isEmpty()) {
            node.move(left, level * ROW);
            return left + LEAF;
        }
        double right = left;
        for (TreeNode<?> child : node.getChildren()) right = layout(child, right, level + 1);
        node.move((left + right - LEAF) / 2, level * ROW);
        return right;
    }
}
