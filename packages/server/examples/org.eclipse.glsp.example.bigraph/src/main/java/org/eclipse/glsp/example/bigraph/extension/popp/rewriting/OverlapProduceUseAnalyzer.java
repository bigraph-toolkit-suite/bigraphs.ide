package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.reactivesystem.InstantiationMap;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.core.reactivesystem.TrackingMap;

import java.util.*;

/**
 * Produce-use dependencies between arbitrary reaction rules by overlapping them, as in the critical pair analysis of
 * graph transformation, but only deciding whether an overlap exists: {@code r} enables {@code s} if the reactum of
 * {@code r} and the redex of {@code s} can be glued so that {@code s} uses something {@code r} changed.
 * <p>
 * <b>What a rule changes.</b> Read off the redex, the reactum and the rule's {@link TrackingMap}, which maps a reactum
 * node to the redex node it keeps. Without a tracking map every reactum node counts as new.
 * <ul>
 *   <li><i>new structure</i>: a new node, or a kept node under a different parent.</li>
 *   <li><i>removed children</i>: a kept node that lost a child. In bigraphs, this can give a new match, since a redex
 *   node without a site only matches a node with exactly the listed children.</li>
 *   <li><i>changed links</i>: a kept node whose ports are linked differently, and links that lost a point. The latter
 *   can give a new match as well, since a closed edge of a redex only matches an edge with exactly its points.</li>
 *   <li><i>moved parameters</i>: a site under a different parent, an instantiation map that copies or drops a
 *   parameter, or inner names (the links into the parameters) connected differently. The contents of a parameter are
 *   unknown, so such a rule enables every rule.</li>
 * </ul>
 * <b>Gluing.</b> Two nodes can be glued if they have the same control, their children can be glued (see
 * {@link #childrenFit}), and their parents can be glued as well, up to the first pattern root on either side, where the
 * context may supply anything. Links are not compared. Leaving out constraints only adds dependencies, so the result
 * over-approximates the real dependencies, as the {@link MonotoneFixpointRewriter} requires.
 * <p>
 * The analysis runs once per rule set, in time quadratic in the number of rules, and compares small patterns.
 * <p>
 * <b>Extending.</b> {@link #changes} (what a rule changes), {@link #enables} (whether a change can be used) and
 * {@link #gluer} (when two nodes can be the same) can be overridden, e.g. to compare links as well, or to take
 * domain knowledge into account.
 */
public class OverlapProduceUseAnalyzer implements ProduceUseAnalyzer {

    /** What a rewrite by one rule changes, as nodes of its reactum (or, for removals, of its redex). */
    protected record Changes(List<NodeEntity<?>> newStructure,
                             List<NodeEntity<?>> lostChildren,
                             List<NodeEntity<?>> relinked,
                             boolean lostLinkPoints,
                             boolean movedParameters) {}

    @Override
    public boolean[][] analyzeRuleSet(List<Rule> rules) {
        int ruleCount = rules.size();
        List<Changes> changes = new ArrayList<>(ruleCount);
        for (Rule rule : rules) changes.add(changes(rule.reaction()));
        boolean[][] enablesMatrix = new boolean[ruleCount][ruleCount];
        for (int ruleIndex = 0; ruleIndex < ruleCount; ruleIndex++) {
            for (int differentRuleIndex = 0; differentRuleIndex < ruleCount; differentRuleIndex++) {
                enablesMatrix[ruleIndex][differentRuleIndex] = enables(rules.get(ruleIndex).reaction(),
                        changes.get(ruleIndex), rules.get(differentRuleIndex).reaction().getRedex());
            }
        }
        return enablesMatrix;
    }

    /**
     * Whether some gluing of the reactum of {@code rule} and {@code otherRedex} lets the other rule use what
     * {@code rule} changed.
     */
    protected boolean enables(ParametricReactionRule<PureBigraph> rule, Changes changes, PureBigraph otherRedex) {
        if (changes.movedParameters()) return true;
        PureBigraph reactum = rule.getReactum();
        Gluer gluer = gluer(reactum, otherRedex);
        for (NodeEntity<?> changed : changes.newStructure()) {
            for (NodeEntity<?> used : otherRedex.getNodes()) if (gluer.glue(changed, used)) return true;
        }
        for (NodeEntity<?> changed : changes.lostChildren()) {
            for (NodeEntity<?> used : otherRedex.getNodes()) {
                // only a node without a site needs exactly its children
                if (!hasSite(otherRedex, used) && gluer.glue(changed, used)) return true;
            }
        }
        for (NodeEntity<?> changed : changes.relinked()) {
            for (NodeEntity<?> used : otherRedex.getNodes()) {
                if (!otherRedex.getPorts(used).isEmpty() && gluer.glue(changed, used)) return true;
            }
        }
        return changes.lostLinkPoints() && !otherRedex.getEdges().isEmpty(); // a closed edge must have exactly its points
    }

    // What a rule changes

    /** What a rewrite by {@code rule} changes. */
    protected Changes changes(ParametricReactionRule<PureBigraph> rule) {
        PureBigraph redex = rule.getRedex(), reactum = rule.getReactum();
        Map<String, NodeEntity<?>> redexByName = new HashMap<>();
        for (NodeEntity<?> node : redex.getNodes()) redexByName.put(node.getName(), node);
        TrackingMap tracking = rule.getTrackingMap();

        // reactum node -> the redex node it keeps (absent: new)
        Map<NodeEntity<?>, NodeEntity<?>> kept = new IdentityHashMap<>();
        Map<NodeEntity<?>, NodeEntity<?>> keptBy = new IdentityHashMap<>();
        if (tracking != null) {
            for (NodeEntity<?> node : reactum.getNodes()) {
                String from = tracking.get(node.getName());
                NodeEntity<?> old = from == null || from.isEmpty() ? null : redexByName.get(from);
                if (old != null && control(old).equals(control(node)) && !keptBy.containsKey(old)) {
                    kept.put(node, old);
                    keptBy.put(old, node);
                }
            }
        }

        List<NodeEntity<?>> newStructure = new ArrayList<>();
        List<NodeEntity<?>> lostChildren = new ArrayList<>();
        List<NodeEntity<?>> relinked = new ArrayList<>();
        for (NodeEntity<?> node : reactum.getNodes()) {
            NodeEntity<?> old = kept.get(node);
            if (old == null || !sameParent(redex.getParent(old), reactum.getParent(node), keptBy)) {
                newStructure.add(node);
                continue;
            }
            // a redex child of old that is no longer below node (deleted, or moved elsewhere)
            for (BigraphEntity<?> child : redex.getChildrenOf(old)) {
                if (child instanceof NodeEntity<?> childNode) {
                    NodeEntity<?> now = keptBy.get(childNode);
                    if (now == null || reactum.getParent(now) != node) {
                        lostChildren.add(node);
                        break;
                    }
                }
            }
            if (!linksOf(redex, old, keptBy, reactum, true).equals(linksOf(reactum, node, keptBy, reactum, false))) {
                relinked.add(node);
            }
        }
        // A deleted node below a kept node is found above. A deleted node directly in a region needs nothing: only a
        // node without a site requires exactly its children, and a region of a redex is open to the context.

        return new Changes(newStructure, lostChildren, relinked, lostLinkPoints(redex, reactum, keptBy),
                movedParameters(rule, keptBy));
    }

    /** Whether the parent of a kept node is the same before and after (a kept node, or the same region). */
    private static boolean sameParent(BigraphEntity<?> before, BigraphEntity<?> after,
                                      Map<NodeEntity<?>, NodeEntity<?>> keptBy) {
        if (before instanceof NodeEntity<?> beforeNode) return after == keptBy.get(beforeNode);
        if (before instanceof BigraphEntity.RootEntity rootBefore && after instanceof BigraphEntity.RootEntity rootAfter) {
            return rootBefore.getIndex() == rootAfter.getIndex();
        }
        return false;
    }

    /**
     * The links of a node's ports, by port index, each as the set of the other points on the link (kept nodes named by
     * their redex node, inner names by name) and the outer name, if any. New nodes on a link are left out, since a
     * match that uses them uses a new node, which {@code newStructure} covers.
     */
    private static Map<Integer, String> linksOf(PureBigraph bigraph, NodeEntity<?> node,
                                                Map<NodeEntity<?>, NodeEntity<?>> keptBy,
                                                PureBigraph reactum, boolean isRedex) {
        Map<Integer, String> out = new TreeMap<>();
        for (BigraphEntity.Port port : bigraph.getPorts(node)) {
            BigraphEntity.Link link = bigraph.getLinkOfPoint(port);
            if (link == null) continue;
            SortedSet<String> points = new TreeSet<>();
            for (BigraphEntity<?> point : bigraph.getPointsFromLink(link)) {
                if (point instanceof BigraphEntity.Port pointPort) {
                    NodeEntity<?> owner = bigraph.getNodeOfPort(pointPort);
                    String name = redexName(owner, keptBy, isRedex);
                    if (name != null) points.add(name + "#" + pointPort.getIndex());
                } else if (point instanceof BigraphEntity.InnerName innerName) {
                    points.add("inner:" + innerName.getName());
                }
            }
            String kind = link instanceof BigraphEntity.OuterName outerName ? "outer:" + outerName.getName() : "edge";
            out.put(port.getIndex(), kind + points);
        }
        return out;
    }

    /** The redex name of a point's owner if the node is kept by the rule, else {@code null}. */
    private static String redexName(NodeEntity<?> owner,
                                    Map<NodeEntity<?>, NodeEntity<?>> keptBy,
                                    boolean isRedex) {
        if (isRedex) return keptBy.containsKey(owner) ? owner.getName() : null;
        for (Map.Entry<NodeEntity<?>, NodeEntity<?>> entry : keptBy.entrySet()) {
            if (entry.getValue() == owner) return entry.getKey().getName();
        }
        return null;
    }

    /** Whether some link of the redex loses a point that the reactum keeps elsewhere, or loses an inner name. */
    private static boolean lostLinkPoints(PureBigraph redex, PureBigraph reactum,
                                          Map<NodeEntity<?>, NodeEntity<?>> keptBy) {
        Set<String> together = new HashSet<>(), stillTogether = new HashSet<>();
        pairs(redex, together, keptBy, true);
        pairs(reactum, stillTogether, keptBy, false);
        return !stillTogether.containsAll(together);
    }

    /** Every pair of kept points (or inner names) that share a link. */
    private static void pairs(PureBigraph bigraph, Set<String> out,
                              Map<NodeEntity<?>, NodeEntity<?>> keptBy, boolean isRedex) {
        List<BigraphEntity.Link> links = new ArrayList<>(bigraph.getEdges());
        links.addAll(bigraph.getOuterNames());
        for (BigraphEntity.Link link : links) {
            List<String> points = new ArrayList<>();
            for (BigraphEntity<?> point : bigraph.getPointsFromLink(link)) {
                if (point instanceof BigraphEntity.Port port) {
                    String name = redexName(bigraph.getNodeOfPort(port), keptBy, isRedex);
                    if (name != null) points.add(name + "#" + port.getIndex());
                } else if (point instanceof BigraphEntity.InnerName innerName) {
                    points.add("inner:" + innerName.getName());
                }
            }
            if (link instanceof BigraphEntity.OuterName outerName) points.add("outer:" + outerName.getName());
            for (String first : points) {
                for (String second : points) if (first.compareTo(second) < 0) out.add(first + "|" + second);
            }
        }
    }

    /**
     * Whether the parameters end up elsewhere (copied, dropped, or under a different parent than their site), or are
     * linked differently (inner names connected to other links than before).
     */
    private static boolean movedParameters(ParametricReactionRule<PureBigraph> rule,
                                           Map<NodeEntity<?>, NodeEntity<?>> keptBy) {
        PureBigraph redex = rule.getRedex(), reactum = rule.getReactum();
        InstantiationMap instantiation = rule.getInstantationMap();
        int sites = redex.getSites().size();
        if (reactum.getSites().size() != sites) return true;
        if (instantiation != null && !instantiation.isIdentity()) return true;
        Map<Integer, BigraphEntity<?>> parentBefore = new HashMap<>();
        for (BigraphEntity.SiteEntity site : redex.getSites()) parentBefore.put(site.getIndex(), redex.getParent(site));
        for (BigraphEntity.SiteEntity site : reactum.getSites()) {
            if (!sameParent(parentBefore.get(site.getIndex()), reactum.getParent(site), keptBy)) return true;
        }
        return !innerNameLinks(redex, keptBy, true).equals(innerNameLinks(reactum, keptBy, false));
    }

    /** For every inner name, the other inner names, the outer name and the kept points on its link. */
    private static Map<String, Set<String>> innerNameLinks(PureBigraph bigraph,
                                                         Map<NodeEntity<?>, NodeEntity<?>> keptBy,
                                                         boolean isRedex) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (BigraphEntity.InnerName inner : bigraph.getInnerNames()) {
            Set<String> others = new TreeSet<>();
            BigraphEntity.Link link = bigraph.getLinkOfPoint(inner);
            if (link != null) {
                if (link instanceof BigraphEntity.OuterName outerName) others.add("outer:" + outerName.getName());
                for (BigraphEntity<?> point : bigraph.getPointsFromLink(link)) {
                    if (point instanceof BigraphEntity.InnerName other && other != inner) others.add("inner:" + other.getName());
                    else if (point instanceof BigraphEntity.Port port) {
                        String name = redexName(bigraph.getNodeOfPort(port), keptBy, isRedex);
                        if (name != null) others.add(name + "#" + port.getIndex());
                    }
                }
            }
            out.put(inner.getName(), others);
        }
        return out;
    }

    // Gluing

    /** Decides whether a node of {@code reactum} and a node of {@code redex} can be glued. */
    protected Gluer gluer(PureBigraph reactum, PureBigraph redex) {
        return new Gluer(reactum, redex);
    }

    /** Decides whether a node of a reactum and a node of a redex can be the same node of some agent. */
    protected static class Gluer {
        protected final PureBigraph reactum, redex;
        private final Map<BigraphEntity<?>, Map<BigraphEntity<?>, Boolean>> memo = new IdentityHashMap<>();

        protected Gluer(PureBigraph reactum, PureBigraph redex) {
            this.reactum = reactum;
            this.redex = redex;
        }

        /** The two nodes, their children and their parents (up to a pattern root on either side) can be glued. */
        protected boolean glue(NodeEntity<?> reactumNode, NodeEntity<?> redexNode) {
            if (!down(reactumNode, redexNode)) return false;
            BigraphEntity<?> reactumParent = reactum.getParent(reactumNode), redexParent = redex.getParent(redexNode);
            if (reactumParent instanceof NodeEntity<?> reactumParentNode && redexParent instanceof NodeEntity<?> redexParentNode) {
                return glue(reactumParentNode, redexParentNode);
            }
            return true; // a pattern root: the context supplies the parent
        }

        /** Same control, and the children can be glued. */
        protected boolean down(NodeEntity<?> reactumNode, NodeEntity<?> redexNode) {
            if (!control(reactumNode).equals(control(redexNode))) return false;
            Map<BigraphEntity<?>, Boolean> row = memo.computeIfAbsent(reactumNode, key -> new IdentityHashMap<>());
            Boolean known = row.get(redexNode);
            if (known != null) return known;
            row.put(redexNode, true); // patterns are trees, a revisit could only add dependencies, which stays sound
            boolean fits = childrenFit(nodeChildren(reactum, reactumNode), hasSite(reactum, reactumNode),
                    nodeChildren(redex, redexNode), hasSite(redex, redexNode));
            row.put(redexNode, fits);
            return fits;
        }

        /**
         * A node without a site has exactly the listed children, so every child of the other node must be glued with
         * one of them (distinct ones). With a site on both sides, the children can be different nodes.
         */
        protected boolean childrenFit(List<NodeEntity<?>> reactumChildren, boolean reactumOpen,
                                      List<NodeEntity<?>> redexChildren, boolean redexOpen) {
            if (reactumOpen && redexOpen) return true;
            if (!reactumOpen && !redexOpen && reactumChildren.size() != redexChildren.size()) return false;
            if (!reactumOpen) {
                // every child of the redex node onto one of the reactum node
                return assign(redexChildren, reactumChildren, true, 0, new boolean[reactumChildren.size()]);
            }
            // every child of the reactum node onto one of the redex node
            return assign(reactumChildren, redexChildren, false, 0, new boolean[redexChildren.size()]);
        }

        /** Backtracking: each of {@code from}, starting at {@code next}, glued with a distinct one of {@code to}. */
        private boolean assign(List<NodeEntity<?>> from, List<NodeEntity<?>> to, boolean fromIsRedex, int next, boolean[] used) {
            if (next == from.size()) return true;
            for (int j = 0; j < to.size(); j++) {
                if (used[j]) continue;
                boolean fits = fromIsRedex ? down(to.get(j), from.get(next)) : down(from.get(next), to.get(j));
                if (!fits) continue;
                used[j] = true;
                if (assign(from, to, fromIsRedex, next + 1, used)) return true;
                used[j] = false;
            }
            return false;
        }
    }

    protected static List<NodeEntity<?>> nodeChildren(PureBigraph bigraph, NodeEntity<?> node) {
        List<NodeEntity<?>> out = new ArrayList<>();
        for (BigraphEntity<?> child : bigraph.getChildrenOf(node)) if (child instanceof NodeEntity<?> childNode) out.add(childNode);
        return out;
    }

    protected static boolean hasSite(PureBigraph bigraph, NodeEntity<?> node) {
        for (BigraphEntity<?> child : bigraph.getChildrenOf(node)) if (child instanceof BigraphEntity.SiteEntity) return true;
        return false;
    }

    protected static String control(NodeEntity<?> node) {
        return node.getControl().getNamedType().stringValue();
    }
}
