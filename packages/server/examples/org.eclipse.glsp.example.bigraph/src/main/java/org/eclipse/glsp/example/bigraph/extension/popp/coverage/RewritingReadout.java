package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;

import java.util.*;

/** State and derivation per domain node id. Gate nodes are internal and not included. */
record RewritingReadout(Map<String, Coverage> coverage, Map<String, Derivation> derivation) {
    /** Why a node has its state: the reason marker of the rule that set it, and the giver for link rules. */
    record Derivation(CoverageMarker marker, String giverId) {}

    /**
     * Reads states and reasons off a bigraph the rules were <i>applied</i> to (the fixpoint of a rewriting
     * session), by one walk over its nodes. It carries {@code CovIntrinsic} from the start and the reason markers the
     * reactums added: per domain node the marker that belongs to its state ({@code CovDecomp}, {@code PartLink},
     * ..., a node upgraded twice keeps both, only the one for the final state counts). A link marker
     * ({@code CovLink}/{@code PartLink}) is connected to the relation edge it was derived over, so the giver is the
     * domain node owning the other relation stub on that link.
     */
    static RewritingReadout ofRewritten(PureBigraph g) {
        Map<String, Coverage> cov = new HashMap<>();
        Map<String, Derivation> why = new HashMap<>();
        for (BigraphEntity.NodeEntity<?> owner : g.getNodes()) {
            if (!POPPBigraphSignature.isKindControl(POPPBigraphSignature.forNode(owner))) continue;
            Coverage state = null;
            List<BigraphEntity.NodeEntity<?>> markers = new ArrayList<>();
            for (BigraphEntity<?> c : g.getChildrenOf(owner)) {
                if (!(c instanceof BigraphEntity.NodeEntity<?> n)) continue;
                Optional<CoverageMarker> m = CoverageMarker.forNode(n);
                if (m.isEmpty()) continue;
                if (m.get().state().isPresent()) state = m.get().state().get();
                else markers.add(n);
            }
            if (state == null) continue;
            cov.put(owner.getName(), state);
            for (BigraphEntity.NodeEntity<?> n : markers) {
                CoverageMarker m = CoverageMarker.forNode(n).orElseThrow();
                if (!m.belongsTo(state)) continue;
                String giver = m.arity() == 0 ? null : giverOverLink(g, owner, n);
                why.put(owner.getName(), new Derivation(m, giver));
            }
        }
        return new RewritingReadout(cov, why);
    }

    /** The domain node owning the other relation stub on the link of the reason marker {@code marker}. */
    private static String giverOverLink(PureBigraph g, BigraphEntity.NodeEntity<?> receiver,
                                        BigraphEntity.NodeEntity<?> marker) {
        for (BigraphEntity.Port port : g.getPorts(marker)) {
            BigraphEntity.Link link = (BigraphEntity.Link) g.getLinkOfPoint(port);
            if (link == null) continue;
            for (BigraphEntity<?> point : g.getPointsFromLink(link)) {
                if (!(point instanceof BigraphEntity.Port p)) continue;
                BigraphEntity.NodeEntity<?> stub = g.getNodeOfPort(p);
                if (CoverageMarker.forNode(stub).isPresent()) continue;     // the marker itself
                BigraphEntity<?> owner = g.getParent(stub);
                if (owner instanceof BigraphEntity.NodeEntity<?> o && !o.getName().equals(receiver.getName())) {
                    return o.getName();
                }
            }
        }
        throw new IllegalStateException("Link marker of " + receiver.getName() + " is not linked to a giver");
    }
}
