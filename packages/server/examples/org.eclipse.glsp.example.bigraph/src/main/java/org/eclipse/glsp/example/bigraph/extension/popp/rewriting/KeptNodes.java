package org.eclipse.glsp.example.bigraph.extension.popp.rewriting;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.reactivesystem.InstantiationMap;
import org.bigraphs.framework.core.reactivesystem.ParametricReactionRule;
import org.bigraphs.framework.core.reactivesystem.TrackingMap;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The nodes a reaction rule keeps: a reactum node keeps the redex node its tracking map names, if both have the same
 * control (each redex node is kept at most once). Every other reactum node is new, every other redex node deleted.
 * Shared by the two analyses that read a rule's structure, {@link OverlapProduceUseAnalyzer} and {@link GuardEffects}.
 */
final class KeptNodes {

    /** Redex node to the reactum node that keeps it. */
    private final Map<NodeEntity<?>, NodeEntity<?>> reactumOf;
    /** Reactum node to the redex node it keeps. */
    private final Map<NodeEntity<?>, NodeEntity<?>> redexOf;

    private KeptNodes(Map<NodeEntity<?>, NodeEntity<?>> reactumOf, Map<NodeEntity<?>, NodeEntity<?>> redexOf) {
        this.reactumOf = Collections.unmodifiableMap(reactumOf);
        this.redexOf = Collections.unmodifiableMap(redexOf);
    }

    static KeptNodes of(ParametricReactionRule<PureBigraph> rule) {
        PureBigraph redex = rule.getRedex(), reactum = rule.getReactum();
        Map<String, NodeEntity<?>> redexByName = new HashMap<>();
        for (NodeEntity<?> node : redex.getNodes()) redexByName.put(node.getName(), node);
        Map<NodeEntity<?>, NodeEntity<?>> reactumOf = new IdentityHashMap<>();
        Map<NodeEntity<?>, NodeEntity<?>> redexOf = new IdentityHashMap<>();
        TrackingMap tracking = rule.getTrackingMap();
        if (tracking != null) {
            for (NodeEntity<?> node : reactum.getNodes()) {
                String trackedName = tracking.get(node.getName());
                NodeEntity<?> redexNode = trackedName == null || trackedName.isEmpty() ? null : redexByName.get(trackedName);
                if (redexNode != null && control(redexNode).equals(control(node)) && !reactumOf.containsKey(redexNode)) {
                    reactumOf.put(redexNode, node);
                    redexOf.put(node, redexNode);
                }
            }
        }
        return new KeptNodes(reactumOf, redexOf);
    }

    /** Redex node to the reactum node that keeps it (absent: deleted). */
    Map<NodeEntity<?>, NodeEntity<?>> reactumOf() {
        return reactumOf;
    }

    /** Reactum node to the redex node it keeps (absent: new). */
    Map<NodeEntity<?>, NodeEntity<?>> redexOf() {
        return redexOf;
    }

    /**
     * Whether a parent is the same before and after the rewrite: the reactum node that keeps the redex node, or a root
     * with the same index.
     */
    static boolean sameParent(BigraphEntity<?> before, BigraphEntity<?> after, Map<NodeEntity<?>, NodeEntity<?>> reactumOf) {
        if (before instanceof NodeEntity<?> beforeNode) return after == reactumOf.get(beforeNode);
        if (before instanceof BigraphEntity.RootEntity rootBefore && after instanceof BigraphEntity.RootEntity rootAfter) {
            return rootBefore.getIndex() == rootAfter.getIndex();
        }
        return false;
    }

    /**
     * Whether the rule moves its parameters in the place graph: copies or drops one (a different number of sites, or
     * an instantiation map that is not the identity), or puts one under a different parent than its site.
     */
    boolean movesSites(ParametricReactionRule<PureBigraph> rule) {
        PureBigraph redex = rule.getRedex(), reactum = rule.getReactum();
        InstantiationMap instantiation = rule.getInstantationMap();
        if (reactum.getSites().size() != redex.getSites().size()) return true;
        if (instantiation != null && !instantiation.isIdentity()) return true;
        Map<Integer, BigraphEntity<?>> parentBefore = new HashMap<>();
        for (BigraphEntity.SiteEntity site : redex.getSites()) parentBefore.put(site.getIndex(), redex.getParent(site));
        for (BigraphEntity.SiteEntity site : reactum.getSites()) {
            if (!sameParent(parentBefore.get(site.getIndex()), reactum.getParent(site), reactumOf)) return true;
        }
        return false;
    }

    /** The name of a node's control. */
    static String control(NodeEntity<?> node) {
        return node.getControl().getNamedType().stringValue();
    }

    /** Whether {@code node} has a site among its children, i.e. may have more children than the pattern lists. */
    static boolean hasSite(PureBigraph bigraph, NodeEntity<?> node) {
        for (BigraphEntity<?> child : bigraph.getChildrenOf(node)) if (child instanceof BigraphEntity.SiteEntity) return true;
        return false;
    }
}
