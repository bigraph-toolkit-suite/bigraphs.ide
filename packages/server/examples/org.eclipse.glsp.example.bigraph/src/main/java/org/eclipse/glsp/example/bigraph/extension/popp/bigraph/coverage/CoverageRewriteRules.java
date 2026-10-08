package org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage;


import org.bigraphs.framework.core.exceptions.InvalidConnectionException;
import org.bigraphs.framework.core.exceptions.InvalidReactionRuleException;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.core.reactivesystem.TrackingMap;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.AbstractCoverageSession;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.Coverage;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;

public final class CoverageRewriteRules {

    record P(String control, String link, List<P> kids) {
        static final P SITE = new P(null, null, List.of());
        boolean isSite() { return control == null; }
        static P n(String control, P... kids)              { return new P(control, null, List.of(kids)); }
        static P n(String control, String link, P... kids) { return new P(control, link, List.of(kids)); }
    }

    record Spec(String name, List<P> redex, List<P> reactum, Map<P, P> pairs) {
        public Spec(String name, List<P> redex, List<P> reactum) {
            this(name, redex, reactum, Map.of());
        }
    }

    private static Map<P, P> pair(P redexNode, P reactumNode) {
        Map<P, P> m = new IdentityHashMap<>();
        m.put(redexNode, reactumNode);
        return m;
    }

    private static final List<Coverage> ALL = List.of(Coverage.UNCOVERED, Coverage.PARTIAL, Coverage.COVERED);
    private static final List<Coverage> UPGRADABLE = List.of(Coverage.UNCOVERED, Coverage.PARTIAL);
    private static final List<Coverage> PARTIAL_OR_COVERED = List.of(Coverage.PARTIAL, Coverage.COVERED);

    private static final String TREE = POPPBigraphSignature.TREE_NODE.controlName();

    static List<Spec> specs() {
        List<Spec> out = new ArrayList<>();
        chainRules(out);
        liftRules(out);
        linkRules(out);
        return out;
    }

    private static P treeNode(P... kids) { return P.n(TREE, kids); }
    private static P state(Coverage c) { return P.n(CoverageMarker.of(c).controlName()); }

    private static P[] concat(List<P> head, P... tail) {
        List<P> all = new ArrayList<>(head);
        all.addAll(List.of(tail));
        return all.toArray(P[]::new);
    }
    private static String gate(DecompositionType t) {
        return (t == DecompositionType.AND ? POPPBigraphSignature.AND_DECOMP : POPPBigraphSignature.OR_DECOMP).controlName();
    }

    private static P why(Coverage target, boolean link) {
        boolean cov = target == Coverage.COVERED;
        CoverageMarker m = link ? (cov ? CoverageMarker.COV_LINK : CoverageMarker.PART_LINK)
                : (cov ? CoverageMarker.COV_DECOMP : CoverageMarker.PART_DECOMP);
        return link ? P.n(m.controlName(), "x") : P.n(m.controlName());
    }

    private static void chainRules(List<Spec> out) {
        for (boolean root : new boolean[]{true, false})
            for (DecompositionType type : List.of(DecompositionType.AND, DecompositionType.OR))
                for (Coverage cur : UPGRADABLE)
                    for (int i = 0; i < ALL.size(); i++) {
                        if (root) addChain(out, type, cur, true, List.of(ALL.get(i)));
                        for (int j = i; j < ALL.size(); j++)
                            addChain(out, type, cur, root, List.of(ALL.get(i), ALL.get(j)));
                    }
    }

    private static void addChain(List<Spec> out, DecompositionType type, Coverage cur, boolean root, List<Coverage> slots) {
        Coverage target = AbstractCoverageSession.aggregate(type, slots);
        if (target.compareTo(cur) <= 0) return;
        P[] slotPatterns = slots.stream().map(s -> treeNode(state(s), P.SITE)).toArray(P[]::new);
        P g = P.n(gate(type));

        List<P> kept = new ArrayList<>();
        if (root) kept.add(P.n(POPPBigraphSignature.CHAIN_ROOT.controlName()));
        if (cur == Coverage.PARTIAL) kept.add(P.n(CoverageMarker.PART_DECOMP.controlName()));

        List<P> redexKids = new ArrayList<>(List.of(state(cur)));
        redexKids.addAll(kept); redexKids.add(g);
        List<P> reactumKids = new ArrayList<>(List.of(state(target)));
        reactumKids.addAll(kept); reactumKids.add(why(target, false)); reactumKids.add(g);

        P redexNode   = treeNode(concat(redexKids, slotPatterns));
        P reactumNode = treeNode(concat(reactumKids, slotPatterns));
        out.add(new Spec("chain-" + (root ? "root-" : "") + type + "-" + cur + "-" + slots,
                List.of(redexNode), List.of(reactumNode), pair(redexNode, reactumNode)));
    }

    private static void collect(PureBigraph b, BigraphEntity<?> parent, List<P> ps, IdentityHashMap<P, String> out) {
        List<BigraphEntity.NodeEntity<?>> nodes = b.getChildrenOf(parent).stream()
                .filter(BigraphEntity.NodeEntity.class::isInstance)
                .map(e -> (BigraphEntity.NodeEntity<?>) e).collect(Collectors.toUnmodifiableList());
        List<P> pn = ps.stream().filter(p -> !p.isSite()).toList();
        for (int i = 0; i < pn.size(); i++) {
            out.put(pn.get(i), nodes.get(i).getName());
            collect(b, nodes.get(i), pn.get(i).kids(), out);
        }
    }

    private static IdentityHashMap<P, String> names(PureBigraph b, List<P> roots) {
        var out = new IdentityHashMap<P, String>();
        var rs = new ArrayList<>(b.getRoots());
        rs.sort(Comparator.comparing(BigraphEntity.RootEntity::getIndex));
        for (int i = 0; i < roots.size(); i++) collect(b, rs.get(i), List.of(roots.get(i)), out);
        return out;
    }

    static TrackingMap trackingMap(Spec s, PureBigraph redex, PureBigraph reactum) {
        var rn = names(redex, s.redex());
        var an = names(reactum, s.reactum());

        var partnerOf = new IdentityHashMap<P, P>();
        s.pairs().forEach((redexP, reactumP) -> partnerOf.put(reactumP, redexP));

        var tm = new TrackingMap();
        tm.setRuleName(s.name());
        an.forEach((reactumP, reactumName) -> {
            P redexP = rn.containsKey(reactumP) ? reactumP : partnerOf.get(reactumP);
            tm.put(reactumName, redexP != null && rn.containsKey(redexP) ? rn.get(redexP) : "");
        });
        return tm;
    }

    private static void liftRules(List<Spec> out) {
        for (DecompositionType type : List.of(DecompositionType.AND, DecompositionType.OR)) {
            for (Coverage s : PARTIAL_OR_COVERED) {
                for (Coverage c : UPGRADABLE) {
                    if (s.compareTo(c) <= 0) continue;
                    P chain = treeNode(state(s), P.n(POPPBigraphSignature.CHAIN_ROOT.controlName()), P.n(gate(type)), P.SITE);
                    P redexNode   = treeNode(state(c), P.SITE, chain);
                    P reactumNode = treeNode(state(s), why(s, false), P.SITE, chain);
                    out.add(new Spec("lift-" + type + "-" + c + "-" + s,
                            List.of(redexNode), List.of(reactumNode), pair(redexNode, reactumNode)));
                }
            }
        }
    }

    private static void linkRules(List<Spec> out) {
        for (RelationType t : RelationType.values()) {
            RelationType.CoveragePropagation dir = t.getCoveragePropagation();
            if (dir.hasNoCoveragePropagation()) continue;
            boolean fwd = dir == RelationType.CoveragePropagation.FORWARD;
            String giverStub = (fwd ? POPPBigraphSignature.outgoingStubFor(t) : POPPBigraphSignature.incomingStubFor(t)).controlName();
            String recvStub  = (fwd ? POPPBigraphSignature.incomingStubFor(t) : POPPBigraphSignature.outgoingStubFor(t)).controlName();

            for (Coverage g : PARTIAL_OR_COVERED) {
                for (Coverage c : UPGRADABLE) {
                    Coverage target = Coverage.best(c, g);
                    if (target.compareTo(c) <= 0) continue;

                    P giver = treeNode(state(g), P.n(giverStub, "x"), P.SITE);
                    P recv  = P.n(recvStub, "x");
                    P redexRecv   = treeNode(state(c), recv, P.SITE);
                    P reactumRecv = treeNode(state(target), why(target, true), recv, P.SITE);

                    out.add(new Spec("link-" + t + "-" + g + "-" + c,
                            List.of(giver, redexRecv), List.of(giver, reactumRecv), pair(redexRecv, reactumRecv)));
                }
            }
        }
    }

    public static List<ParametricReactionRule<PureBigraph>> build() {
        DynamicSignature sig = CoverageSignature.get();
        List<ParametricReactionRule<PureBigraph>> rules = new ArrayList<>();
        for (Spec s : specs()) {
            try {
                PureBigraph redex = side(sig, s.redex()), reactum = side(sig, s.reactum());
                var rule = new ParametricReactionRule<>(redex, reactum);
                rule.withTrackingMap(trackingMap(s, redex, reactum));
                rules.add(rule);
            } catch (InvalidReactionRuleException e) {
                throw new IllegalStateException("Invalid coverage rule " + s.name(), e);
            }
        }
        return List.copyOf(rules);
    }

    static PureBigraph side(DynamicSignature sig, List<P> roots) {
        var b = pureBuilder(sig);
        for (P p : roots) emit(b.root(), p);
        return b.create();
    }

    private static void emit(PureBigraphBuilder<DynamicSignature>.Hierarchy level, P p) {
        if (p.isSite()) {
            level.site();
            return;
        }
        PureBigraphBuilder<DynamicSignature>.Hierarchy self;
        try {
            self = p.link() == null ? level.child(p.control()) : level.child(p.control(), p.link());
        } catch (InvalidConnectionException e) {
            throw new RuntimeException(e);
        }
        if (!p.kids().isEmpty()) {
            var inner = self.down();
            p.kids().forEach(k -> emit(inner, k));
        }
    }

    /** Helper to visualize rules as text */
    static String toBigrapher(Spec s) {
        return "react " + ident(s.name()) + " =\n  " + side(s.redex()) + "\n  --> " + side(s.reactum()) + ";\n";
    }
    private static String ident(String n) {
        return n.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("_$", "");
    }
    private static String side(List<P> roots) {
        return roots.stream().map(CoverageRewriteRules::expr).collect(Collectors.joining(" || "));
    }
    private static String expr(P p) {
        if (p.isSite()) return "id";
        String head = p.control() + (p.link() == null ? "" : "{" + p.link() + "}");
        return p.kids().isEmpty() ? head
                : head + ".(" + p.kids().stream().map(CoverageRewriteRules::expr).collect(Collectors.joining(" | ")) + ")";
    }
}