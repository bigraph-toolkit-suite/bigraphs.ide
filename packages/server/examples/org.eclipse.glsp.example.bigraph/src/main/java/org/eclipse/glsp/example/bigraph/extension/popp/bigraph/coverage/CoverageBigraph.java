package org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.Coverage;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageMode;

import java.util.HashMap;
import java.util.Map;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;

public class CoverageBigraph {
    public static PureBigraphMutable build(POPPBigraph source, CoverageMode mode, Map<String, Coverage> states) {
       DynamicSignature sig = CoverageSignature.get();
       PureBigraphMutable src = source.getBigraph();
       PureBigraphMutable g = pureBuilder(sig).createMutable();
       Map<String, BigraphEntity.NodeEntity<DynamicControl>> copies = new HashMap<>();

       for (BigraphEntity.RootEntity root : src.getRoots()) {
           copyPlaces(src, root, g.addRoot(), g, sig, copies);
       }
       copyEdges(src, g, copies);
       annotate(source, g, sig, mode, copies, states);
       return g;
    }

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
                                 Map<String, BigraphEntity.NodeEntity<DynamicControl>> copies,
                                 Map<String, Coverage> states) {
        for (BigraphEntity.NodeEntity<DynamicControl> old : source.getBigraph().getNodes()) {
            if (POPPBigraphSignature.forNode(old) != POPPBigraphSignature.TREE_NODE) continue;
            var role = source.roleOf(old).orElseThrow();
            boolean intrinsic = role == POPPBigraphSignature.DOM
                    && source.kindOf(old).orElseThrow().isIntrinsicallyCovered(mode);

            Coverage state = intrinsic ? Coverage.COVERED
                    : states.getOrDefault(old.getName(), Coverage.UNCOVERED);
            var copy = copies.get(old.getName());
            cell(g, sig, copy, CoverageMarker.of(state), ".state");
            if (intrinsic) cell(g, sig, copy, CoverageMarker.COV_INTRINSIC, ".why");
            // chain redexes list PartDecomp explicitly when the chain node is PARTIAL
            if (role != POPPBigraphSignature.DOM && state == Coverage.PARTIAL)
                cell(g, sig, copy, CoverageMarker.PART_DECOMP, ".partwhy");
        }
    }

    private static void cell(PureBigraphMutable g, DynamicSignature sig,
                             BigraphEntity.NodeEntity<DynamicControl> owner, CoverageMarker m, String suffix) {
        g.addNode(owner, sig.getControlByName(m.controlName()), owner.getName() + suffix);
    }
}
