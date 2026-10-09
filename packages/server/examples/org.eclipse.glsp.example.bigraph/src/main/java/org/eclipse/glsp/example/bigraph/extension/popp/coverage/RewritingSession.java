package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageMarker;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

/**
 * Session over the result of a rewriting analysis: the coverage state and the derivation (reason marker and giver)
 * of every node, as recorded by {@link RewritingCoverageAnalyzer} or {@link FragmentRewritingCoverageAnalyzer}.
 */
final class RewritingSession extends AbstractCoverageSession {
    private final RewritingCoverageAnalyzer.Readout readout;

    RewritingSession(CoverageMode mode, RelationGraph relations, RewritingCoverageAnalyzer.Readout readout) {
        super(mode, relations);
        this.readout = readout;
    }

    @Override
    public Coverage coverage(TreeNode<?> node) {
        Coverage c = readout.coverage().get(node.getId());
        if (c == null) throw new IllegalStateException("Node missing from the coverage bigraph: " + node);
        return c;
    }

    @Override
    public CoverageReason explain(TreeNode<?> node) {
        Coverage state = coverage(node);
        RewritingCoverageAnalyzer.Derivation d = readout.derivation().get(node.getId());

        if (d != null) {
            return switch (d.marker()) {
                case CoverageMarker.COV_INTRINSIC -> new CoverageReason.Intrinsic(node, Coverage.COVERED);
                case CoverageMarker.PART_DECOMP, CoverageMarker.COV_DECOMP -> decompositionReason(node, state);
                case CoverageMarker.PART_LINK, CoverageMarker.COV_LINK -> {
                    Relation r = relations.all().stream()
                            .filter(x -> giverOf(x).getId().equals(d.giverId())
                                    && receiverOf(x).getId().equals(node.getId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("No relation from " + d.giverId() + " to " + node.getId()));
                    yield linkReason(node, state, r, giverOf(r));
                }
                default -> throw new IllegalStateException("Not a reason marker: " + d.marker());
            };
        }

        if (!node.getChildren().isEmpty()) return decompositionReason(node, Coverage.UNCOVERED);
        return candidates(node).stream().findFirst()
                .<CoverageReason>map(c -> linkReason(node, Coverage.UNCOVERED, c.relation(), c.node()))
                .orElseGet(() -> new CoverageReason.NotCovered(node, CoverageGap.remediesFor(node.getKind())));
    }
}
