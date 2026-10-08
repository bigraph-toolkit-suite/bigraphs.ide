package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.simulation.matching.AbstractBigraphMatcher;
import org.bigraphs.framework.simulation.matching.pure.PureReactiveSystem;
import org.eclipse.glsp.example.bigraph.BigraphDebugPrinter;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageMarker;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageRewriteRules;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

public final class RewritingCoverageAnalyzer implements CoverageAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(RewritingCoverageAnalyzer.class);

    private static final List<ParametricReactionRule<PureBigraph>> RULES = CoverageRewriteRules.build();

    private final Supplier<POPPBigraph> structure;
    private final RelationGraph relations;

    private final PureReactiveSystem system = new PureReactiveSystem();
    private final AbstractBigraphMatcher<PureBigraph> matcher = AbstractBigraphMatcher.create(PureBigraph.class);

    public RewritingCoverageAnalyzer(Supplier<POPPBigraph> structure, RelationGraph relations) {
        this.structure = structure;
        this.relations = relations;
    }

    @Override
    public CoverageSession session(CoverageMode mode) {
        Map<String, Coverage> states = new HashMap<>();
        Map<String, Derivation> why = new HashMap<>();
        int budget = 2 * structure.get().getBigraph().getNodes().size() + 8;

        for (boolean changed = true; changed; ) {
            changed = false;
            PureBigraph agent = CoverageBigraph.build(structure.get(), mode, states);
            for (var rule : RULES) {
                Optional<PureBigraph> next = rewriteOnce(agent, rule);
                if (next.isEmpty()) continue;
                if (--budget < 0) throw new IllegalStateException("Coverage rewriting did not terminate");

                Readout r = Readout.of(next.get());
                r.coverage().forEach((id, c) -> {
                    if (states.get(id) != c) {
                        Derivation d = r.derivation().get(id);
                        if (d != null) why.put(id, d); else why.remove(id);
                    }
                    states.put(id, c);
                });
                changed = true;
                break;
            }
        }

        Readout fin = Readout.of(CoverageBigraph.build(structure.get(), mode, states));
        Map<String, Derivation> derivation = new HashMap<>(fin.derivation());
        derivation.putAll(why);
        return new RewritingSession(mode, relations, new Readout(fin.coverage(), derivation));
    }

    private Optional<PureBigraph> rewriteOnce(PureBigraph agent, ParametricReactionRule<PureBigraph> rule) {
        for (var match : matcher.matchAll(agent, rule)) {
            return Optional.of(system.buildParametricReaction(agent, match, rule));
        }
        return Optional.empty();
    }

    private final class RewritingSession extends AbstractCoverageSession {
        private final Readout readout;

        RewritingSession(CoverageMode mode, RelationGraph relations, Readout readout) {
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
            Derivation d = readout.derivation().get(node.getId());

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

    record Derivation(CoverageMarker marker, String giverId) {}

    record Readout(Map<String, Coverage> coverage, Map<String, Derivation> derivation) {
        static Readout of(PureBigraph g) {
            Map<String, Coverage> cov = new HashMap<>();
            Map<String, Derivation> why = new HashMap<>();
            for (BigraphEntity.NodeEntity<?> cell : g.getNodes()) {
                Optional<Coverage> state = CoverageMarker.forNode(cell).flatMap(CoverageMarker::state);
                if (state.isEmpty()) continue;
                var owner = (BigraphEntity.NodeEntity<?>) g.getParent(cell);
                Derivation d = null;
                for (BigraphEntity<?> sib : g.getChildrenOf(owner)) {
                    if (!(sib instanceof BigraphEntity.NodeEntity<?> s)) continue;
                    String ctrl = s.getControl().getNamedType().stringValue();
                    if (ctrl.equals(POPPBigraphSignature.DOM.controlName())) { continue; }

                    Optional<CoverageMarker> m = CoverageMarker.forNode(s).filter(x -> x.belongsTo(state.get()));
                    if (m.isEmpty()) continue;
                    String giver = null;
                    if (m.get() == CoverageMarker.PART_LINK || m.get() == CoverageMarker.COV_LINK) {
                        var link = g.getLinkOfPoint(g.getPorts(s).get(0));
                        for (BigraphEntity<?> pt : g.getPointsFromLink(link)) {
                            if (!(pt instanceof BigraphEntity.Port port)) continue;
                            var pointNode = g.getNodeOfPort(port);
                            if (pointNode == null || pointNode == s) continue;
                            var pointOwner = g.getParent(pointNode);
                            if (pointOwner instanceof BigraphEntity.NodeEntity<?> po && !po.getName().equals(owner.getName())) {
                                giver = po.getName();
                            }
                        }
                    }
                    d = new Derivation(m.get(), giver);
                }

                cov.put(owner.getName(), state.get());
                if (d != null) why.put(owner.getName(), d);
            }
            return new Readout(cov, why);
        }
    }
}
