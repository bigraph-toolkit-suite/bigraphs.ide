package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/** Helpers shared by the concrete {@link CoverageSession}s, and the {@code gaps} implementation all analyzers use. */
public abstract class AbstractCoverageSession implements CoverageSession {

    protected final CoverageMode mode;
    protected final RelationGraph relations;

    public AbstractCoverageSession(CoverageMode mode, RelationGraph relations) {
        this.mode = mode;
        this.relations = relations;
    }

    /** A propagating relation of a node and the neighbour ({@code node}) that would give the coverage. */
    protected record Candidate(Relation relation, TreeNode<?> node) {}

    /**
     * Three-valued AND/OR over the children's coverage. AND is COVERED if all children are, OR if any child is;
     * otherwise PARTIAL if some child is at least PARTIAL, else UNCOVERED. NONE is UNCOVERED.
     * <p>
     * {@code children} must not be empty: for an empty list both AND and OR yield COVERED (vacuous truth). Callers
     * only aggregate nodes that have children, and the gate rules never list an empty gate node.
     */
    public static Coverage aggregate(DecompositionType type, List<Coverage> children) {
        boolean all = children.stream().allMatch(c -> c == Coverage.COVERED);
        boolean any = all || children.stream().anyMatch(c -> c == Coverage.COVERED);
        boolean some = children.stream().anyMatch(c -> c != Coverage.UNCOVERED);
        return switch (type) {
            case AND -> all ? Coverage.COVERED : some ? Coverage.PARTIAL : Coverage.UNCOVERED;
            case OR -> any ? Coverage.COVERED : some ? Coverage.PARTIAL : Coverage.UNCOVERED;
            case NONE -> Coverage.UNCOVERED;
        };
    }

    /** The relations coverage can flow into {@code node} through: incoming FORWARD and outgoing BACKWARD ones. */
    protected final List<Candidate> candidates(TreeNode<?> node) {
        List<Candidate> out = new ArrayList<>();
        relations.incoming(node).stream()
                .filter(r -> r.type().getCoveragePropagation() == RelationType.CoveragePropagation.FORWARD)
                .forEach(r -> out.add(new Candidate(r, r.source())));
        relations.outgoing(node).stream()
               .filter(r -> r.type().getCoveragePropagation() == RelationType.CoveragePropagation.BACKWARD)
               .forEach(r -> out.add(new Candidate(r, r.target())));
        return out;
    }

    /** A decomposition reason; for a COVERED node only the contributing children are explained. */
    protected final CoverageReason decompositionReason(TreeNode<?> node, Coverage decomposition) {
        List<? extends TreeNode<?>> children = node.getChildren();
        Predicate<TreeNode<?>> isCovered = n -> coverage(n) == Coverage.COVERED;
        List<? extends TreeNode<?>> shown = decomposition == Coverage.COVERED
                ? node.getDecompositionType().contributingChildren(children, isCovered)
                : children;
        return new CoverageReason.Decomposition(node, decomposition, node.getDecompositionType(),
                shown.stream().map(this::explain).toList());
    }

    protected final CoverageReason linkReason(TreeNode<?> node, Coverage c, Relation r, TreeNode<?> giver) {
        return new CoverageReason.Link(node, c, r, explain(giver));
    }

    @Override
    public CoverageGap.CoverageGaps gaps(Collection<? extends TreeNode<?>> scope) {
        List<CoverageGap> out = new ArrayList<>();
        for (TreeNode<?> node : scope) {
            Coverage c = coverage(node);
            if (c == Coverage.COVERED) {
                continue;
            }
            boolean root = !hasUncoveredBlocker(node);
            out.add(new CoverageGap(node, c, root, root ? CoverageGap.remediesFor(node.getKind()) : List.of()));
        }
        return new CoverageGap.CoverageGaps(List.copyOf(out));
    }

    /** Whether something beneath or behind this node is itself not covered. */
    protected boolean hasUncoveredBlocker(TreeNode<?> node) {
        for (TreeNode<?> child : node.getChildren()) {
            if (coverage(child) != Coverage.COVERED) return true;
        }
        for (Candidate candidate : candidates(node)) {
            if (coverage(candidate.node()) != Coverage.COVERED) return true;
        }
        return false;
    }

    /** The node a propagating relation covers (only meaningful for FORWARD and BACKWARD relations). */
    protected static TreeNode<?> receiverOf(Relation r) {
        return r.type().getCoveragePropagation() == RelationType.CoveragePropagation.FORWARD ? r.target() : r.source();
    }

    /** The node a propagating relation takes the coverage from (only meaningful for FORWARD and BACKWARD relations). */
    protected static TreeNode<?> giverOf(Relation r) {
        return r.type().getCoveragePropagation() == RelationType.CoveragePropagation.FORWARD ? r.source() : r.target();
    }
}