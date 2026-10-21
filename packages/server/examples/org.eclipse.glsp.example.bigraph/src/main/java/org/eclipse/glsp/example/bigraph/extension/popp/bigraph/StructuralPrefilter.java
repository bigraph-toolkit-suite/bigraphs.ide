package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Filter step of a filter-and-refine matcher (Lee et al. 2012): decides cheaply whether a redex can have a match in an
 * agent, so that the bigraph matcher (jLibBig's constraint model, the refine step) is only called when it can succeed.
 * Generic over any signature; it knows nothing about POPP.
 * <p>
 * The filter searches, by backtracking, an embedding of the redex's structure:
 * <ul>
 *   <li>place graph: every redex node goes to an agent node with the same control, injectively; children go to
 *       children; a redex node without a site needs exactly as many children as the agent node has (sites stand for
 *       the rest); the top nodes of one redex root go to siblings under one agent place (a root or a node);</li>
 *   <li>link graph: ports that share one redex link land on ports that share one agent link.</li>
 * </ul>
 * Every match the matcher can find is such an embedding, so the filter never rejects a redex that matches (it is a
 * necessary condition). Not modelled: inner names, closed redex edges (treated like outer names), parameters and the
 * match decomposition, so for other redex classes it may let a miss through. For the coverage rules (outer names
 * only, no inner names) it is exact on all models we tested: the matcher is called only for hits.
 */
public final class StructuralPrefilter {

    /** A place of a compiled bigraph: a root (control {@code null}) or a node with its children and port links. */
    private static final class Place {
        final String control;
        final int id;
        final List<Place> kids = new ArrayList<>();
        boolean site;
        int[] links = new int[0];

        Place(String control, int id) { this.control = control; this.id = id; }
    }

    /** A bigraph compiled for the filter: its roots, all places, and the node count. */
    public static final class Structure {
        private final List<Place> roots = new ArrayList<>();
        private final List<Place> places = new ArrayList<>();
        private int nodes;

        public int nodeCount() { return nodes; }
    }

    private StructuralPrefilter() {}

    /** Compiles a redex or an agent; for an agent, do it once per agent and reuse it for every redex. */
    public static Structure compile(PureBigraph b) {
        Structure s = new Structure();
        Map<Object, Integer> linkIds = new IdentityHashMap<>();
        List<BigraphEntity.RootEntity> roots = new ArrayList<>(b.getRoots());
        roots.sort(Comparator.comparingInt(BigraphEntity.RootEntity::getIndex));
        for (BigraphEntity.RootEntity r : roots) {
            Place p = new Place(null, -1);
            s.roots.add(p);
            s.places.add(p);
            build(b, r, p, s, linkIds);
        }
        return s;
    }

    private static void build(PureBigraph b, BigraphEntity<?> entity, Place place, Structure s, Map<Object, Integer> linkIds) {
        for (BigraphEntity<?> c : b.getChildrenOf(entity)) {
            if (c instanceof BigraphEntity.SiteEntity) {
                place.site = true;
                continue;
            }
            if (!(c instanceof BigraphEntity.NodeEntity<?> node)) continue;
            Place k = new Place(node.getControl().getNamedType().stringValue(), s.nodes++);
            List<BigraphEntity.Port> ports = new ArrayList<>(b.getPorts(node));
            ports.sort(Comparator.comparingInt(BigraphEntity.Port::getIndex));
            k.links = new int[ports.size()];
            for (int i = 0; i < ports.size(); i++) {
                Object link = b.getLinkOfPoint(ports.get(i));
                k.links[i] = link == null ? -1 : linkIds.computeIfAbsent(link, x -> linkIds.size());
            }
            place.kids.add(k);
            s.places.add(k);
            build(b, node, k, s, linkIds);
        }
    }

    /** Whether {@code redex} can be embedded into {@code agent} (see the class comment). */
    public static boolean mayMatch(Structure redex, Structure agent) {
        if (redex.nodes > agent.nodes) return false;
        return new Search(agent).roots(redex.roots, 0);
    }

    private static final class Search {
        private final Structure agent;
        private final boolean[] used;
        private final Map<Integer, Integer> linkImage = new HashMap<>();

        Search(Structure agent) {
            this.agent = agent;
            this.used = new boolean[agent.nodes];
        }

        boolean roots(List<Place> roots, int i) {
            if (i == roots.size()) return true;
            Place r = roots.get(i);
            for (Place place : agent.places) {
                if (place.kids.size() < r.kids.size()) continue;
                if (children(r.kids, 0, place.kids, false, () -> roots(roots, i + 1))) return true;
            }
            return false;
        }

        /** Maps redex children {@code rs[i..]} injectively to unused agent children; {@code exact}: all of them. */
        boolean children(List<Place> rs, int i, List<Place> as, boolean exact, BooleanSupplier then) {
            if (i == rs.size()) {
                if (exact) for (Place a : as) if (!used[a.id]) return false;
                return then.getAsBoolean();
            }
            Place r = rs.get(i);
            for (Place a : as) {
                if (used[a.id] || !a.control.equals(r.control)) continue;
                if (node(r, a, () -> children(rs, i + 1, as, exact, then))) return true;
            }
            return false;
        }

        boolean node(Place r, Place a, BooleanSupplier then) {
            if (r.site ? a.kids.size() < r.kids.size() : a.kids.size() != r.kids.size()) return false;
            if (r.links.length != a.links.length) return false;
            List<Integer> bound = new ArrayList<>(r.links.length);
            boolean consistent = true;
            for (int p = 0; p < r.links.length && consistent; p++) {
                Integer image = linkImage.get(r.links[p]);
                if (image == null) {
                    linkImage.put(r.links[p], a.links[p]);
                    bound.add(r.links[p]);
                } else {
                    consistent = image == a.links[p];
                }
            }
            boolean found = false;
            if (consistent) {
                used[a.id] = true;
                found = children(r.kids, 0, a.kids, !r.site, then);
                used[a.id] = false;
            }
            for (int l : bound) linkImage.remove(l);
            return found;
        }
    }
}
