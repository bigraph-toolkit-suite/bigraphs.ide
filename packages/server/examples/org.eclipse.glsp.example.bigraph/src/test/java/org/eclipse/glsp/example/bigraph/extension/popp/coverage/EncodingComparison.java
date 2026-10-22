package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageRewriteRules.Effect;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageRewriteRules.RuleSpecification;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageRewriteRules.Term;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;

import java.util.*;

/**
 * How the density of the encoding affects the rule worklist: per encoding, the number of rules and how many rules a
 * rule enables on average, by the control-pair analysis and by the overlap analysis
 * ({@link CoverageRewriteRules#enablingByOverlap(List)}). Static, on the rules as terms; the two earlier encodings are
 * rebuilt here from their rule generators (they no longer have a signature or bigraph builder in the code):
 * <ol>
 *   <li><b>single control</b>: every tree and gate node is a {@code TreeNode}; a gate node carries its type as a
 *   marker ({@code AndDecomp}/{@code OrDecomp}) and the first one a {@code TopGate} marker; no node kinds;</li>
 *   <li><b>generic gates</b>: domain nodes per kind, gate nodes {@code AndDecomp}/{@code OrDecomp} for every kind;</li>
 *   <li><b>per-kind gates</b>: gate nodes per kind and type ({@code GoalAnd}, ...), one rule per combination of slot
 *   states (the encoding of the benchmark run);</li>
 *   <li><b>per-kind gates, threshold rules</b>: as 3, with the gate rules as thresholds.</li>
 * </ol>
 * {@code EncodingComparison [out.csv]}
 */
public final class EncodingComparison {
    private static final List<Coverage> ALL = List.of(Coverage.UNCOVERED, Coverage.PARTIAL, Coverage.COVERED);
    private static final List<Coverage> UPGRADABLE = List.of(Coverage.UNCOVERED, Coverage.PARTIAL);
    private static final List<Coverage> PARTIAL_OR_COVERED = List.of(Coverage.PARTIAL, Coverage.COVERED);
    private static final String TREE = "TreeNode", ROOT = "TopGate";

    public static void main(String[] args) throws java.io.IOException {
        java.io.PrintWriter csv = args.length > 0
                ? new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(java.nio.file.Path.of(args[0]))) : null;
        if (csv != null) csv.println("encoding,rules,gate,lift,link,enables_controls,enables_overlap");
        Map<String, List<RuleSpecification>> encodings = new LinkedHashMap<>();
        encodings.put("1 single control (TreeNode + markers)", singleControl());
        encodings.put("2 per-kind domain, generic gate controls", genericGates(CoverageRewriteRules.specs(false)));
        encodings.put("3 per-kind gate controls, combination rules", CoverageRewriteRules.specs(false));
        encodings.put("4 per-kind gate controls, threshold rules", CoverageRewriteRules.specs(true));
        System.out.printf("%-44s %6s %6s %6s %6s %6s %6s %6s%n", "encoding", "rules", "gate", "lift", "link",
                "en/ctl", "en/ovl", "ovl %");
        for (var e : encodings.entrySet()) {
            List<RuleSpecification> rules = e.getValue();
            long gate = rules.stream().filter(r -> r.name().startsWith("gate")).count();
            long lift = rules.stream().filter(r -> r.name().startsWith("lift")).count();
            long link = rules.stream().filter(r -> r.name().startsWith("link")).count();
            double ctl = average(byControls(rules)), ovl = average(CoverageRewriteRules.enablingByOverlap(rules));
            System.out.printf("%-44s %6d %6d %6d %6d %6.1f %6.1f %5.1f%%%n", e.getKey(), rules.size(), gate, lift, link,
                    ctl, ovl, 100 * ovl / rules.size());
            if (csv != null) csv.printf(java.util.Locale.ROOT, "\"%s\",%d,%d,%d,%d,%.2f,%.2f%n", e.getKey(), rules.size(),
                    gate, lift, link, ctl, ovl);
        }
        if (csv != null) csv.close();
        System.out.println("en/ctl, en/ovl: rules a rule enables on average (control pairs, overlap); ovl %: as a share of all rules");
    }

    private static double average(boolean[][] m) {
        long sum = 0;
        for (boolean[] row : m) for (boolean b : row) if (b) sum++;
        return (double) sum / m.length;
    }

    // ---- the control-pair analysis of ScheduledRewritingCoverageAnalyzer, on terms ----

    private static boolean[][] byControls(List<RuleSpecification> rules) {
        int n = rules.size();
        List<Map<String, Integer>> needs = new ArrayList<>();
        List<Set<String>> exact = new ArrayList<>(), added = new ArrayList<>(), removed = new ArrayList<>();
        for (RuleSpecification r : rules) {
            Map<String, Integer> before = facts(r.redex()), after = facts(r.reactum());
            needs.add(before);
            Set<String> ex = new HashSet<>();
            for (Term t : r.redex()) exactNodes(t, ex);
            exact.add(ex);
            Set<String> plus = new HashSet<>(), minus = new HashSet<>(), keys = new HashSet<>(before.keySet());
            keys.addAll(after.keySet());
            for (String k : keys) {
                int d = after.getOrDefault(k, 0) - before.getOrDefault(k, 0);
                if (d > 0) plus.add(k);
                else if (d < 0) minus.add(k);
            }
            added.add(plus);
            removed.add(minus);
        }
        boolean[][] out = new boolean[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                final int jj = j;
                out[i][j] = added.get(i).stream().anyMatch(f -> needs.get(jj).containsKey(f))
                        || removed.get(i).stream().anyMatch(f -> exact.get(jj).contains(parentOf(f)));
            }
        return out;
    }

    private static Map<String, Integer> facts(List<Term> roots) {
        Map<String, Integer> out = new HashMap<>();
        Map<String, List<String>> links = new HashMap<>();
        for (Term r : roots) facts(r, null, out, links);
        for (List<String> pts : links.values()) {
            Collections.sort(pts);
            out.merge("link:" + String.join(",", pts), 1, Integer::sum);
        }
        return out;
    }

    private static void facts(Term t, Term parent, Map<String, Integer> out, Map<String, List<String>> links) {
        if (t.isSite()) return;
        out.merge(parent == null ? t.control() : parent.control() + ">" + t.control(), 1, Integer::sum);
        if (t.link() != null) links.computeIfAbsent(t.link(), k -> new ArrayList<>())
                .add((parent == null ? "" : parent.control()) + ">" + t.control());
        for (Term k : t.kids()) facts(k, t, out, links);
    }

    private static void exactNodes(Term t, Set<String> out) {
        if (t.isSite()) return;
        if (!t.kids().isEmpty() && t.kids().stream().noneMatch(Term::isSite)) out.add(t.control());
        for (Term k : t.kids()) exactNodes(k, out);
    }

    private static String parentOf(String fact) {
        int i = fact.indexOf('>');
        return fact.startsWith("link:") || i < 0 ? fact : fact.substring(0, i);
    }

    // ---- encoding 2: the current rules with one AndDecomp/OrDecomp control for all kinds ----

    private static List<RuleSpecification> genericGates(List<RuleSpecification> rules) {
        Map<String, String> rename = new HashMap<>();
        for (NodeKind k : NodeKind.values()) {
            rename.put(POPPBigraphSignature.getGateControl(k, DecompositionType.AND).controlName(), "AndDecomp");
            rename.put(POPPBigraphSignature.getGateControl(k, DecompositionType.OR).controlName(), "OrDecomp");
        }
        Map<String, RuleSpecification> distinct = new LinkedHashMap<>();   // rules that only differed in the gate's kind
        for (RuleSpecification r : rules) {
            IdentityHashMap<Term, Term> map = new IdentityHashMap<>();
            List<Term> redex = r.redex().stream().map(t -> rename(t, rename, map)).toList();
            List<Term> reactum = r.reactum().stream().map(t -> rename(t, rename, map)).toList();
            Map<Term, Term> pairs = new IdentityHashMap<>();
            r.pairs().forEach((a, b) -> pairs.put(map.get(a), map.get(b)));
            Effect fx = r.effect();
            RuleSpecification g = new RuleSpecification(r.name(), redex, reactum, pairs,
                    new Effect(map.get(fx.receiver()), fx.giver() == null ? null : map.get(fx.giver()), fx.target(),
                            fx.reason(), fx.relation(), fx.giverState(), fx.receiverState()));
            distinct.putIfAbsent(CoverageRewriteRules.toBigrapher(g).replaceFirst("react [^=]*=", ""), g);
        }
        return new ArrayList<>(distinct.values());
    }

    private static Term rename(Term t, Map<String, String> rename, IdentityHashMap<Term, Term> map) {
        if (t.isSite()) return t;
        Term c = new Term(rename.getOrDefault(t.control(), t.control()), t.link(),
                t.kids().stream().map(k -> rename(k, rename, map)).toList());
        map.put(t, c);
        return c;
    }

    // ---- encoding 1: everything a TreeNode (the first rewriting encoding) ----

    private static Term state(Coverage c) { return Term.node(CoverageMarker.of(c).controlName()); }

    private static String marker(DecompositionType t) { return t == DecompositionType.AND ? "AndDecomp" : "OrDecomp"; }

    private static Term why(Coverage target, boolean link) {
        boolean cov = target == Coverage.COVERED;
        CoverageMarker m = link ? (cov ? CoverageMarker.COV_LINK : CoverageMarker.PART_LINK)
                : (cov ? CoverageMarker.COV_DECOMP : CoverageMarker.PART_DECOMP);
        return link ? Term.node(m.controlName(), "x") : Term.node(m.controlName());
    }

    private static Effect effect(Term receiver, Term giver, Coverage target) {
        return new Effect(receiver, giver, target, null);
    }

    private static List<RuleSpecification> singleControl() {
        List<RuleSpecification> out = new ArrayList<>();
        for (boolean root : new boolean[]{true, false})
            for (DecompositionType type : List.of(DecompositionType.AND, DecompositionType.OR))
                for (Coverage cur : UPGRADABLE)
                    for (int i = 0; i < ALL.size(); i++) {
                        if (root) addGate(out, type, cur, true, List.of(ALL.get(i)));
                        for (int j = i; j < ALL.size(); j++) addGate(out, type, cur, root, List.of(ALL.get(i), ALL.get(j)));
                    }
        for (DecompositionType type : List.of(DecompositionType.AND, DecompositionType.OR))
            for (Coverage s : PARTIAL_OR_COVERED)
                for (Coverage c : UPGRADABLE) {
                    if (s.compareTo(c) <= 0) continue;
                    Term topGate = Term.node(TREE, state(s), Term.node(ROOT), Term.node(marker(type)), Term.SITE);
                    Term redex = Term.node(TREE, state(c), Term.SITE, topGate);
                    Term reactum = Term.node(TREE, state(s), why(s, false), Term.SITE, topGate);
                    out.add(spec("lift-" + type + "-" + c + "-" + s, List.of(redex), List.of(reactum), redex, reactum, null, s));
                }
        for (RelationType t : RelationType.values()) {
            RelationType.CoveragePropagation dir = t.getCoveragePropagation();
            if (dir.hasNoCoveragePropagation()) continue;
            boolean fwd = dir == RelationType.CoveragePropagation.FORWARD;
            String giverStub = (fwd ? POPPBigraphSignature.outgoingStubFor(t) : POPPBigraphSignature.incomingStubFor(t)).controlName();
            String recvStub = (fwd ? POPPBigraphSignature.incomingStubFor(t) : POPPBigraphSignature.outgoingStubFor(t)).controlName();
            for (Coverage g : PARTIAL_OR_COVERED)
                for (Coverage c : UPGRADABLE) {
                    Coverage target = Coverage.best(c, g);
                    if (target.compareTo(c) <= 0) continue;
                    Term giver = Term.node(TREE, state(g), Term.node(giverStub, "x"), Term.SITE);
                    Term recv = Term.node(recvStub, "x");
                    Term redex = Term.node(TREE, state(c), recv, Term.SITE);
                    Term reactum = Term.node(TREE, state(target), why(target, true), recv, Term.SITE);
                    out.add(spec("link-" + t + "-" + g + "-" + c, List.of(giver, redex), List.of(giver, reactum), redex,
                            reactum, giver, target));
                }
        }
        return out;
    }

    private static void addGate(List<RuleSpecification> out, DecompositionType type, Coverage cur, boolean root, List<Coverage> slots) {
        Coverage target = AbstractCoverageSession.aggregate(type, slots);
        if (target.compareTo(cur) <= 0) return;
        List<Term> kept = new ArrayList<>();
        if (root) kept.add(Term.node(ROOT));
        if (cur == Coverage.PARTIAL) kept.add(Term.node(CoverageMarker.PART_DECOMP.controlName()));
        List<Term> before = new ArrayList<>(List.of(state(cur)));
        before.addAll(kept);
        before.add(Term.node(marker(type)));
        List<Term> after = new ArrayList<>(List.of(state(target)));
        after.addAll(kept);
        after.add(why(target, false));
        after.add(Term.node(marker(type)));
        for (Coverage s : slots) {
            Term slot = Term.node(TREE, state(s), Term.SITE);
            before.add(slot);
            after.add(slot);
        }
        Term redex = Term.node(TREE, before.toArray(Term[]::new)), reactum = Term.node(TREE, after.toArray(Term[]::new));
        out.add(spec("gate-" + (root ? "root-" : "") + type + "-" + cur + "-" + slots, List.of(redex), List.of(reactum),
                redex, reactum, null, target));
    }

    private static RuleSpecification spec(String name, List<Term> redex, List<Term> reactum, Term receiver,
                                          Term receiverAfter, Term giver, Coverage target) {
        Map<Term, Term> pairs = new IdentityHashMap<>();
        pairs.put(receiver, receiverAfter);
        return new RuleSpecification(name, redex, reactum, pairs, effect(receiver, giver, target));
    }
}
