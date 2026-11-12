package org.eclipse.glsp.example.bigraph.extension.popp.coverage;


import org.bigraphs.framework.core.exceptions.InvalidConnectionException;
import org.bigraphs.framework.core.exceptions.InvalidReactionRuleException;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.core.reactivesystem.TrackingMap;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;

/**
 * Generates the coverage rules: states each rule as terms ({@link RuleSpecification}) and compiles it to a reaction rule
 * of the framework ({@link CompiledRule}). What the analyzers derive from the compiled rules (guards, kinds, the rules
 * of a mode) is in {@link CoverageRuleSet}.
 */
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
     * Where a rule's effect lies, which the analyzers need without reading the bigraphs: the redex node it raises
     * ({@code receiver}) and, for a link rule, the redex node it takes the coverage from ({@code giver}) over a
     * relation of type {@code relation}.
     */
    record Effect(Term receiver, Term giver, RelationType relation) {
        /** A gate or lift rule: no giver and no relation. */
        static Effect place(Term receiver) {
            return new Effect(receiver, null, null);
        }
    }

    /**
     * A rule as terms: redex and reactum roots, {@code pairs} maps a redex term to the reactum term that keeps its
     * identity (for the tracking map), and the rule's {@link Effect}.
     */
    record RuleSpecification(String name, List<Term> redex, List<Term> reactum, Map<Term, Term> pairs, Effect effect) {}

    /**
     * A rule ready for matching, with its {@link Effect}: {@code receiverNode} and {@code giverNode} are node names in
     * the redex.
     */
    public record CompiledRule(String name, ParametricReactionRule<PureBigraph> rule,
                               String receiverNode, String giverNode, RelationType relation) {
        /** Whether this is a link rule, i.e. has a relation type. Place rules (gate, lift) have none. */
        public boolean isLinkRule() { return relation != null; }
    }

    private static Map<Term, Term> pair(Term redexNode, Term reactumNode) {
        Map<Term, Term> map = new IdentityHashMap<>();
        map.put(redexNode, reactumNode);
        return map;
    }

    private static final List<Coverage> UPGRADABLE = List.of(Coverage.UNCOVERED, Coverage.PARTIAL);
    private static final List<Coverage> PARTIAL_OR_COVERED = List.of(Coverage.PARTIAL, Coverage.COVERED);

    /**
     * The coverage rules for the per-kind encoding of {@link POPPBigraphSignature}. A redex cannot say "any tree
     * node", so every pattern that mentions a tree node is written once per control that node can have. The
     * expansions are the minimal sound set, i.e. only combinations that can occur in a model:
     * <ul>
     *   <li><b>gate rules</b> ({@link #thresholdGateRules}): a gate node holds its state, {@code PartDecomp} when it is
     *       PARTIAL, and its slots. A slot is a domain node of kind K ({@link NodeKind}) or the next gate node. All domain
     *       slots of one decomposition chain share the kind K of the decomposed node (a tree holds a single kind).</li>
     *   <li><b>lift rules</b>: a domain node of kind K ({@link NodeKind}) takes the state of its gate node. The gate node directly
     *       under a domain node is always the topmost gate node of the chain, so no extra marker is needed.</li>
     *   <li><b>link rules</b>: per supported (from, to) kind pair of the {@link RelationType}.</li>
     * </ul>
     */
    static List<RuleSpecification> specs() {
        List<RuleSpecification> out = new ArrayList<>();
        thresholdGateRules(out);
        liftRules(out);
        linkRules(out);
        return out;
    }

    private static Term state(Coverage coverage) { return Term.node(CoverageMarker.of(coverage).controlName()); }

    private static String kind(NodeKind kind) { return POPPBigraphSignature.of(kind).controlName(); }

    private static String gate(DecompositionType decompositionType, NodeKind kind) {
        return POPPBigraphSignature.gate(kind, decompositionType).controlName();
    }

    private static CoverageMarker whyMarker(Coverage target, boolean link) {
        boolean covered = target == Coverage.COVERED;
        return link ? (covered ? CoverageMarker.COV_LINK : CoverageMarker.PART_LINK)
                : (covered ? CoverageMarker.COV_DECOMP : CoverageMarker.PART_DECOMP);
    }

    private static Term why(Coverage target, boolean link) {
        CoverageMarker marker = whyMarker(target, link);
        return link ? Term.node(marker.controlName(), "x") : Term.node(marker.controlName());
    }

    /** One slot of a gate node: a node with {@code control} in {@code state}, and whatever else it contains. */
    private record Slot(String control, Coverage state) {}

    /**
     * The gate rules as thresholds. The aggregation is monotone, so a gate node can be upgraded as soon as enough of
     * its slots are: OR is COVERED once one slot is, PARTIAL once one slot is PARTIAL (or better). AND is PARTIAL once
     * one slot is PARTIAL or COVERED. Those rules name the one slot and leave the rest of the gate node to a site,
     * instead of one rule per combination of all slot states. Only AND to COVERED needs every slot and keeps the exact
     * shapes (one K slot, two K slots, a K slot and the next gate node). This gives the same fixpoint as one rule per
     * combination of slot states, with fewer rules, and a slot that changes state enables only the thresholds of its
     * new state.
     */
    private static void thresholdGateRules(List<RuleSpecification> out) {
        for (NodeKind nk : NodeKind.values()) {
            String kindControl = kind(nk);
            for (DecompositionType type : List.of(DecompositionType.AND, DecompositionType.OR)) {
                String gateControl = gate(type, nk);
                for (String slot : List.of(kindControl, gateControl)) {
                    if (type == DecompositionType.OR) {
                        for (Coverage cur : UPGRADABLE) addThreshold(out, type, cur, nk, slot, Coverage.COVERED, Coverage.COVERED);
                        addThreshold(out, type, Coverage.UNCOVERED, nk, slot, Coverage.PARTIAL, Coverage.PARTIAL);
                    } else {
                        addThreshold(out, type, Coverage.UNCOVERED, nk, slot, Coverage.PARTIAL, Coverage.PARTIAL);
                        addThreshold(out, type, Coverage.UNCOVERED, nk, slot, Coverage.COVERED, Coverage.PARTIAL);
                    }
                }
                if (type == DecompositionType.AND) {
                    for (Coverage cur : UPGRADABLE) {
                        addGate(out, type, cur, nk, List.of(new Slot(kindControl, Coverage.COVERED)));
                        addGate(out, type, cur, nk, List.of(new Slot(kindControl, Coverage.COVERED), new Slot(kindControl, Coverage.COVERED)));
                        addGate(out, type, cur, nk, List.of(new Slot(kindControl, Coverage.COVERED), new Slot(gateControl, Coverage.COVERED)));
                    }
                }
            }
        }
    }

    /** Upgrades a gate node in {@code cur} to {@code target} because one slot ({@code slot}) is in {@code slotState}. */
    private static void addThreshold(List<RuleSpecification> out, DecompositionType type, Coverage cur, NodeKind nk,
                                     String slot, Coverage slotState, Coverage target) {
        Term slotPattern = Term.node(slot, state(slotState), Term.SITE);
        List<Term> kept = new ArrayList<>();
        if (cur == Coverage.PARTIAL) kept.add(Term.node(CoverageMarker.PART_DECOMP.controlName()));
        List<Term> redexKids = new ArrayList<>(List.of(state(cur)));
        redexKids.addAll(kept);
        redexKids.add(slotPattern);
        redexKids.add(Term.SITE);
        List<Term> reactumKids = new ArrayList<>(List.of(state(target)));
        reactumKids.addAll(kept);
        reactumKids.add(why(target, false));
        reactumKids.add(slotPattern);
        reactumKids.add(Term.SITE);
        Term redexNode = Term.node(gate(type, nk), redexKids.toArray(Term[]::new));
        Term reactumNode = Term.node(gate(type, nk), reactumKids.toArray(Term[]::new));
        String name = "gate-" + type + "-" + nk + "-" + cur + "-" + target + "-if-" + slot + "_" + slotState;
        out.add(new RuleSpecification(name, List.of(redexNode), List.of(reactumNode), pair(redexNode, reactumNode),
                Effect.place(redexNode)));
    }

    private static void addGate(List<RuleSpecification> out, DecompositionType type, Coverage cur, NodeKind nk, List<Slot> slots) {
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
        String name = "gate-" + type + "-" + nk + "-" + cur + "-"
                + slots.stream().map(s -> s.control() + "_" + s.state()).collect(Collectors.joining("+"));
        out.add(new RuleSpecification(name, List.of(redexNode), List.of(reactumNode), pair(redexNode, reactumNode),
                Effect.place(redexNode)));
    }

    /**
     * Pairs each non-site term with the bigraph node built from it. Relies on {@link #side} adding the children of a
     * node in term order and on {@code getChildrenOf} returning them in that order (true for the framework's builder).
     */
    private static void collect(PureBigraph bigraph, BigraphEntity<?> parent, List<Term> terms, IdentityHashMap<Term, String> out) {
        List<BigraphEntity.NodeEntity<?>> nodes = bigraph.getChildrenOf(parent).stream()
                .filter(BigraphEntity.NodeEntity.class::isInstance)
                .map(e -> (BigraphEntity.NodeEntity<?>) e).collect(Collectors.toUnmodifiableList());
        List<Term> nodeTerms = terms.stream().filter(term -> !term.isSite()).toList();
        for (int i = 0; i < nodeTerms.size(); i++) {
            out.put(nodeTerms.get(i), nodes.get(i).getName());
            collect(bigraph, nodes.get(i), nodeTerms.get(i).kids(), out);
        }
    }

    /** Term -> generated node name in {@code bigraph}, which was built from {@code roots} by {@link #side}. */
    private static IdentityHashMap<Term, String> names(PureBigraph bigraph, List<Term> roots) {
        var out = new IdentityHashMap<Term, String>();
        var sortedRoots = new ArrayList<>(bigraph.getRoots());
        sortedRoots.sort(Comparator.comparing(BigraphEntity.RootEntity::getIndex));
        for (int i = 0; i < roots.size(); i++) collect(bigraph, sortedRoots.get(i), List.of(roots.get(i)), out);
        return out;
    }

    /** Reactum node name -> redex node name it keeps (empty for new nodes, i.e. the added markers). */
    static TrackingMap trackingMap(RuleSpecification spec, PureBigraph redex, PureBigraph reactum) {
        var redexNames = names(redex, spec.redex());
        var reactumNames = names(reactum, spec.reactum());

        var partnerOf = new IdentityHashMap<Term, Term>();
        spec.pairs().forEach((redexTerm, reactumTerm) -> partnerOf.put(reactumTerm, redexTerm));

        var tracking = new TrackingMap();
        tracking.setRuleName(spec.name());
        reactumNames.forEach((reactumTerm, reactumName) -> {
            Term redexTerm = redexNames.containsKey(reactumTerm) ? reactumTerm : partnerOf.get(reactumTerm);
            tracking.put(reactumName, redexTerm != null && redexNames.containsKey(redexTerm) ? redexNames.get(redexTerm) : "");
        });
        return tracking;
    }

    private static void liftRules(List<RuleSpecification> out) {
        for (DecompositionType type : List.of(DecompositionType.AND, DecompositionType.OR)) {
            for (NodeKind nk : NodeKind.values()) {
                String kindControl = kind(nk);
                for (Coverage s : PARTIAL_OR_COVERED) {
                    for (Coverage c : UPGRADABLE) {
                        if (s.compareTo(c) <= 0) continue;
                        Term topGate = Term.node(gate(type, nk), state(s), Term.SITE); // the gate node directly under a domain node
                        Term redexNode = Term.node(kindControl, state(c), Term.SITE, topGate);
                        Term reactumNode = Term.node(kindControl, state(s), why(s, false), Term.SITE, topGate);
                        out.add(new RuleSpecification("lift-" + type + "-" + nk + "-" + c + "-" + s,
                                List.of(redexNode), List.of(reactumNode), pair(redexNode, reactumNode),
                                Effect.place(redexNode)));
                    }
                }
            }
        }
    }

    private static void linkRules(List<RuleSpecification> out) {
        for (RelationType t : RelationType.values()) {
            for (RelationType.NodeTypePair kinds : t.getSupportedNodeTypePair()) {
                RelationType.CoveragePropagation dir = t.getCoveragePropagation(kinds);
                if (dir.hasNoCoveragePropagation()) continue;
                boolean fwd = dir == RelationType.CoveragePropagation.FORWARD;
                String giverStub = POPPBigraphSignature.stub(t, fwd).controlName();
                String recvStub = POPPBigraphSignature.stub(t, !fwd).controlName();
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
                                new Effect(redexRecv, giver, t)));
                    }
                }
            }
        }
    }

    /** The coverage rules, each with where to find receiver and giver in a match. Used by both rewriting analyzers. */
    public static List<CompiledRule> compile() {
        DynamicSignature signature = CoverageSignature.get();
        List<CompiledRule> out = new ArrayList<>();
        for (RuleSpecification spec : specs()) {
            Effect effect = Objects.requireNonNull(spec.effect(), "Spec without effect: " + spec.name());
            try {
                PureBigraph redex = side(signature, spec.redex()), reactum = side(signature, spec.reactum());
                ParametricReactionRule<PureBigraph> rule = new ParametricReactionRule<>(redex, reactum);
                // buildParametricReaction names the reactum nodes after the agent nodes the tracked redex nodes matched,
                // so domain nodes keep their ids (the incremental analyzer and the readout rely on it).
                rule.withTrackingMap(trackingMap(spec, redex, reactum));
                var redexNames = names(redex, spec.redex());
                out.add(new CompiledRule(spec.name(), rule,
                        redexNames.get(effect.receiver()),
                        effect.giver() == null ? null : redexNames.get(effect.giver()), effect.relation()));
            } catch (InvalidReactionRuleException e) {
                throw new IllegalStateException("Invalid coverage rule " + spec.name(), e);
            }
            if (logger.isDebugEnabled()) logger.debug(toBigrapher(spec)); // debugging only
        }
        return List.copyOf(out);
    }

    static PureBigraph side(DynamicSignature signature, List<Term> roots) {
        var builder = pureBuilder(signature);
        for (Term term : roots) emit(builder.root(), term);
        return builder.create();
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

    /**
     * The rule in BigraphER syntax. Meant for debugging only: {@link #compile()} writes every rule at debug level, so
     * the generated rules can be read and checked with BigraphER.
     */
    static String toBigrapher(RuleSpecification spec) {
        return "react " + ident(spec.name()) + " =\n  " + side(spec.redex()) + "\n  --> " + side(spec.reactum()) + ";\n";
    }
    private static String ident(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("_$", "");
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