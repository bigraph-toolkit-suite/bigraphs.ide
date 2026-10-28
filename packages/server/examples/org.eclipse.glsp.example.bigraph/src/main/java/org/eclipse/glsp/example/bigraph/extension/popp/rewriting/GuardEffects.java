package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import org.bigraphs.framework.core.ControlStatus;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.reactivesystem.InstantiationMap;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.core.reactivesystem.TrackingMap;

import java.util.*;
import java.util.function.Consumer;

/**
 * How many occurrences of a guard a rewrite by a rule creates and destroys, decided from the rule and the guard alone:
 * an overlap analysis like {@link OverlapProduceUseAnalyzer}, but between a rule and a guard, and counting.
 * <p>
 * An <em>occurrence</em> of a guard is a set of agent nodes the guard pattern embeds onto. A rewrite can only create or
 * destroy an occurrence that overlaps the rule's pattern. The guard is glued with the redex (for destroyed occurrences)
 * and the reactum (for created ones) in every possible way: every guard node either on a pattern node or outside it
 * (in the context or a parameter). Each gluing is checked against the other side of the rule, through the tracking map:
 * <ul>
 *   <li>It <em>survives</em> the rewrite if all its pattern nodes are kept and keep the parent, children and siblings
 *       the guard needs. Such an overlap does not change the count.</li>
 *   <li>Otherwise, if the guard lies <em>completely</em> in the pattern (no guard node outside, and a guard node that
 *       must have exactly its children only on a pattern node that has exactly its children), the occurrence exists at
 *       every match and is created or destroyed by every rewrite: it is counted.</li>
 *   <li>Otherwise (it reaches into the context or a parameter and is affected) whether it exists depends on the agent:
 *       the effect is <em>unknown</em>.</li>
 * </ul>
 * The effect is also unknown if the rule moves, copies or drops parameters, if the guard has a node that must have
 * exactly its children while the rule changes the nodes directly in a region (the context's node there gets other
 * children), and for guards with link constraints (closed edges, inner names, an outer name with two points). Every
 * other guard is link-agnostic, so relinking never changes its occurrences. A node must have exactly its children if it
 * has no site. An atomic node never has children, so it can never be the context node of a region.
 * <p>
 * Known effects are exact for every agent and match, unknown ones mean "match the guard again".
 */
public final class GuardEffects {
    private GuardEffects() {}

    /**
     * {@code effects[rule][guard]}: the change of the number of occurrences of the guard per rewrite by the rule, or
     * {@code null} if it is unknown.
     */
    public static Integer[][] analyze(List<Rule> rules, List<Guard> guards) {
        Integer[][] effects = new Integer[rules.size()][guards.size()];
        for (int i = 0; i < rules.size(); i++) {
            for (int j = 0; j < guards.size(); j++) {
                effects[i][j] = effect(rules.get(i).reaction(), guards.get(j).pattern().getRedex());
            }
        }
        return effects;
    }

    /** The change of the number of occurrences of {@code guard} per rewrite by {@code rule}, or null if it is unknown. */
    public static Integer effect(ParametricReactionRule<PureBigraph> rule, PureBigraph guard) {
        if (!linkAgnostic(guard)) return null;
        PureBigraph redex = rule.getRedex(), reactum = rule.getReactum();
        Map<NodeEntity<?>, NodeEntity<?>> forward = tracking(rule); // redex node -> reactum node
        Map<NodeEntity<?>, NodeEntity<?>> backward = new IdentityHashMap<>();
        forward.forEach((redexNode, reactumNode) -> backward.put(reactumNode, redexNode));
        if (movesParameters(rule, forward)) return null;
        if (hasExactParent(guard) && changesRegions(redex, reactum, forward)) return null;

        Side before = new Side(redex), after = new Side(reactum);
        Pattern pattern = new Pattern(guard);
        Integer destroyed = changedOccurrences(pattern, before, after, forward);
        if (destroyed == null) return null;
        Integer created = changedOccurrences(pattern, after, before, backward);
        if (created == null) return null;
        return created - destroyed;
    }

    /**
     * The occurrences of {@code pattern} that lie completely in {@code from} and do not survive into {@code to} (the
     * destroyed ones from the redex, the created ones from the reactum), or null if an occurrence that only partly lies
     * in {@code from} may not survive.
     */
    private static Integer changedOccurrences(Pattern pattern, Side from, Side to, Map<NodeEntity<?>, NodeEntity<?>> carriedOver) {
        // occurrences are node sets: one surviving gluing onto a set keeps the occurrence
        List<Set<NodeEntity<?>>> images = new ArrayList<>();
        List<Boolean> survived = new ArrayList<>();
        boolean[] unknown = {false};
        new Gluings(pattern, from).forEach(gluing -> {
            if (unknown[0]) return;
            boolean survives = survives(pattern, gluing, from, to, carriedOver);
            if (!isComplete(pattern, gluing, from)) {
                if (!survives) unknown[0] = true;
                return;
            }
            Set<NodeEntity<?>> image = Collections.newSetFromMap(new IdentityHashMap<>());
            image.addAll(Arrays.asList(gluing));
            int known = indexOfSameSet(images, image);
            if (known < 0) {
                images.add(image);
                survived.add(survives);
            } else {
                survived.set(known, survived.get(known) || survives);
            }
        });
        if (unknown[0]) return null;
        return (int) survived.stream().filter(survives -> !survives).count();
    }

    /** The index of a set with the same elements (identity sets do not compare by content), or -1. */
    private static int indexOfSameSet(List<Set<NodeEntity<?>>> sets, Set<NodeEntity<?>> set) {
        for (int i = 0; i < sets.size(); i++) {
            if (sets.get(i).size() == set.size() && sets.get(i).containsAll(set)) return i;
        }
        return -1;
    }

    // The guard and the rule sides

    /** A guard pattern: nodes parent before child, parent and children per node, which ones have exactly their children. */
    private static final class Pattern {
        final List<NodeEntity<?>> nodes = new ArrayList<>();
        /** Index of the parent node, -1 for a root. */
        final int[] parent;
        final List<List<Integer>> children = new ArrayList<>();
        final boolean[] exact;
        final String[] control;

        Pattern(PureBigraph bigraph) {
            Deque<BigraphEntity<?>> queue = new ArrayDeque<>();
            for (BigraphEntity.RootEntity root : bigraph.getRoots()) queue.add(root);
            while (!queue.isEmpty()) {
                for (BigraphEntity<?> child : bigraph.getChildrenOf(queue.poll())) {
                    if (child instanceof NodeEntity<?> node) {
                        nodes.add(node);
                        queue.add(node);
                    }
                }
            }
            int size = nodes.size();
            parent = new int[size];
            exact = new boolean[size];
            control = new String[size];
            for (int i = 0; i < size; i++) {
                NodeEntity<?> node = nodes.get(i);
                BigraphEntity<?> parentEntity = bigraph.getParent(node);
                parent[i] = parentEntity instanceof NodeEntity<?> parentNode ? indexOf(parentNode) : -1;
                exact[i] = !hasSite(bigraph, node);
                control[i] = control(node);
                children.add(new ArrayList<>());
            }
            for (int i = 0; i < size; i++) if (parent[i] >= 0) children.get(parent[i]).add(i);
        }

        int indexOf(NodeEntity<?> node) {
            for (int i = 0; i < nodes.size(); i++) if (nodes.get(i) == node) return i;
            throw new IllegalStateException("Not a node of the pattern: " + node.getName());
        }
    }

    /** One side of a rule (redex or reactum). */
    private static final class Side {
        final PureBigraph bigraph;
        final List<NodeEntity<?>> nodes;

        Side(PureBigraph bigraph) {
            this.bigraph = bigraph;
            this.nodes = new ArrayList<>(bigraph.getNodes());
        }

        BigraphEntity<?> parent(NodeEntity<?> node) { return bigraph.getParent(node); }

        boolean topLevel(NodeEntity<?> node) { return bigraph.getParent(node) instanceof BigraphEntity.RootEntity; }

        int root(NodeEntity<?> node) { return ((BigraphEntity.RootEntity) bigraph.getParent(node)).getIndex(); }

        boolean open(NodeEntity<?> node) { return hasSite(bigraph, node); }

        List<NodeEntity<?>> nodeChildren(BigraphEntity<?> entity) {
            List<NodeEntity<?>> out = new ArrayList<>();
            for (BigraphEntity<?> child : bigraph.getChildrenOf(entity)) if (child instanceof NodeEntity<?> node) out.add(node);
            return out;
        }

        List<NodeEntity<?>> topLevelOf(int rootIndex) {
            for (BigraphEntity.RootEntity root : bigraph.getRoots()) if (root.getIndex() == rootIndex) return nodeChildren(root);
            return List.of();
        }
    }

    // Gluings

    /**
     * Every way to glue the guard with a pattern so that both could be part of one agent: an array with, per guard
     * node, the pattern node it is glued to, or null if it lies outside the pattern (at least one is glued). Leaving out
     * constraints only adds gluings, which only makes effects unknown, never wrong.
     */
    private static final class Gluings {
        final Pattern guard;
        final Side side;
        final NodeEntity<?>[] gluing;

        Gluings(Pattern guard, Side side) {
            this.guard = guard;
            this.side = side;
            this.gluing = new NodeEntity<?>[guard.nodes.size()];
        }

        void forEach(Consumer<NodeEntity<?>[]> action) {
            assign(0, action);
        }

        private void assign(int guardNode, Consumer<NodeEntity<?>[]> action) {
            if (guardNode == gluing.length) {
                if (Arrays.stream(gluing).anyMatch(Objects::nonNull) && consistent()) action.accept(gluing.clone());
                return;
            }
            gluing[guardNode] = null;
            if (outsideFits(guardNode)) assign(guardNode + 1, action);
            for (NodeEntity<?> candidate : side.nodes) {
                if (!control(candidate).equals(guard.control[guardNode]) || used(candidate, guardNode)
                        || !insideFits(guardNode, candidate)) continue;
                gluing[guardNode] = candidate;
                assign(guardNode + 1, action);
                gluing[guardNode] = null;
            }
        }

        private boolean used(NodeEntity<?> candidate, int upTo) {
            for (int j = 0; j < upTo; j++) if (gluing[j] == candidate) return true;
            return false;
        }

        /** Guard node {@code guardNode} outside the pattern. Its parent (if any) is assigned already. */
        private boolean outsideFits(int guardNode) {
            int parent = guard.parent[guardNode];
            if (parent < 0 || gluing[parent] == null) return true;
            return side.open(gluing[parent]); // in a parameter of its parent's pattern node
        }

        /** Guard node {@code guardNode} glued to {@code candidate}. Its parent (if any) is assigned already. */
        private boolean insideFits(int guardNode, NodeEntity<?> candidate) {
            int parent = guard.parent[guardNode];
            if (parent < 0) return true;
            if (gluing[parent] == null && inParameter(parent)) return false; // below a node in a parameter
            if (gluing[parent] != null) return side.parent(candidate) == gluing[parent];
            return side.topLevel(candidate); // the parent is a context node
        }

        /** Guard node {@code guardNode} is outside, below a glued one: in a parameter. */
        private boolean inParameter(int guardNode) {
            for (int ancestor = guard.parent[guardNode]; ancestor >= 0; ancestor = guard.parent[ancestor]) {
                if (gluing[ancestor] != null) return true;
            }
            return false;
        }

        /** The constraints that need all of a node's children assigned. */
        private boolean consistent() {
            for (int i = 0; i < gluing.length; i++) {
                List<Integer> children = guard.children.get(i);
                if (gluing[i] != null) {
                    // a node with exactly its children: every child on the pattern side is the image of a guard child
                    if (guard.exact[i]) {
                        for (NodeEntity<?> child : side.nodeChildren(gluing[i])) {
                            if (children.stream().noneMatch(guardChild -> gluing[guardChild] == child)) return false;
                        }
                    }
                } else {
                    // a context node: its glued children are top level, in one region
                    Integer region = null;
                    for (int child : children) {
                        if (gluing[child] == null) continue;
                        int root = side.root(gluing[child]);
                        if (region != null && region != root) return false;
                        region = root;
                    }
                    if (region != null && guard.exact[i]) {
                        for (NodeEntity<?> topLevel : side.topLevelOf(region)) {
                            if (children.stream().noneMatch(guardChild -> gluing[guardChild] == topLevel)) return false;
                        }
                    }
                }
            }
            return true;
        }
    }

    /** No guard node outside, and no guard node with exactly its children on a pattern node with a site. */
    private static boolean isComplete(Pattern guard, NodeEntity<?>[] gluing, Side side) {
        for (int i = 0; i < gluing.length; i++) {
            if (gluing[i] == null) return false;
            if (guard.exact[i] && side.open(gluing[i])) return false;
        }
        return true;
    }

    /**
     * Whether an occurrence with this gluing, if it exists on side {@code from}, still exists on side {@code to}: its
     * pattern nodes are carried over by {@code carriedOver} and keep what the guard needs.
     */
    private static boolean survives(Pattern guard, NodeEntity<?>[] gluing, Side from, Side to,
                                    Map<NodeEntity<?>, NodeEntity<?>> carriedOver) {
        for (int i = 0; i < gluing.length; i++) {
            NodeEntity<?> before = gluing[i];
            if (before == null) continue;
            NodeEntity<?> after = carriedOver.get(before);
            if (after == null) return false; // deleted (or new, seen from the reactum)
            int parent = guard.parent[i];
            if (parent >= 0) {
                if (gluing[parent] != null) {
                    if (to.parent(after) != carriedOver.get(gluing[parent])) return false;
                } else if (!to.topLevel(after) || to.root(after) != from.root(before)) {
                    return false; // under a context node, it must stay at the top level of the same region
                }
            }
            if (guard.exact[i]) {
                // no new children: every child on the other side is carried over from a child here
                for (NodeEntity<?> childAfter : to.nodeChildren(after)) {
                    if (from.nodeChildren(before).stream().noneMatch(child -> carriedOver.get(child) == childAfter)) return false;
                }
                if (!from.open(before) && to.open(after)) return false;
            }
        }
        for (int i = 0; i < gluing.length; i++) {
            if (gluing[i] != null || !guard.exact[i]) continue;
            // an exact context node with glued children: no new nodes at the top level of that region
            for (int child : guard.children.get(i)) {
                if (gluing[child] == null) continue;
                int region = from.root(gluing[child]);
                List<NodeEntity<?>> topLevelBefore = from.topLevelOf(region);
                for (NodeEntity<?> topLevelAfter : to.topLevelOf(region)) {
                    if (topLevelBefore.stream().noneMatch(node -> carriedOver.get(node) == topLevelAfter)) return false;
                }
                break;
            }
        }
        return true;
    }

    // The rule

    /** Redex node to the reactum node that keeps it (same control, from the tracking map). */
    private static Map<NodeEntity<?>, NodeEntity<?>> tracking(ParametricReactionRule<PureBigraph> rule) {
        PureBigraph redex = rule.getRedex(), reactum = rule.getReactum();
        Map<String, NodeEntity<?>> redexByName = new HashMap<>();
        for (NodeEntity<?> node : redex.getNodes()) redexByName.put(node.getName(), node);
        Map<NodeEntity<?>, NodeEntity<?>> forward = new IdentityHashMap<>();
        TrackingMap tracking = rule.getTrackingMap();
        if (tracking == null) return forward;
        for (NodeEntity<?> node : reactum.getNodes()) {
            String from = tracking.get(node.getName());
            NodeEntity<?> old = from == null || from.isEmpty() ? null : redexByName.get(from);
            if (old != null && control(old).equals(control(node)) && !forward.containsKey(old)) forward.put(old, node);
        }
        return forward;
    }

    /** Parameters copied, dropped or under another parent than before. */
    private static boolean movesParameters(ParametricReactionRule<PureBigraph> rule, Map<NodeEntity<?>, NodeEntity<?>> forward) {
        PureBigraph redex = rule.getRedex(), reactum = rule.getReactum();
        InstantiationMap instantiation = rule.getInstantationMap();
        if (reactum.getSites().size() != redex.getSites().size()) return true;
        if (instantiation != null && !instantiation.isIdentity()) return true;
        Map<Integer, BigraphEntity<?>> parentBefore = new HashMap<>();
        for (BigraphEntity.SiteEntity site : redex.getSites()) parentBefore.put(site.getIndex(), redex.getParent(site));
        for (BigraphEntity.SiteEntity site : reactum.getSites()) {
            BigraphEntity<?> was = parentBefore.get(site.getIndex()), now = reactum.getParent(site);
            if (was instanceof NodeEntity<?> wasNode) {
                if (forward.get(wasNode) != now) return true;
            } else if (!(was instanceof BigraphEntity.RootEntity wasRoot && now instanceof BigraphEntity.RootEntity nowRoot
                    && wasRoot.getIndex() == nowRoot.getIndex())) {
                return true;
            }
        }
        return false;
    }

    /** Whether the nodes directly in some region differ (a node there deleted, new, or moved in or out). */
    private static boolean changesRegions(PureBigraph redex, PureBigraph reactum, Map<NodeEntity<?>, NodeEntity<?>> forward) {
        Side before = new Side(redex), after = new Side(reactum);
        if (redex.getRoots().size() != reactum.getRoots().size()) return true;
        for (BigraphEntity.RootEntity root : redex.getRoots()) {
            List<NodeEntity<?>> topLevelBefore = before.topLevelOf(root.getIndex());
            List<NodeEntity<?>> topLevelAfter = after.topLevelOf(root.getIndex());
            if (topLevelBefore.size() != topLevelAfter.size()) return true;
            for (NodeEntity<?> node : topLevelBefore) if (!topLevelAfter.contains(forward.get(node))) return true;
        }
        return false;
    }

    /** A guard node that must have exactly its children and can have children (not atomic). */
    private static boolean hasExactParent(PureBigraph guard) {
        for (NodeEntity<?> node : guard.getNodes()) {
            if (!hasSite(guard, node) && !ControlStatus.isAtomic(node.getControl())) return true;
        }
        return false;
    }

    /** No closed edges, no inner names, and every outer name with at most one point: any link fits. */
    private static boolean linkAgnostic(PureBigraph guard) {
        if (!guard.getEdges().isEmpty() || !guard.getInnerNames().isEmpty()) return false;
        for (BigraphEntity.OuterName outer : guard.getOuterNames()) if (guard.getPointsFromLink(outer).size() > 1) return false;
        return true;
    }

    private static boolean hasSite(PureBigraph bigraph, NodeEntity<?> node) {
        for (BigraphEntity<?> child : bigraph.getChildrenOf(node)) if (child instanceof BigraphEntity.SiteEntity) return true;
        return false;
    }

    private static String control(NodeEntity<?> node) {
        return node.getControl().getNamedType().stringValue();
    }
}
