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
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;

/** Builds the coverage rules for the rewriting analyzers: one {@link CompiledRule} per state upgrade a match justifies. */
public final class CoverageRewriteRules {
    private static final Logger logger = LoggerFactory.getLogger(CoverageRewriteRules.class);

    /**
     * One element of a rule side, written as a bigraph term: a node with a control,
     * an optional link (outer name) and its children, or a site.
     */
    record Term(String control, String link, List<Term> kids) {
        static final Term SITE = new Term(null, null, List.of());
        boolean isSite() { return control == null; }
        static Term node(String control, Term... kids)              { return new Term(control, null, List.of(kids)); }
        static Term node(String control, String link, Term... kids) { return new Term(control, link, List.of(kids)); }
    }

    /**
     * What a rule does, stated once and explicitly: the redex node whose state cell is upgraded
     * ({@code receiver}), the redex node it takes the coverage from over a link ({@code giver}, only link rules),
     * the state it is upgraded to and the reason marker that justifies it.
     * The schedule uses it (receiver kind, link rule or not); the reactum does the same by rewriting.
     */
    record Effect(Term receiver, Term giver, Coverage target, CoverageMarker reason,
                  RelationType relation, Coverage giverState, Coverage receiverState) {
        /** Non-link rules: no relation guard. */
        public Effect(Term receiver, Term giver, Coverage target, CoverageMarker reason) {
            this(receiver, giver, target, reason, null, null, null);
        }
    }

    /**
     * A rule as terms: redex and reactum roots, {@code pairs} maps a redex term to the reactum term that keeps its
     * identity (for the tracking map), and the rule's {@link Effect}.
     */
    record RuleSpecification(String name, List<Term> redex, List<Term> reactum, Map<Term, Term> pairs, Effect effect) {}

    /**
     * A rule ready for matching. {@code receiverNode} / {@code giverNode} are node names in the redex
     * bigraph; look them up in the match's redex-to-agent node embedding to find the agent nodes.
     */
    public record CompiledRule(String name, ParametricReactionRule<PureBigraph> rule,
                               String receiverNode, String giverNode,
                               Coverage target, CoverageMarker reason,
                               RelationType relation, Coverage giverState, Coverage receiverState) {
        /** Whether this is a link rule, i.e. has a relation type; place rules (chain, lift) have none. */
        public boolean isLinkRule() { return relation != null; }
    }

    private static Map<Term, Term> pair(Term redexNode, Term reactumNode) {
        Map<Term, Term> m = new IdentityHashMap<>();
        m.put(redexNode, reactumNode);
        return m;
    }

    private static final List<Coverage> ALL = List.of(Coverage.UNCOVERED, Coverage.PARTIAL, Coverage.COVERED);
    private static final List<Coverage> UPGRADABLE = List.of(Coverage.UNCOVERED, Coverage.PARTIAL);
    private static final List<Coverage> PARTIAL_OR_COVERED = List.of(Coverage.PARTIAL, Coverage.COVERED);

    /**
     * The coverage rules for the per-kind encoding of {@link POPPBigraphSignature}. A redex cannot say "any tree
     * node", so every pattern that mentions a tree node is written once per control that node can have. The
     * expansions are the minimal sound set, i.e. only combinations that can occur in a model:
     * <ul>
     *   <li><b>chain rules</b>: a chain node holds its state, {@code PartDecomp} when it is PARTIAL, and its slots
     *       exactly (no site, so the slot count is exact). A slot is a domain node of kind K ({@link NodeKind}) or the next chain node;
     *       all domain slots of one chain share the kind K of the decomposed node (a tree holds a single kind), so per
     *       kind: one K slot, two K slots, or one K slot and one chain-node slot;</li>
     *   <li><b>lift rules</b>: a domain node of kind K ({@link NodeKind}) takes the state of its chain node. The chain node directly
     *       under a domain node is always the first one of the chain, so no extra marker is needed;</li>
     *   <li><b>link rules</b>: per supported (from, to) kind pair of the {@link RelationType}.</li>
     * </ul>
     */
    static List<RuleSpecification> specs() {
        List<RuleSpecification> out = new ArrayList<>();
        chainRules(out);
        liftRules(out);
        linkRules(out);
        return out;
    }

    private static Term state(Coverage c) { return Term.node(CoverageMarker.of(c).controlName()); }

    private static String kind(NodeKind k) { return POPPBigraphSignature.getByNodeKind(k).controlName(); }

    private static String gate(DecompositionType t, NodeKind k) {
        return POPPBigraphSignature.getChainControl(k, t).controlName();
    }

    private static CoverageMarker whyMarker(Coverage target, boolean link) {
        boolean cov = target == Coverage.COVERED;
        return link ? (cov ? CoverageMarker.COV_LINK : CoverageMarker.PART_LINK)
                : (cov ? CoverageMarker.COV_DECOMP : CoverageMarker.PART_DECOMP);
    }

    private static Term why(Coverage target, boolean link) {
        CoverageMarker m = whyMarker(target, link);
        return link ? Term.node(m.controlName(), "x") : Term.node(m.controlName());
    }

    /** One slot of a chain node: a node with {@code control} in {@code state}, and whatever else it contains. */
    private record Slot(String control, Coverage state) {}

    private static void chainRules(List<RuleSpecification> out) {
        for (DecompositionType type : List.of(DecompositionType.AND, DecompositionType.OR)) {
            for (Coverage cur : UPGRADABLE) {
                for (NodeKind nk : NodeKind.values()) {
                    String k = kind(nk);
                    String g = gate(type, nk);
                    // one child
                    for (Coverage s : ALL) addChain(out, type, cur, nk, List.of(new Slot(k, s)));
                    // two domain children
                    for (int i = 0; i < ALL.size(); i++)
                        for (int j = i; j < ALL.size(); j++)
                            addChain(out, type, cur, nk, List.of(new Slot(k, ALL.get(i)), new Slot(k, ALL.get(j))));
                    // one domain child and the next chain node
                    for (Coverage a : ALL)
                        for (Coverage b : ALL)
                            addChain(out, type, cur, nk, List.of(new Slot(k, a), new Slot(g, b)));
                }
            }
        }
    }

    private static void addChain(List<RuleSpecification> out, DecompositionType type, Coverage cur, NodeKind nk, List<Slot> slots) {
        Coverage target = AbstractCoverageSession.aggregate(type, slots.stream().map(Slot::state).toList());
        if (target.compareTo(cur) <= 0) return;
        Term[] slotPatterns = slots.stream().map(s -> Term.node(s.control(), state(s.state()), Term.SITE)).toArray(Term[]::new);

        List<Term> kept = new ArrayList<>();
        if (cur == Coverage.PARTIAL) kept.add(Term.node(CoverageMarker.PART_DECOMP.controlName()));

        List<Term> redexKids = new ArrayList<>(List.of(state(cur)));
        redexKids.addAll(kept);
        redexKids.addAll(List.of(slotPatterns));
        List<Term> reactumKids = new ArrayList<>(List.of(state(target)));
        reactumKids.addAll(kept);
        reactumKids.add(why(target, false));
        reactumKids.addAll(List.of(slotPatterns));

        Term redexNode = Term.node(gate(type, nk), redexKids.toArray(Term[]::new));
        Term reactumNode = Term.node(gate(type, nk), reactumKids.toArray(Term[]::new));
        String name = "chain-" + type + "-" + nk + "-" + cur + "-"
                + slots.stream().map(s -> s.control() + "_" + s.state()).collect(Collectors.joining("+"));
        out.add(new RuleSpecification(name, List.of(redexNode), List.of(reactumNode), pair(redexNode, reactumNode),
                new Effect(redexNode, null, target, whyMarker(target, false))));
    }

    /**
     * Pairs each non-site term with the bigraph node built from it. Relies on {@link #side} adding the children of a
     * node in term order and on {@code getChildrenOf} returning them in that order (true for the framework's builder).
     */
    private static void collect(PureBigraph b, BigraphEntity<?> parent, List<Term> terms, IdentityHashMap<Term, String> out) {
        List<BigraphEntity.NodeEntity<?>> nodes = b.getChildrenOf(parent).stream()
                .filter(BigraphEntity.NodeEntity.class::isInstance)
                .map(e -> (BigraphEntity.NodeEntity<?>) e).collect(Collectors.toUnmodifiableList());
        List<Term> pn = terms.stream().filter(term -> !term.isSite()).toList();
        for (int i = 0; i < pn.size(); i++) {
            out.put(pn.get(i), nodes.get(i).getName());
            collect(b, nodes.get(i), pn.get(i).kids(), out);
        }
    }

    /** Term -> generated node name in {@code b}, which was built from {@code roots} by {@link #side}. */
    private static IdentityHashMap<Term, String> names(PureBigraph b, List<Term> roots) {
        var out = new IdentityHashMap<Term, String>();
        var rs = new ArrayList<>(b.getRoots());
        rs.sort(Comparator.comparing(BigraphEntity.RootEntity::getIndex));
        for (int i = 0; i < roots.size(); i++) collect(b, rs.get(i), List.of(roots.get(i)), out);
        return out;
    }

    /** Reactum node name -> redex node name it keeps (empty for new nodes, i.e. the added markers). */
    static TrackingMap trackingMap(RuleSpecification s, PureBigraph redex, PureBigraph reactum) {
        var rn = names(redex, s.redex());
        var an = names(reactum, s.reactum());

        var partnerOf = new IdentityHashMap<Term, Term>();
        s.pairs().forEach((redexTerm, reactumTerm) -> partnerOf.put(reactumTerm, redexTerm));

        var tm = new TrackingMap();
        tm.setRuleName(s.name());
        an.forEach((reactumTerm, reactumName) -> {
            Term redexTerm = rn.containsKey(reactumTerm) ? reactumTerm : partnerOf.get(reactumTerm);
            tm.put(reactumName, redexTerm != null && rn.containsKey(redexTerm) ? rn.get(redexTerm) : "");
        });
        return tm;
    }

    private static void liftRules(List<RuleSpecification> out) {
        for (DecompositionType type : List.of(DecompositionType.AND, DecompositionType.OR)) {
            for (NodeKind nk : NodeKind.values()) {
                String k = kind(nk);
                for (Coverage s : PARTIAL_OR_COVERED) {
                    for (Coverage c : UPGRADABLE) {
                        if (s.compareTo(c) <= 0) continue;
                        Term chain = Term.node(gate(type, nk), state(s), Term.SITE); // the chain node directly under a domain node
                        Term redexNode = Term.node(k, state(c), Term.SITE, chain);
                        Term reactumNode = Term.node(k, state(s), why(s, false), Term.SITE, chain);
                        out.add(new RuleSpecification("lift-" + type + "-" + nk + "-" + c + "-" + s,
                                List.of(redexNode), List.of(reactumNode), pair(redexNode, reactumNode),
                                new Effect(redexNode, null, s, whyMarker(s, false))));
                    }
                }
            }
        }
    }

    private static void linkRules(List<RuleSpecification> out) {
        for (RelationType t : RelationType.values()) {
            RelationType.CoveragePropagation dir = t.getCoveragePropagation();
            if (dir.hasNoCoveragePropagation()) continue;
            boolean fwd = dir == RelationType.CoveragePropagation.FORWARD;
            String giverStub = (fwd ? POPPBigraphSignature.outgoingStubFor(t) : POPPBigraphSignature.incomingStubFor(t)).controlName();
            String recvStub = (fwd ? POPPBigraphSignature.incomingStubFor(t) : POPPBigraphSignature.outgoingStubFor(t)).controlName();

            for (RelationType.NodeTypePair kinds : t.getSupportedNodeTypePair()) {
                String giverKind = kind(fwd ? kinds.from() : kinds.to());
                String recvKind = kind(fwd ? kinds.to() : kinds.from());
                for (Coverage g : PARTIAL_OR_COVERED) {
                    for (Coverage c : UPGRADABLE) {
                        Coverage target = Coverage.best(c, g);
                        if (target.compareTo(c) <= 0) continue;

                        Term giver = Term.node(giverKind, state(g), Term.node(giverStub, "x"), Term.SITE);
                        Term recv = Term.node(recvStub, "x");
                        Term redexRecv = Term.node(recvKind, state(c), recv, Term.SITE);
                        Term reactumRecv = Term.node(recvKind, state(target), why(target, true), recv, Term.SITE);

                        out.add(new RuleSpecification("link-" + t + "-" + kinds.from() + "-" + kinds.to() + "-" + g + "-" + c,
                                List.of(giver, redexRecv), List.of(giver, reactumRecv), pair(redexRecv, reactumRecv),
                                new Effect(redexRecv, giver, target, whyMarker(target, true), t, g, c)));
                    }
                }
            }
        }
    }

    /** The coverage rules, each with where to find receiver and giver in a match; used by both rewriting analyzers. */
    public static List<CompiledRule> compile() {
        DynamicSignature sig = CoverageSignature.get();
        List<CompiledRule> out = new ArrayList<>();
        for (RuleSpecification s : specs()) {
            Effect fx = Objects.requireNonNull(s.effect(), "Spec without effect: " + s.name());
            try {
                PureBigraph redex = side(sig, s.redex()), reactum = side(sig, s.reactum());
                ParametricReactionRule<PureBigraph> rule = new ParametricReactionRule<>(redex, reactum);
                // buildParametricReaction names the reactum nodes after the agent nodes the tracked redex nodes matched,
                // so domain nodes keep their ids (the incremental analyzer and the readout rely on it).
                rule.withTrackingMap(trackingMap(s, redex, reactum));
                var redexNames = names(redex, s.redex());
                out.add(new CompiledRule(s.name(), rule,
                        redexNames.get(fx.receiver()),
                        fx.giver() == null ? null : redexNames.get(fx.giver()),
                        fx.target(), fx.reason(), fx.relation(), fx.giverState(), fx.receiverState()));
            } catch (InvalidReactionRuleException e) {
                throw new IllegalStateException("Invalid coverage rule " + s.name(), e);
            }
            logger.debug(toBigrapher(s));
        }
        return List.copyOf(out);
    }

    static PureBigraph side(DynamicSignature sig, List<Term> roots) {
        var b = pureBuilder(sig);
        for (Term term : roots) emit(b.root(), term);
        return b.create();
    }

    private static void emit(PureBigraphBuilder<DynamicSignature>.Hierarchy level, Term term) {
        if (term.isSite()) {
            level.site();
            return;
        }
        PureBigraphBuilder<DynamicSignature>.Hierarchy self;
        try {
            self = term.link() == null ? level.child(term.control()) : level.child(term.control(), term.link());
        } catch (InvalidConnectionException e) {
            throw new RuntimeException(e);
        }
        if (!term.kids().isEmpty()) {
            var inner = self.down();
            term.kids().forEach(k -> emit(inner, k));
        }
    }

    /** The rule in BigraphER syntax, for logging and inspection. */
    static String toBigrapher(RuleSpecification s) {
        return "react " + ident(s.name()) + " =\n  " + side(s.redex()) + "\n  --> " + side(s.reactum()) + ";\n";
    }
    private static String ident(String n) {
        return n.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("_$", "");
    }
    private static String side(List<Term> roots) {
        return roots.stream().map(CoverageRewriteRules::expr).collect(Collectors.joining(" || "));
    }
    private static String expr(Term term) {
        if (term.isSite()) return "id";
        String head = term.control() + (term.link() == null ? "" : "{" + term.link() + "}");
        return term.kids().isEmpty() ? head
                : head + ".(" + term.kids().stream().map(CoverageRewriteRules::expr).collect(Collectors.joining(" | ")) + ")";
    }
}