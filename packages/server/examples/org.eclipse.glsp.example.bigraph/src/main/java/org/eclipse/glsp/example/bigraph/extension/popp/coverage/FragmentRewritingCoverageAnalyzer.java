package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import it.uniud.mads.jlibbig.core.std.Match;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatch;
import org.bigraphs.framework.simulation.matching.pure.PureBigraphMatcher;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageMarker;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageRewriteRules;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.coverage.CoverageRewriteRules.CompiledRule;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.bigraphs.framework.core.factory.BigraphFactory.pureBuilder;

/**
 * The coverage rules of {@link CoverageRewriteRules}, unchanged, evaluated on local fragments of the coverage bigraph
 * instead of the whole agent.
 * <p>
 * <b>Why fragments are enough.</b> Every rule is local: a place rule (chain, lift) names one tree node (a domain node,
 * whose control is its kind, or a chain node {@code AndDecomp}/{@code OrDecomp}) as the receiver, its direct children
 * and their marker children; a link rule names two domain nodes whose stubs share
 * one relation edge. Everything else a match touches lies in a site. So, for a receiver {@code N}, a match in the full
 * coverage bigraph exists if and only if a match exists in the <i>node fragment</i> of {@code N}: {@code N} and each of
 * its tree-node children, every one with the markers {@link CoverageBigraph} gives it (state cell, {@code CovIntrinsic}
 * unless {@code slim}, {@code PartDecomp} on a PARTIAL chain node), but without stubs and grandchildren. For a relation
 * edge it is the <i>link fragment</i>: both endpoints with their state cell and the two stubs on that edge. The parts
 * that are left out are exactly the ones a redex can only cover with a site, which {@link #validate} checks for every
 * rule at construction; rules that do not have this shape are rejected rather than evaluated wrongly. Matches are only
 * used when the receiver is the fragment's own node (for a link fragment: one of its two endpoints), which makes the
 * correspondence a plain argument about one node and its neighbourhood.
 * <p>
 * One corner where fragments and the full agent could differ: a two-root link redex cannot match a relation between
 * a node and one of its own descendants in the full agent (the two roots would overlap), but it can in a link
 * fragment. This cannot occur in POPP: relations connect different node kinds and every decomposition tree holds a
 * single kind. Relations of a node to itself are skipped.
 * <p>
 * <b>Evaluation.</b> A worklist of fragments, seeded children before parents. Evaluating a fragment builds it from the
 * current states, matches the candidate rules and applies their effects (states only rise). A changed node re-queues
 * its own fragment, its parent's fragment and the link fragments of its relations. All rules are monotone state
 * upgrades, so any such chaotic iteration reaches the same least fixpoint as the round-based evaluation of
 * {@link RewritingCoverageAnalyzer}. Fragments are built from a per-session snapshot of the structure bigraph, not
 * by copying the whole bigraph.
 * <p>
 * <b>Matching.</b> Only through the Bigraph Framework: every fragment is a framework {@link PureBigraph} over the
 * rules' signature and is matched against the rule with {@link PureBigraphMatcher}. The node embedding of a match,
 * which says which fragment node is the receiver (and the giver), is read from the framework's matchAll
 * ({@code PureBigraphMatch#getJLibMatchResult}). {@code matchFirst} is not used: in bigraph-simulation 2.4.1 its
 * iterator never runs the matching engine, so it returns no match at all (fixed in 2.4.2).
 * <p>
 * <b>Cost.</b> With the default levers, every matcher call succeeds and there is one call per rule application, i.e.
 * per state upgrade; each call works on a fragment of at most 9 nodes (a link fragment has 6). Underneath, the framework translates
 * fragment and redex for jLibBig and solves a constraint model per call, which stays small because fragments are
 * bounded. A session should therefore scale linearly with the model size.
 * <p>
 * <b>Levers</b> ({@link Options}), all on by default except {@code memo}:
 * <ul>
 *   <li>{@code localPrune}: before calling the matcher, check the place-graph shape: every redex root's
 *   tree node must fit a distinct fragment root (its control, its marker children as a multiset, exactly when it has
 *   no site, and its tree-node children recursively, one level down). This is a necessary condition only; links
 *   and the final decision stay with the matcher. Without it only the node-count census per control is used;</li>
 *   <li>{@code incremental}: the worklist; without it every fragment is re-evaluated in rounds until nothing
 *   changes;</li>
 *   <li>{@code firstMatch}: stop enumerating a rule's matches after the first one whose receiver is a fragment root.
 *   With the current rules every match has its receiver at a fragment root, and within one fragment the receiver
 *   (and the giver) are fixed, so all matches of a rule have the same effect. A rule can still match twice: a chain
 *   rule whose two slots have the same pattern (e.g. both children covered) matches once per assignment of the two
 *   children to the slots. Since {@code matchAll} computes all matches before it returns, stopping early only skips
 *   reading the second one; the search itself would only be cut short with {@code matchFirst} (usable from 2.4.2), so
 *   this lever has no measurable effect yet;</li>
 *   <li>{@code slim}: leave {@code CovIntrinsic} out of node fragments. No redex names it and it only occurs on
 *   domain nodes, which every redex covers with a site (checked). With the per-kind encoding this is the only marker
 *   that can be left out, so the lever is small;</li>
 *   <li>{@code memo}: remember, per fragment shape (controls and nesting, no names), which rules matched. The matcher
 *   then only runs for shapes not seen before, so this turns the rule set into a lookup table after warm-up and is
 *   reported separately.</li>
 * </ul>
 * {@code memo} is off by default so that the default configuration measures matching, not a cache; its results
 * depend on the shape key capturing everything the redexes distinguish.
 */
public final class FragmentRewritingCoverageAnalyzer implements CoverageAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(FragmentRewritingCoverageAnalyzer.class);

    private static final List<CompiledRule> RULES = CoverageRewriteRules.compile();

    /** Controls of tree nodes: the node kinds and the two chain-node controls. */
    private static final Set<String> TREE;
    static {
        Set<String> t = new HashSet<>();
        for (POPPBigraphSignature c : POPPBigraphSignature.values()) {
            if (POPPBigraphSignature.isTreeControl(c)) t.add(c.controlName());
        }
        TREE = Set.copyOf(t);
    }
    /** The coverage signature the rules are built over; fragments must use the same one. */
    private static final DynamicSignature SIGNATURE = RULES.getFirst().rule().getRedex().getSignature();
    private static final Set<String> GATES = Set.of(
            POPPBigraphSignature.AND_DECOMP.controlName(), POPPBigraphSignature.OR_DECOMP.controlName());
    /** Left out of node fragments with {@code slim}. */
    private static final Set<String> SLIM_OMITTED = Set.of(CoverageMarker.COV_INTRINSIC.controlName());

    public record Options(boolean localPrune, boolean incremental, boolean slim, boolean firstMatch, boolean memo) {
        public static final Options DEFAULT = new Options(true, true, true, true, false);
    }

    /**
     * The place-graph shape a redex tree node needs: its control, the multiset of its marker (non-tree)
     * children, the needs of its tree-node children, and with {@code exact} (no site) exactly these children.
     * A necessary condition for a match, checked before the matcher is called; links are left to the matcher.
     */
    private record Need(String control, Map<String, Integer> markers, List<Need> trees, boolean exact) {
        static Need of(PureBigraph b, BigraphEntity.NodeEntity<?> n) {
            Map<String, Integer> markers = new HashMap<>();
            List<Need> trees = new ArrayList<>();
            boolean site = false;
            for (BigraphEntity<?> c : b.getChildrenOf(n)) {
                if (c instanceof BigraphEntity.SiteEntity) site = true;
                else if (c instanceof BigraphEntity.NodeEntity<?> k) {
                    if (TREE.contains(FragmentRewritingCoverageAnalyzer.control(k))) trees.add(of(b, k));
                    else markers.merge(FragmentRewritingCoverageAnalyzer.control(k), 1, Integer::sum);
                }
            }
            return new Need(FragmentRewritingCoverageAnalyzer.control(n), markers, List.copyOf(trees), !site);
        }

        boolean admits(FNode f) {
            if (!f.control.equals(control)) return false;
            Map<String, Integer> fm = new HashMap<>();
            List<FNode> ft = new ArrayList<>();
            for (FNode k : f.kids) {
                if (TREE.contains(k.control)) ft.add(k);
                else fm.merge(k.control, 1, Integer::sum);
            }
            if (exact ? !(fm.equals(markers) && ft.size() == trees.size())
                    : !(includes(fm, markers) && ft.size() >= trees.size())) return false;
            return assign(trees, 0, ft, new boolean[ft.size()]);
        }
    }

    /** Whether {@code needs[i..]} can be given distinct fragment nodes they admit (backtracking; at most 2-3 each). */
    private static boolean assign(List<Need> needs, int i, List<FNode> candidates, boolean[] used) {
        if (i == needs.size()) return true;
        for (int j = 0; j < candidates.size(); j++) {
            if (used[j] || !needs.get(i).admits(candidates.get(j))) continue;
            used[j] = true;
            if (assign(needs, i + 1, candidates, used)) return true;
            used[j] = false;
        }
        return false;
    }

    /** A rule with the node count per control its redex needs and the shapes of its roots. */
    private record Prepared(CompiledRule rule, Map<String, Integer> required, List<Need> roots) {
        PureBigraph redex() { return rule.rule().getRedex(); }
    }

    private final CoverageInput input;
    private final Options options;

    private final List<Prepared> placeRules;
    private final List<Prepared> linkRules;

    /** Fragment shape -> the rules that matched it (with {@code memo}); shared by all sessions of this analyzer. */
    private final Map<String, List<MemoHit>> memo = new ConcurrentHashMap<>();
    private record MemoHit(Prepared rule, int receiverRoot, int giverRoot) {}

    private volatile RewritingStats lastStats;

    public FragmentRewritingCoverageAnalyzer(CoverageInput input) {
        this(input, Options.DEFAULT);
    }

    public FragmentRewritingCoverageAnalyzer(CoverageInput input, Options options) {
        this.input = input;
        this.options = options;

        List<Prepared> place = new ArrayList<>(), link = new ArrayList<>();
        for (CompiledRule r : RULES) {
            PureBigraph redex = r.rule().getRedex();
            List<Need> needs = new ArrayList<>();
            for (BigraphEntity.RootEntity root : roots(redex)) {
                for (BigraphEntity<?> top : redex.getChildrenOf(root)) {
                    if (top instanceof BigraphEntity.NodeEntity<?> n) needs.add(Need.of(redex, n));
                }
            }
            Prepared p = new Prepared(r, census(redex), List.copyOf(needs));
            validate(p, options.slim());
            (r.isLinkRule() ? link : place).add(p);
        }
        this.placeRules = List.copyOf(place);
        this.linkRules = List.copyOf(link);
    }

    /** Counters of the most recent session (for profiling; not synchronised across concurrent sessions). */
    public RewritingStats lastStats() { return lastStats; }

    /**
     * Runs a few sessions on a small built-in model, so that class loading and JIT compilation of the matcher (the
     * framework's matcher, with jLibBig and its constraint solver underneath) do not land on the user's first
     * inspection. Meant to be called once per JVM, off the UI path ({@code POPPExtension} starts it in a background
     * thread when the first model is loaded); the first real session is then close to the steady state. Not called by
     * the analyzer itself, so the cold-start benchmark still measures the unprepared case.
     */
    public static void warmUp() {
        DynamicSignature sig = POPPBigraphSignature.create();
        POPPBigraph bigraph = new POPPBigraph(pureBuilder(sig).createMutable(), sig);
        POPPModel model = new POPPModel(); // only creates the nodes; the bigraph is filled by hand below
        TreeNode<?> root = model.createNode(NodeKind.SUCCESS_CRITERIA, "warm-up root", 0, 0);
        TreeNode<?> left = model.createNode(NodeKind.SUCCESS_CRITERIA, "warm-up left", 0, 0);
        TreeNode<?> right = model.createNode(NodeKind.SUCCESS_CRITERIA, "warm-up right", 0, 0);
        model.decompose(root, left);
        model.decompose(root, right);
        root.setDecompositionType(DecompositionType.OR);
        TreeNode<?> solution = model.createNode(NodeKind.SOLUTION, "warm-up solution", 0, 0);
        TreeNode<?> proof = model.createNode(NodeKind.SUCCESS_PROOF, "warm-up proof", 0, 0);
        for (TreeNode<?> n : List.<TreeNode<?>>of(left, right, root, solution, proof)) bigraph.addTreeNode(n);
        bigraph.createRelation(new Relation(solution, RelationType.PRODUCES, proof));
        bigraph.createRelation(new Relation(proof, RelationType.VALIDATES, left));
        FragmentRewritingCoverageAnalyzer analyzer = new FragmentRewritingCoverageAnalyzer(CoverageInput.of(model, bigraph));
        for (int i = 0; i < 20; i++) {
            for (CoverageMode mode : CoverageMode.presets()) analyzer.session(mode).coverage(root);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rule shapes the fragments rely on

    /**
     * Rejects a rule whose redex reaches further than a fragment shows. Place rules: one root holding exactly the
     * receiver tree node; the receiver's children are leaf markers, sites and tree nodes; every such tree-node child
     * has a site and otherwise only leaf markers; a receiver without a site is a chain node (which never carries stubs
     * or the slim-omitted markers). Link rules: two roots, each exactly one tree node with a site and otherwise leaf
     * nodes. With {@code slim}: no redex names a slim-omitted control.
     */
    private static void validate(Prepared p, boolean slim) {
        CompiledRule r = p.rule();
        PureBigraph b = p.redex();
        String where = "Rule " + r.name() + ": ";
        if (slim) {
            for (BigraphEntity.NodeEntity<?> n : b.getNodes()) {
                if (SLIM_OMITTED.contains(control(n)))
                    throw new IllegalStateException(where + "names " + control(n) + ", cannot be slim");
            }
        }
        List<BigraphEntity.RootEntity> roots = roots(b);
        if (r.isLinkRule()) {
            if (roots.size() != 2) throw new IllegalStateException(where + "link rule needs two roots");
            for (BigraphEntity.RootEntity root : roots) {
                BigraphEntity.NodeEntity<?> t = onlyTreeNode(b, root, where);
                if (!hasSite(b, t)) throw new IllegalStateException(where + "link endpoint without a site");
                for (BigraphEntity<?> c : b.getChildrenOf(t)) {
                    if (!(c instanceof BigraphEntity.SiteEntity) && !isLeaf(b, c))
                        throw new IllegalStateException(where + "nested link endpoint");
                }
            }
            return;
        }
        if (roots.size() != 1) throw new IllegalStateException(where + "place rule needs one root");
        BigraphEntity.NodeEntity<?> receiver = onlyTreeNode(b, roots.getFirst(), where);
        if (!receiver.getName().equals(r.receiverNode()))
            throw new IllegalStateException(where + "receiver is not the root node");
        for (BigraphEntity<?> c : b.getChildrenOf(receiver)) {
            if (!(c instanceof BigraphEntity.NodeEntity<?> n)) continue;
            String ctrl = control(n);
            if (!TREE.contains(ctrl)) {
                if (!isLeaf(b, n)) throw new IllegalStateException(where + "marker with children");
                continue;
            }
            if (!hasSite(b, n)) throw new IllegalStateException(where + "child tree node without a site");
            for (BigraphEntity<?> g : b.getChildrenOf(n)) {
                if (g instanceof BigraphEntity.NodeEntity<?> gn && (!isLeaf(b, gn) || TREE.contains(control(gn))))
                    throw new IllegalStateException(where + "redex deeper than a node fragment");
            }
        }
        if (!hasSite(b, receiver) && !GATES.contains(control(receiver)))
            throw new IllegalStateException(where + "site-less receiver that is not a chain node");
    }

    private static String control(BigraphEntity.NodeEntity<?> n) {
        return n.getControl().getNamedType().stringValue();
    }

    private static List<BigraphEntity.RootEntity> roots(PureBigraph b) {
        List<BigraphEntity.RootEntity> roots = new ArrayList<>(b.getRoots());
        roots.sort(Comparator.comparing(BigraphEntity.RootEntity::getIndex));
        return roots;
    }

    private static BigraphEntity.NodeEntity<?> onlyTreeNode(PureBigraph b, BigraphEntity.RootEntity root, String where) {
        var children = b.getChildrenOf(root);
        if (children.size() != 1 || !(children.iterator().next() instanceof BigraphEntity.NodeEntity<?> n)
                || !TREE.contains(control(n))) {
            throw new IllegalStateException(where + "a root must hold exactly one tree node");
        }
        return n;
    }

    private static boolean hasSite(PureBigraph b, BigraphEntity<?> n) {
        for (BigraphEntity<?> c : b.getChildrenOf(n)) if (c instanceof BigraphEntity.SiteEntity) return true;
        return false;
    }

    private static boolean isLeaf(PureBigraph b, BigraphEntity<?> c) {
        return c instanceof BigraphEntity.NodeEntity<?> && b.getChildrenOf(c).isEmpty();
    }

    private static Map<String, Integer> census(PureBigraph b) {
        Map<String, Integer> counts = new HashMap<>();
        for (BigraphEntity.NodeEntity<?> node : b.getNodes()) counts.merge(control(node), 1, Integer::sum);
        return counts;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Structure snapshot (read once per session from the POPP bigraph)

    /** A tree node of the structure bigraph: its control, tree children, relations and parent. */
    private static final class SNode {
        final String id;
        final String control;
        final List<SNode> children = new ArrayList<>();
        final List<SLink> links = new ArrayList<>();
        SNode parent;
        boolean domain;
        boolean intrinsic;

        SNode(String id, String control) { this.id = id; this.control = control; }
    }

    /** A relation edge between the stubs of two tree nodes. */
    private record SLink(String edge, SNode a, String stubA, SNode b, String stubB) {}

    private record Snapshot(Map<String, SNode> nodes, List<SNode> postOrder, List<SLink> links) {}

    @SuppressWarnings("unchecked")
    private static Snapshot snapshot(POPPBigraph popp, CoverageMode mode) {
        PureBigraphMutable g = popp.getBigraph();
        Map<String, SNode> nodes = new LinkedHashMap<>();
        for (BigraphEntity.NodeEntity<?> n : g.getNodes()) {
            POPPBigraphSignature ctrl = POPPBigraphSignature.forNode(n);
            if (!POPPBigraphSignature.isTreeControl(ctrl)) continue;
            SNode s = new SNode(n.getName(), ctrl.controlName());
            s.domain = POPPBigraphSignature.isKindControl(ctrl);
            s.intrinsic = s.domain && mode.isIntrinsic(POPPBigraphSignature.toNodeKind(ctrl));
            nodes.put(n.getName(), s);
        }
        Map<String, SNode> stubOwner = new HashMap<>();
        Map<String, String> stubControl = new HashMap<>();
        for (BigraphEntity.NodeEntity<?> n : g.getNodes()) {
            SNode self = nodes.get(n.getName());
            if (self == null) continue;
            for (BigraphEntity<?> c : g.getChildrenOf(n)) {
                if (!(c instanceof BigraphEntity.NodeEntity<?> child)) continue;
                POPPBigraphSignature ctrl = POPPBigraphSignature.forNode(child);
                if (POPPBigraphSignature.isTreeControl(ctrl)) {
                    SNode k = nodes.get(child.getName());
                    self.children.add(k);
                    k.parent = self;
                } else if (POPPBigraphSignature.isStubControl(ctrl)) {
                    stubOwner.put(child.getName(), self);
                    stubControl.put(child.getName(), ctrl.controlName());
                } else {
                    throw new IllegalStateException("Unexpected child " + child.getName() + " of tree node " + n.getName());
                }
            }
        }
        List<SLink> links = new ArrayList<>();
        for (BigraphEntity.Edge e : g.getEdges()) {
            List<String> stubs = new ArrayList<>();
            for (BigraphEntity<?> pt : g.getPointsFromLink(e)) {
                if (pt instanceof BigraphEntity.Port port) {
                    var stub = g.getNodeOfPort(port);
                    if (stub != null && stubOwner.containsKey(stub.getName())) stubs.add(stub.getName());
                }
            }
            // an edge without exactly two stubs (a broken file; the validator reports it) carries no relation
            if (stubs.size() != 2) {
                logger.debug("Skipping relation edge {} with {} stubs", e.getName(), stubs.size());
                continue;
            }
            SNode a = stubOwner.get(stubs.get(0)), b = stubOwner.get(stubs.get(1));
            if (a == b) continue; // a two-root redex never matches a single node
            SLink l = new SLink(e.getName(), a, stubControl.get(stubs.get(0)), b, stubControl.get(stubs.get(1)));
            links.add(l);
            a.links.add(l);
            b.links.add(l);
        }
        List<SNode> post = new ArrayList<>();
        for (SNode n : nodes.values()) if (n.parent == null) postOrder(n, post);
        return new Snapshot(nodes, post, links);
    }

    private static void postOrder(SNode n, List<SNode> out) {
        for (SNode c : n.children) postOrder(c, out);
        out.add(n);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Fragments

    /** A node of a fragment before it is built as a framework bigraph. */
    private static final class FNode {
        final String control, name, link;
        final List<FNode> kids = new ArrayList<>();

        FNode(String control, String name, String link) { this.control = control; this.name = name; this.link = link; }

        FNode add(FNode k) { kids.add(k); return this; }

        void census(Map<String, Integer> out) {
            out.merge(control, 1, Integer::sum);
            for (FNode k : kids) k.census(out);
        }

        int size() {
            int s = 1;
            for (FNode k : kids) s += k.size();
            return s;
        }

        /** Controls and nesting, names left out: equal for fragments the rules cannot tell apart. */
        String shape() {
            if (kids.isEmpty()) return link == null ? control : control + "{e}";
            List<String> ks = new ArrayList<>(kids.size());
            for (FNode k : kids) ks.add(k.shape());
            Collections.sort(ks);
            return control + (link == null ? "" : "{e}") + "(" + String.join(",", ks) + ")";
        }
    }

    private final class Session {
        final Snapshot s;
        final Map<String, Coverage> states = new HashMap<>();
        final Map<String, RewritingCoverageAnalyzer.Derivation> why = new HashMap<>();
        final PureBigraphMatcher matcher = new PureBigraphMatcher();
        int evaluations, calls, successful, maxFragment;
        long tBuild, tEncode, tMatch;

        Session(Snapshot s) {
            this.s = s;
            for (SNode n : s.nodes().values()) {
                if (n.intrinsic) {
                    states.put(n.id, Coverage.COVERED);
                    why.put(n.id, new RewritingCoverageAnalyzer.Derivation(CoverageMarker.COV_INTRINSIC, null));
                } else {
                    states.put(n.id, Coverage.UNCOVERED);
                }
            }
        }

        Coverage state(SNode n) { return states.get(n.id); }

        /** The markers {@link CoverageBigraph} gives a tree node ({@code CovIntrinsic} only without {@code slim}). */
        void markers(FNode f, SNode n) {
            Coverage c = state(n);
            f.add(new FNode(CoverageMarker.of(c).controlName(), n.id + ".state", null));
            if (n.intrinsic && !options.slim()) f.add(new FNode(CoverageMarker.COV_INTRINSIC.controlName(), n.id + ".why", null));
            if (!n.domain && c == Coverage.PARTIAL) f.add(new FNode(CoverageMarker.PART_DECOMP.controlName(), n.id + ".partwhy", null));
        }

        FNode nodeFragment(SNode n) {
            FNode f = new FNode(n.control, n.id, null);
            markers(f, n);
            for (SNode c : n.children) {
                FNode k = new FNode(c.control, c.id, null);
                markers(k, c);
                f.add(k);
            }
            return f;
        }

        List<FNode> linkFragment(SLink l) {
            FNode a = new FNode(l.a().control, l.a().id, null)
                    .add(new FNode(CoverageMarker.of(state(l.a())).controlName(), l.a().id + ".state", null))
                    .add(new FNode(l.stubA(), l.stubA() + ":" + l.edge(), l.edge()));
            FNode b = new FNode(l.b().control, l.b().id, null)
                    .add(new FNode(CoverageMarker.of(state(l.b())).controlName(), l.b().id + ".state", null))
                    .add(new FNode(l.stubB(), l.stubB() + ":" + l.edge(), l.edge()));
            return List.of(a, b);
        }

        /** Evaluates one fragment; returns the ids of the nodes whose state rose. */
        List<String> evaluate(List<FNode> roots, List<Prepared> rules) {
            evaluations++;
            long t0 = System.nanoTime();
            Map<String, Integer> available = new HashMap<>();
            int size = 0;
            for (FNode r : roots) { r.census(available); size += r.size(); }
            maxFragment = Math.max(maxFragment, size);

            tBuild += System.nanoTime() - t0;

            List<MemoHit> hits;
            String shape = null;
            if (options.memo()) {
                StringBuilder sb = new StringBuilder();
                for (FNode r : roots) sb.append(r.shape()).append("||");
                shape = sb.toString();
                hits = memo.get(shape);
                if (hits != null) return apply(roots, hits);
            }

            hits = new ArrayList<>();
            PureBigraph agent = null;
            for (Prepared p : rules) {
                if (!includes(available, p.required())) continue;
                if (options.localPrune() && !assign(p.roots(), 0, roots, new boolean[roots.size()])) continue;
                if (agent == null) {
                    t0 = System.nanoTime();
                    agent = fragmentBigraph(roots);
                    tEncode += System.nanoTime() - t0;
                }
                calls++;
                t0 = System.nanoTime();
                boolean any = false;
                // never matchFirst (see the class comment); firstMatch stops after the first usable match below
                for (PureBigraphMatch found : matcher.matchAll(agent, p.rule().rule())) {
                    Match m = found.getJLibMatchResult();
                    Map<String, String> emb = new HashMap<>();
                    m.emb_nodes.forEach((rn, an) -> emb.put(rn.getEditable().getName(), an.getEditable().getName()));
                    int receiver = rootIndex(roots, emb.get(p.rule().receiverNode()));
                    int giver = p.rule().giverNode() == null ? -1 : rootIndex(roots, emb.get(p.rule().giverNode()));
                    if (receiver < 0) continue; // receiver is not a fragment root: not this fragment's match
                    any = true;
                    hits.add(new MemoHit(p, receiver, giver));
                    if (options.firstMatch()) break;
                }
                tMatch += System.nanoTime() - t0;
                if (any) successful++;

            }
            if (options.memo()) memo.put(shape, List.copyOf(hits));
            return apply(roots, hits);
        }

        /** The fragment as a framework bigraph over the rules' signature. */
        PureBigraph fragmentBigraph(List<FNode> roots) {
            PureBigraphMutable g = pureBuilder(SIGNATURE).createMutable();
            Map<String, BigraphEntity.Edge> edges = new HashMap<>();
            for (FNode r : roots) emit(g, g.addRoot(), r, edges);
            return g;
        }

        void emit(PureBigraphMutable g, BigraphEntity<?> parent, FNode f, Map<String, BigraphEntity.Edge> edges) {
            var n = g.addNode(parent, SIGNATURE.getControlByName(f.control), f.name);
            if (f.link != null) g.connectNodeToLink(n, edges.computeIfAbsent(f.link, g::addEdge));
            for (FNode k : f.kids) emit(g, n, k, edges);
        }

        int rootIndex(List<FNode> roots, String name) {
            for (int i = 0; i < roots.size(); i++) if (roots.get(i).name.equals(name)) return i;
            return -1;
        }

        List<String> apply(List<FNode> roots, List<MemoHit> hits) {
            List<String> changed = new ArrayList<>(1);
            for (MemoHit h : hits) {
                String receiver = roots.get(h.receiverRoot()).name;
                CompiledRule rule = h.rule().rule();
                if (rule.target().compareTo(states.get(receiver)) <= 0) continue;
                String giver = h.giverRoot() < 0 ? null : roots.get(h.giverRoot()).name;
                states.put(receiver, rule.target());
                why.put(receiver, new RewritingCoverageAnalyzer.Derivation(rule.reason(), giver));
                if (!changed.contains(receiver)) changed.add(receiver);
            }
            return changed;
        }

        List<String> evaluateNode(SNode n) {
            FNode f = nodeFragment(n);
            return evaluate(List.of(f), placeRules);
        }

        List<String> evaluateLink(SLink l) {
            return evaluate(linkFragment(l), linkRules);
        }

        void run() {
            // generous bound: every evaluation that changes something upgrades a node, at most twice per node
            int budget = 4 * (s.nodes().size() + s.links().size()) * (s.nodes().size() + 2) + 16;
            if (options.incremental()) {
                // keys: SNode (node fragment) or SLink (link fragment), FIFO without duplicates
                LinkedHashSet<Object> work = new LinkedHashSet<>(s.postOrder());
                work.addAll(s.links());
                while (!work.isEmpty()) {
                    if (--budget < 0) throw new IllegalStateException("Coverage evaluation did not terminate");
                    Object next = work.removeFirst();
                    List<String> changed = next instanceof SNode n ? evaluateNode(n) : evaluateLink((SLink) next);
                    for (String id : changed) {
                        SNode x = s.nodes().get(id);
                        work.add(x);
                        if (x.parent != null) work.add(x.parent);
                        work.addAll(x.links);
                    }
                }
            } else {
                for (boolean changed = true; changed; ) {
                    if (--budget < 0) throw new IllegalStateException("Coverage evaluation did not terminate");
                    changed = false;
                    for (SNode n : s.postOrder()) changed |= !evaluateNode(n).isEmpty();
                    for (SLink l : s.links()) changed |= !evaluateLink(l).isEmpty();
                }
            }
        }
    }

    private static boolean includes(Map<String, Integer> available, Map<String, Integer> required) {
        for (var need : required.entrySet()) {
            if (available.getOrDefault(need.getKey(), 0) < need.getValue()) return false;
        }
        return true;
    }

    @Override
    public CoverageSession session(CoverageMode mode) {
        POPPModel model = input.model();
        POPPBigraph bigraph = input.bigraph();
        if (model == null || bigraph == null) throw new IllegalStateException("No POPP model loaded");
        long t0 = System.nanoTime();
        Session session = new Session(snapshot(bigraph, mode));
        long snap = System.nanoTime() - t0;
        session.run();
        lastStats = new RewritingStats(session.evaluations, session.calls, session.successful, session.maxFragment,
                session.tBuild + snap, session.tEncode, session.tMatch);
        logger.debug("[fragment session {}] {}", mode, lastStats);
        return new RewritingSession(mode, model.getRelations(), new RewritingCoverageAnalyzer.Readout(session.states, session.why));    }
}
