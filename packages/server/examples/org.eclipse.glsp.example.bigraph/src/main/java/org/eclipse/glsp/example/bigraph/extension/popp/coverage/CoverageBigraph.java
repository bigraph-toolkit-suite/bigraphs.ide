package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;

import java.util.HashMap;
import java.util.Map;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;

/**
 * The coverage bigraph: a copy of the structure bigraph over {@link CoverageSignature} in which every domain node and
 * every gate node holds a state cell ({@code Unc}, {@code Part} or {@code Cov}). Tree nodes of an intrinsically
 * covered kind start covered and hold {@code CovIntrinsic}: the domain nodes by definition, and their gate nodes
 * because the gate controls are per kind, so all their slots are covered domain nodes of that kind or gate nodes of
 * that tree. Every other tree node starts with {@code Unc}. Stubs and relation edges are copied unchanged. Rewriting
 * adds the reason markers of the upgrades; {@code IncrementalRewritingCoverageAnalyzer} keeps one such bigraph per mode and
 * edits it in place.
 */
public class CoverageBigraph {
    /**
     * Builds the coverage bigraph of {@code source} at the start of the evaluation in {@code mode}. Holds the lock of
     * {@code source} while reading it: reading a framework bigraph fills lazy caches (ports, link points) that are not
     * thread-safe, and the modes of a report are evaluated in parallel. The result is an independent copy.
     */
    public static PureBigraphMutable build(POPPBigraph source, CoverageMode mode) {
        synchronized (source) {
            return copy(source, mode);
        }
    }

    private static PureBigraphMutable copy(POPPBigraph source, CoverageMode mode) {
       DynamicSignature sig = CoverageSignature.get();
       PureBigraphMutable src = source.getBigraph();
       PureBigraphMutable g = pureBuilder(sig).createMutable();
       Map<String, BigraphEntity.NodeEntity<DynamicControl>> copies = new HashMap<>();

       for (BigraphEntity.RootEntity root : src.getRoots()) {
           copyPlaces(src, root, g.addRoot(), g, sig, copies);
       }
       copyEdges(src, g, copies);
       annotate(source, g, sig, mode, copies);
       return g;
    }

    /**
     * Whether a tree node with {@code control} (a domain or a gate control) is covered from the start in {@code mode}:
     * its kind, or the kind of its tree, is intrinsically covered.
     */
    public static boolean isIntrinsic(POPPBigraphSignature control, CoverageMode mode) {
        if (POPPBigraphSignature.isKindControl(control)) return mode.isIntrinsic(POPPBigraphSignature.toNodeKind(control));
        return POPPBigraphSignature.gateKindOf(control).map(mode::isIntrinsic).orElse(false);
    }

    /** Copies the place graph. */
    private static void copyPlaces(PureBigraphMutable src, BigraphEntity<?> oldParent, BigraphEntity<?> newParent,
                                  PureBigraphMutable g, DynamicSignature sig,
                                  Map<String, BigraphEntity.NodeEntity<DynamicControl>> copies) {
        for (BigraphEntity<?> child : src.getChildrenOf(oldParent)) {
            if (!(child instanceof BigraphEntity.NodeEntity<?> raw)) continue;
            var old = (BigraphEntity.NodeEntity<DynamicControl>) raw;
            var copy = g.addNode(newParent, sig.getControlByName(old.getControl().getNamedType().stringValue()), old.getName());
            copies.put(old.getName(), copy);
            copyPlaces(src, old, copy, g, sig, copies);
        }
    }

    private static void copyEdges(PureBigraphMutable src, PureBigraphMutable g,
                                  Map<String, BigraphEntity.NodeEntity<DynamicControl>> copies) {
        Map<String, BigraphEntity.Edge> edges = new HashMap<>();
        for (BigraphEntity.Edge e : src.getEdges()) edges.put(e.getName(), g.addEdge(e.getName()));
        for (BigraphEntity.NodeEntity<DynamicControl> old : src.getNodes()) {
            for (BigraphEntity.Port port : src.getPorts(old)) {
                if (src.getLinkOfPoint(port) instanceof BigraphEntity.Edge e) {
                    g.connectNodeToLink(copies.get(old.getName()), edges.get(e.getName()));
                }
            }
        }
    }

    private static void annotate(POPPBigraph source, PureBigraphMutable g, DynamicSignature sig, CoverageMode mode,
                                 Map<String, BigraphEntity.NodeEntity<DynamicControl>> copies) {
        for (BigraphEntity.NodeEntity<DynamicControl> old : source.getBigraph().getNodes()) {
            POPPBigraphSignature control = POPPBigraphSignature.forNode(old);
            if (!POPPBigraphSignature.isTreeControl(control)) continue; // container and stubs have no state
            var copy = copies.get(old.getName());
            if (isIntrinsic(control, mode)) {
                cell(g, sig, copy, CoverageMarker.COV, ".state");
                cell(g, sig, copy, CoverageMarker.COV_INTRINSIC, ".why");
            } else {
                cell(g, sig, copy, CoverageMarker.UNC, ".state");
            }
        }
    }

    private static void cell(PureBigraphMutable g, DynamicSignature sig,
                             BigraphEntity.NodeEntity<DynamicControl> owner, CoverageMarker m, String suffix) {
        g.addNode(owner, sig.getControlByName(m.controlName()), owner.getName() + suffix);
    }
}
