package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.bigraphs.framework.core.impl.signature.DynamicSignatureBuilder;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageRewriteRules.Effect;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageRewriteRules.RuleSpecification;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageRewriteRules.Term;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.OverlapProduceUseAnalyzer;
import org.eclipse.glsp.example.bigraph.extension.popp.rewriting.Rule;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureSignatureBuilder;

/**
 * How the encoding of the coverage bigraph affects the worklist (Figure 4.6 of the thesis): per encoding, the number of
 * rules and how many rules a rule enables on average, by the control-pair analysis and by the generic overlap analysis
 * ({@link OverlapProduceUseAnalyzer}). Static, from the rules alone. The four encodings:
 * <ol>
 *   <li><b>single control</b>: every tree and gate node is a {@code TreeNode}, a gate node carries its type as a marker
 *   ({@code AndDecomp}/{@code OrDecomp}) and the topmost one a {@code TopGate} marker, no node kinds;</li>
 *   <li><b>generic gates</b>: domain nodes per kind, gate nodes {@code AndDecomp}/{@code OrDecomp} for every kind;</li>
 *   <li><b>per-kind gates, combination rules</b>: gate nodes per kind and type ({@code GoalAnd}, ...), one gate rule
 *   per combination of slot states;</li>
 *   <li><b>per-kind gates, threshold rules</b>: as 3, with threshold gate rules (the editor's rule set).</li>
 * </ol>
 * The first two encodings no longer exist in the editor, so they are rebuilt here as terms and compiled over a signature
 * made of their own controls, only for the analyses.
 * <p>
 * {@code EncodingComparison [out.csv]}
 */
public final class EncodingComparison {
    private static final List<Coverage> ALL = List.of(Coverage.UNCOVERED, Coverage.PARTIAL, Coverage.COVERED);
    private static final List<Coverage> UPGRADABLE = List.of(Coverage.UNCOVERED, Coverage.PARTIAL);
    private static final List<Coverage> PARTIAL_OR_COVERED = List.of(Coverage.PARTIAL, Coverage.COVERED);
    private static final String TREE = "TreeNode", TOP_GATE = "TopGate";

    public static void main(String[] args) throws IOException {
        Map<String, List<RuleSpecification>> encodings = new LinkedHashMap<>();
        encodings.put("single control", singleControl());
        encodings.put("generic gates", genericGates(CoverageRewriteRules.specs(false)));
        encodings.put("per-kind gates, combination rules", CoverageRewriteRules.specs(false));
        encodings.put("per-kind gates, threshold rules", CoverageRewriteRules.specs(true));
        PrintWriter csv = args.length > 0 ? new PrintWriter(Files.newBufferedWriter(Path.of(args[0]))) : null;
        if (csv != null) csv.println("encoding,rules,gate,lift,link,enables_controls,enables_overlap");
        System.out.printf("%-36s %6s %6s %6s %6s %8s %8s %7s%n", "encoding", "rules", "gate", "lift", "link", "en/ctl",
                "en/ovl", "ovl %");
        for (var encoding : encodings.entrySet()) {
            List<RuleSpecification> rules = encoding.getValue();
            long gate = rules.stream().filter(rule -> rule.name().startsWith("gate")).count();
            long lift = rules.stream().filter(rule -> rule.name().startsWith("lift")).count();
            long link = rules.stream().filter(rule -> rule.name().startsWith("link")).count();
            double controls = average(controlPairs(rules));
            double overlap = average(new OverlapProduceUseAnalyzer().analyzeRuleSet(compileOwnSignature(rules)));
            System.out.printf(Locale.ROOT, "%-36s %6d %6d %6d %6d %8.2f %8.2f %6.1f%%%n", encoding.getKey(), rules.size(),
                    gate, lift, link, controls, overlap, 100 * overlap / rules.size());
            if (csv != null) {
                csv.printf(Locale.ROOT, "\"%s\",%d,%d,%d,%d,%.3f,%.3f%n", encoding.getKey(), rules.size(), gate, lift, link,
                        controls, overlap);
            }
        }
        if (csv != null) csv.close();
        System.out.println("en/ctl, en/ovl: rules a rule enables on average (control pairs, overlap), ovl %: share of all rules");
    }

    private static double average(boolean[][] enables) {
        long sum = 0;
        for (boolean[] row : enables) for (boolean enabled : row) if (enabled) sum++;
        return (double) sum / enables.length;
    }

    /** The rules compiled over a signature of their own controls (arity 1 for the controls that carry a link). */
    static List<Rule> compileOwnSignature(List<RuleSpecification> specs) {
        Map<String, Integer> arities = new TreeMap<>();
        for (RuleSpecification spec : specs) {
            for (Term root : spec.redex()) collectControls(root, arities);
            for (Term root : spec.reactum()) collectControls(root, arities);
        }
        DynamicSignatureBuilder builder = pureSignatureBuilder();
        arities.forEach(builder::add);
        DynamicSignature signature = builder.create();
        List<Rule> out = new ArrayList<>();
        for (RuleSpecification spec : specs) {
            try {
                PureBigraph redex = CoverageRewriteRules.side(signature, spec.redex());
                PureBigraph reactum = CoverageRewriteRules.side(signature, spec.reactum());
                ParametricReactionRule<PureBigraph> rule = new ParametricReactionRule<>(redex, reactum);
                rule.withTrackingMap(CoverageRewriteRules.trackingMap(spec, redex, reactum));
                out.add(Rule.of(spec.name(), rule));
            } catch (Exception e) {
                throw new IllegalStateException("Invalid rule " + spec.name(), e);
            }
        }
        return out;
    }

    private static void collectControls(Term term, Map<String, Integer> arities) {
        if (term.isSite()) return;
        arities.merge(term.control(), term.link() == null ? 0 : 1, Math::max);
        for (Term kid : term.kids()) collectControls(kid, arities);
    }

    // The control-pair analysis of the first version of the optimizations, on terms

    /**
     * The coarse produce-use analysis by control pairs: {@code r} enables {@code s} if {@code r} adds a parent-child
     * pair of controls (or a link between two such pairs) that {@code s}'s redex contains, or removes a child below a
     * control that some node without a site in {@code s}'s redex has. Indices follow {@code rules}.
     */
    static boolean[][] controlPairs(List<RuleSpecification> rules) {
        int count = rules.size();
        List<Map<String, Integer>> needs = new ArrayList<>();
        List<Set<String>> exact = new ArrayList<>(), added = new ArrayList<>(), removed = new ArrayList<>();
        for (RuleSpecification rule : rules) {
            Map<String, Integer> before = facts(rule.redex()), after = facts(rule.reactum());
            needs.add(before);
            Set<String> exactControls = new HashSet<>();
            for (Term root : rule.redex()) exactNodes(root, exactControls);
            exact.add(exactControls);
            Set<String> plus = new HashSet<>(), minus = new HashSet<>(), keys = new HashSet<>(before.keySet());
            keys.addAll(after.keySet());
            for (String fact : keys) {
                int difference = after.getOrDefault(fact, 0) - before.getOrDefault(fact, 0);
                if (difference > 0) plus.add(fact);
                else if (difference < 0) minus.add(fact);
            }
            added.add(plus);
            removed.add(minus);
        }
        boolean[][] out = new boolean[count][count];
        for (int enabling = 0; enabling < count; enabling++) {
            for (int enabled = 0; enabled < count; enabled++) {
                Map<String, Integer> needed = needs.get(enabled);
                Set<String> exactControls = exact.get(enabled);
                out[enabling][enabled] = added.get(enabling).stream().anyMatch(needed::containsKey)
                        || removed.get(enabling).stream().anyMatch(fact -> exactControls.contains(parentOf(fact)));
            }
        }
        return out;
    }

    private static Map<String, Integer> facts(List<Term> roots) {
        Map<String, Integer> out = new HashMap<>();
        Map<String, List<String>> links = new HashMap<>();
        for (Term root : roots) facts(root, null, out, links);
        for (List<String> points : links.values()) {
            Collections.sort(points);
            out.merge("link:" + String.join(",", points), 1, Integer::sum);
        }
        return out;
    }

    private static void facts(Term term, Term parent, Map<String, Integer> out, Map<String, List<String>> links) {
        if (term.isSite()) return;
        out.merge(parent == null ? term.control() : parent.control() + ">" + term.control(), 1, Integer::sum);
        if (term.link() != null) {
            links.computeIfAbsent(term.link(), key -> new ArrayList<>())
                    .add((parent == null ? "" : parent.control()) + ">" + term.control());
        }
        for (Term kid : term.kids()) facts(kid, term, out, links);
    }

    private static void exactNodes(Term term, Set<String> out) {
        if (term.isSite()) return;
        if (!term.kids().isEmpty() && term.kids().stream().noneMatch(Term::isSite)) out.add(term.control());
        for (Term kid : term.kids()) exactNodes(kid, out);
    }

    private static String parentOf(String fact) {
        int separator = fact.indexOf('>');
        return fact.startsWith("link:") || separator < 0 ? fact : fact.substring(0, separator);
    }

    // Encoding 2: the rules with one AndDecomp/OrDecomp control for all kinds

    private static List<RuleSpecification> genericGates(List<RuleSpecification> rules) {
        Map<String, String> rename = new HashMap<>();
        for (NodeKind kind : NodeKind.values()) {
            rename.put(POPPBigraphSignature.getGateControl(kind, DecompositionType.AND).controlName(), "AndDecomp");
            rename.put(POPPBigraphSignature.getGateControl(kind, DecompositionType.OR).controlName(), "OrDecomp");
        }
        Map<String, RuleSpecification> distinct = new LinkedHashMap<>(); // rules that only differed in the gate's kind
        for (RuleSpecification rule : rules) {
            IdentityHashMap<Term, Term> renamed = new IdentityHashMap<>();
            List<Term> redex = rule.redex().stream().map(term -> rename(term, rename, renamed)).toList();
            List<Term> reactum = rule.reactum().stream().map(term -> rename(term, rename, renamed)).toList();
            Map<Term, Term> pairs = new IdentityHashMap<>();
            rule.pairs().forEach((before, after) -> pairs.put(renamed.get(before), renamed.get(after)));
            Effect effect = rule.effect();
            RuleSpecification generic = new RuleSpecification(rule.name(), redex, reactum, pairs,
                    new Effect(renamed.get(effect.receiver()), effect.giver() == null ? null : renamed.get(effect.giver()),
                            effect.target(), effect.reason(), effect.relation(), effect.giverState(), effect.receiverState()));
            distinct.putIfAbsent(CoverageRewriteRules.toBigrapher(generic).replaceFirst("react [^=]*=", ""), generic);
        }
        return new ArrayList<>(distinct.values());
    }

    /** A term with renamed controls. A term both rule sides share (a kept node) stays shared in the copy. */
    private static Term rename(Term term, Map<String, String> rename, IdentityHashMap<Term, Term> renamed) {
        if (term.isSite()) return term;
        if (renamed.containsKey(term)) return renamed.get(term);
        Term copy = new Term(rename.getOrDefault(term.control(), term.control()), term.link(),
                term.kids().stream().map(kid -> rename(kid, rename, renamed)).toList());
        renamed.put(term, copy);
        return copy;
    }

    // Encoding 1: everything a TreeNode (the first rewriting encoding)

    private static Term state(Coverage coverage) { return Term.node(CoverageMarker.of(coverage).controlName()); }

    private static String marker(DecompositionType type) { return type == DecompositionType.AND ? "AndDecomp" : "OrDecomp"; }

    private static Term why(Coverage target, boolean link) {
        boolean covered = target == Coverage.COVERED;
        CoverageMarker reason = link ? (covered ? CoverageMarker.COV_LINK : CoverageMarker.PART_LINK)
                : (covered ? CoverageMarker.COV_DECOMP : CoverageMarker.PART_DECOMP);
        return link ? Term.node(reason.controlName(), "x") : Term.node(reason.controlName());
    }

    private static List<RuleSpecification> singleControl() {
        List<RuleSpecification> out = new ArrayList<>();
        for (boolean topmost : new boolean[]{true, false}) {
            for (DecompositionType type : List.of(DecompositionType.AND, DecompositionType.OR)) {
                for (Coverage current : UPGRADABLE) {
                    for (int first = 0; first < ALL.size(); first++) {
                        if (topmost) addGate(out, type, current, true, List.of(ALL.get(first)));
                        for (int second = first; second < ALL.size(); second++) {
                            addGate(out, type, current, topmost, List.of(ALL.get(first), ALL.get(second)));
                        }
                    }
                }
            }
        }
        for (DecompositionType type : List.of(DecompositionType.AND, DecompositionType.OR)) {
            for (Coverage gateState : PARTIAL_OR_COVERED) {
                for (Coverage current : UPGRADABLE) {
                    if (gateState.compareTo(current) <= 0) continue;
                    Term topGate = Term.node(TREE, state(gateState), Term.node(TOP_GATE), Term.node(marker(type)), Term.SITE);
                    Term redex = Term.node(TREE, state(current), Term.SITE, topGate);
                    Term reactum = Term.node(TREE, state(gateState), why(gateState, false), Term.SITE, topGate);
                    out.add(spec("lift-" + type + "-" + current + "-" + gateState, List.of(redex), List.of(reactum), redex,
                            reactum, null, gateState));
                }
            }
        }
        // without node kinds, a link rule only knows the relation type and the direction, one per propagating pair
        for (RelationType type : RelationType.values()) {
            Set<RelationType.CoveragePropagation> directions = new LinkedHashSet<>();
            for (RelationType.NodeTypePair pair : type.getSupportedNodeTypePair()) {
                RelationType.CoveragePropagation direction = type.getCoveragePropagation(pair);
                if (!direction.hasNoCoveragePropagation()) directions.add(direction);
            }
            for (RelationType.CoveragePropagation direction : directions) {
                boolean forward = direction == RelationType.CoveragePropagation.FORWARD;
                String giverStub = (forward ? POPPBigraphSignature.outgoingStubFor(type) : POPPBigraphSignature.incomingStubFor(type)).controlName();
                String receiverStub = (forward ? POPPBigraphSignature.incomingStubFor(type) : POPPBigraphSignature.outgoingStubFor(type)).controlName();
                for (Coverage giverState : PARTIAL_OR_COVERED) {
                    for (Coverage current : UPGRADABLE) {
                        Coverage target = Coverage.best(current, giverState);
                        if (target.compareTo(current) <= 0) continue;
                        Term giver = Term.node(TREE, state(giverState), Term.node(giverStub, "x"), Term.SITE);
                        Term receiverStubTerm = Term.node(receiverStub, "x");
                        Term redex = Term.node(TREE, state(current), receiverStubTerm, Term.SITE);
                        Term reactum = Term.node(TREE, state(target), why(target, true), receiverStubTerm, Term.SITE);
                        out.add(spec("link-" + type + "-" + direction + "-" + giverState + "-" + current,
                                List.of(giver, redex), List.of(giver, reactum), redex, reactum, giver, target));
                    }
                }
            }
        }
        return out;
    }

    private static void addGate(List<RuleSpecification> out, DecompositionType type, Coverage current, boolean topmost,
                                List<Coverage> slots) {
        Coverage target = AbstractCoverageSession.aggregate(type, slots);
        if (target.compareTo(current) <= 0) return;
        List<Term> kept = new ArrayList<>();
        if (topmost) kept.add(Term.node(TOP_GATE));
        if (current == Coverage.PARTIAL) kept.add(Term.node(CoverageMarker.PART_DECOMP.controlName()));
        List<Term> before = new ArrayList<>(List.of(state(current)));
        before.addAll(kept);
        before.add(Term.node(marker(type)));
        List<Term> after = new ArrayList<>(List.of(state(target)));
        after.addAll(kept);
        after.add(why(target, false));
        after.add(Term.node(marker(type)));
        for (Coverage slotState : slots) {
            Term slot = Term.node(TREE, state(slotState), Term.SITE);
            before.add(slot);
            after.add(slot);
        }
        Term redex = Term.node(TREE, before.toArray(Term[]::new)), reactum = Term.node(TREE, after.toArray(Term[]::new));
        out.add(spec("gate-" + (topmost ? "top-" : "") + type + "-" + current + "-" + slots, List.of(redex),
                List.of(reactum), redex, reactum, null, target));
    }

    private static RuleSpecification spec(String name, List<Term> redex, List<Term> reactum, Term receiver,
                                          Term receiverAfter, Term giver, Coverage target) {
        Map<Term, Term> pairs = new IdentityHashMap<>();
        pairs.put(receiver, receiverAfter);
        return new RuleSpecification(name, redex, reactum, pairs, new Effect(receiver, giver, target, null));
    }
}
