package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.bigraphs.framework.core.BigraphMetaModelConstants;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphListener;
import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraphSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.coverage.CoverageRewriteRules.CompiledRule;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPModel;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coverage kept in sync with the structure bigraph instead of recomputed: one coverage bigraph per mode, rewritten to
 * its fixpoint once and from then on edited in place. A listener of {@link POPPBigraph} (next to the synchronizer's
 * view mirror) records the structural operations. The next session of a mode replays them on that mode's coverage
 * bigraph, resets the states that rested on what changed, rewrites the bigraph to its fixpoint again (with
 * {@link RewritingCoverageAnalyzer#rewriteToFixpoint}, seeded as below) and reads the
 * session off it. There is no state table besides the bigraph: states are the state cells, reasons the reason markers
 * the rewrites left, and what a state rests on is read off the bigraph.
 * <p>
 * <b>Sync.</b> The structure bigraph announces every node (container, domain and gate nodes, stubs) and every
 * relation, in order (see {@link POPPBigraphEvent}). The coverage bigraph keeps the structure bigraph's node names
 * through rewriting, so the operations are replayed by name: a node is added (a tree node with a fresh state cell,
 * {@code Unc}, or {@code Cov} and {@code CovIntrinsic} if its kind is intrinsic in the mode, see
 * {@link CoverageBigraph}), removed or moved. A relation edge is added between its stubs, or removed together with
 * them. Rewriting renames edges, so a relation is found through its
 * stubs, not by its edge name.
 * <p>
 * <b>Reset.</b> Coverage is the least fixpoint of monotone rules. A change that only adds (a relation, a node, an OR
 * child) can only raise states, so the old fixpoint is a valid start. A change that removes support (a relation, a
 * node, an AND child, AND instead of OR) can lower states, so every state that rested on the removed part goes back to
 * UNCOVERED first. What a state rests on is in the bigraph:
 * <ul>
 *   <li>a gate node rests on its slots (its children).</li>
 *   <li>a domain node whose reason marker is {@code CovDecomp}/{@code PartDecomp} rests on its topmost gate node.</li>
 *   <li>a domain node whose reason marker is {@code CovLink}/{@code PartLink} rests on the giver at the other end of
 *       the relation edge that marker is linked to.</li>
 * </ul>
 * Resetting a node removes its state cell and reason markers, adds {@code Unc} and resets, transitively, everything
 * that rests on it: its parent if that is a gate node or a domain node with a decomposition reason, and every domain
 * node whose reason marker sits on one of its relation edges. Derivations were established in order, so they form a
 * DAG, and this leaves only states that are still derivable. A node added, removed or moved below a tree node resets
 * that node by the first two rules. A relation removed resets the receivers whose reason marker is linked to its edge.
 * Intrinsic nodes (domain and gate nodes of an intrinsically covered kind) are never reset.
 * <p>
 * <b>Seed.</b> A reset or new node can only be upgraded by rules whose receiver has its kind (gate, lift and link
 * rules for that kind), and so can a node whose children changed. A new relation can first only be used by the link
 * rules of its type into its receiver's kind. Those rules are queued, and the rewriter queues whatever their upgrades
 * enable. Every other rule had no match at the old fixpoint, and nothing it needs has changed.
 * <p>
 * The coverage bigraph is wrapped for editing ({@link PureBigraphMutable} over the same EMF instance model) and wrapped
 * again, with empty caches, for matching: the framework caches a link's points and does not invalidate the cache on
 * link edits ({@code getPointsFromLink}), so links are read from the EMF model directly here.
 * <p>
 * The coverage analyzer of the POPP extension. Register it with {@link POPPBigraph#addListener}. The first session of a
 * mode, and a session on a different {@link POPPBigraph} or model (a reloaded file), evaluates from scratch. Needs a
 * jLibBig whose AgentMatcher keeps links ({@link RewritingCoverageAnalyzer#REWRITE_KEEPS_LINKS}).
 */
public final class IncrementalRewritingCoverageAnalyzer implements CoverageAnalyzer, POPPBigraphListener {

    /** A structural operation on the structure bigraph, by node names (a {@link POPPBigraphEvent} without entities). */
    private sealed interface Op {}
    /** {@code parent == null}: under the root. */
    private record Added(String node, String control, String parent) implements Op {}
    private record Removed(String node) implements Op {}
    private record Moved(String node, String newParent) implements Op {}
    private record RelationAdded(String edge, List<String> stubs) implements Op {}
    private record RelationRemoving(List<String> stubs) implements Op {}

    /** A mode's coverage bigraph at its fixpoint, how far into the operation log it is, and its session. */
    private record Synced(POPPModel model, POPPBigraph source, int cursor, PureBigraph coverage, CoverageSession session) {}

    private final CoverageInput input;
    private final RewritingCoverageAnalyzer engine;
    /** The operations not yet replayed by every mode. {@code log} and {@code logOffset} are guarded by {@code this}. */
    private final List<Op> log = new ArrayList<>();
    private int logOffset; // index of log.get(0) in the whole history
    /**
     * Per mode: its coverage bigraph and session, written only while holding the mode's lock. Sessions of different
     * modes share nothing but the log, so they run in parallel ({@link CoverageAnalyzer#sessions}).
     */
    private final Map<CoverageMode, Synced> synced = new ConcurrentHashMap<>();
    private final Map<CoverageMode, Object> modeLocks = new ConcurrentHashMap<>();

    public IncrementalRewritingCoverageAnalyzer(CoverageInput input) {
        this.input = input;
        this.engine = new RewritingCoverageAnalyzer(input);
    }

    /**
     * Thread-safe: sessions of the same mode run one after another, sessions of different modes in parallel (only
     * building a coverage bigraph from the shared structure bigraph is serialized, see {@link CoverageBigraph#build}). The
     * structure bigraph must not change during a session, which holds since edits and sessions are both handled on the
     * GLSP dispatch thread, which waits for the sessions.
     */
    @Override
    public CoverageSession session(CoverageMode mode) {
        synchronized (modeLocks.computeIfAbsent(mode, key -> new Object())) {
            return sessionOf(mode);
        }
    }

    private CoverageSession sessionOf(CoverageMode mode) {
        POPPBigraph source = input.bigraph();
        Synced previous = synced.get(mode);
        int head;
        boolean fromScratch;
        List<Op> ops = List.of();
        synchronized (this) {
            head = logOffset + log.size();
            // a different bigraph, or a different model on the same bigraph (a reloaded file): from scratch
            fromScratch = previous == null || previous.source() != source || previous.model() != input.model()
                    || previous.cursor() < logOffset;
            if (!fromScratch) ops = List.copyOf(log.subList(previous.cursor() - logOffset, log.size()));
        }
        RewritingCoverageAnalyzer.Fixpoint next;
        if (fromScratch) {
            next = engine.rewriteToFixpoint(mode, CoverageBigraph.build(source, mode), RewritingCoverageAnalyzer.LINK_RULES);
        } else if (previous.cursor() == head) {
            return previous.session();
        } else {
            Mirror mirror = new Mirror(previous.coverage(), mode);
            for (Op op : ops) mirror.apply(op);
            // start from the rules the edit can have enabled (see the class comment)
            next = engine.rewriteToFixpoint(mode, mirror.forMatching(), mirror::seeds);
        }
        synced.put(mode, new Synced(input.model(), source, head, next.bigraph(), next.session()));
        trimLog();
        return next.session();
    }

    /** Drops the operations every mode has replayed. A mode in its first session is not counted, see {@link #sessionOf}. */
    private synchronized void trimLog() {
        int min = synced.values().stream().mapToInt(Synced::cursor).min().orElse(logOffset + log.size());
        int drop = Math.min(min - logOffset, log.size());
        if (drop > 0) {
            log.subList(0, drop).clear();
            logOffset += drop;
        }
    }

    // Replaying the operations on a coverage bigraph, with the resets

    /** One sync of a mode's coverage bigraph: edits it in place, through a mutable wrapper of its EMF model. */
    private static final class Mirror {
        final PureBigraphMutable bigraph;
        final DynamicSignature signature;
        final CoverageMode mode;
        /** The structure nodes (container, tree nodes, stubs) by name: an index into {@link #bigraph}, not state. */
        final Map<String, BigraphEntity.NodeEntity<DynamicControl>> byName = new HashMap<>();
        /** Every node by its EMF object, to resolve the link points read from the EMF model. */
        final Map<EObject, BigraphEntity.NodeEntity<DynamicControl>> byObject = new HashMap<>();
        /** Kinds of reset, new or changed tree nodes: every rule with a receiver of that kind is seeded. */
        final Set<NodeKind> seeds = EnumSet.noneOf(NodeKind.class);
        /** Added relations, as (relation type, receiver kind): only the link rules for those are seeded. */
        final Set<String> seededLinks = new HashSet<>();

        Mirror(PureBigraph coverage, CoverageMode mode) {
            this.signature = coverage.getSignature();
            this.bigraph = PureBigraphBuilder.create(signature, coverage.getMetaModel(), coverage.getInstanceModel())
                    .createMutable();
            this.mode = mode;
            for (BigraphEntity.NodeEntity<DynamicControl> node : bigraph.getNodes()) {
                byObject.put(node.getInstance(), node);
                if (marker(node).isEmpty()) byName.put(node.getName(), node);
            }
        }

        boolean seeds(CompiledRule rule) {
            Optional<NodeKind> kind = RewritingCoverageAnalyzer.receiverKind(rule);
            if (kind.isEmpty()) return false;
            return seeds.contains(kind.get())
                    || rule.isLinkRule() && seededLinks.contains(rule.relation() + ":" + kind.get());
        }

        /** The edited bigraph, wrapped anew (empty caches) for matching and rewriting. */
        PureBigraph forMatching() {
            return PureBigraphBuilder.create(signature, bigraph.getMetaModel(), bigraph.getInstanceModel()).create();
        }

        void apply(Op op) {
            // If language standard is updated to 21 replace with switch casting
            if (op instanceof Added added) added(added);
            else if (op instanceof Removed removed) removed(removed);
            else if (op instanceof Moved moved) moved(moved);
            else if (op instanceof RelationAdded relationAdded) relationAdded(relationAdded);
            else if (op instanceof RelationRemoving relationRemoving) relationRemoving(relationRemoving);
            else throw new IllegalStateException("Unhandled operation: " + op);
        }

        private void added(Added op) {
            if (byName.containsKey(op.node())) return; // repeated announcement (the container)
            BigraphEntity<?> parent = op.parent() == null ? bigraph.getRoots().getFirst() : byName.get(op.parent());
            var node = add(parent, op.control(), op.node());
            byName.put(op.node(), node);
            if (!isTree(node)) return; // container, stubs: no state
            NodeKind kind = kind(node);
            seeds.add(kind);
            if (CoverageBigraph.isIntrinsic(POPPBigraphSignature.forNode(node), mode)) {
                cell(node, CoverageMarker.COV);
                cell(node, CoverageMarker.COV_INTRINSIC);
            } else {
                cell(node, CoverageMarker.UNC);
            }
            invalidate(parent); // a new gate node is a new slot of its parent
        }

        private void removed(Removed op) {
            var node = byName.get(op.node());
            if (node == null) return;
            BigraphEntity<?> parent = bigraph.getParent(node);
            if (isTree(node)) receiversOf(node).forEach(this::reset); // none in practice: relations are removed first
            // A domain node still below it was moved out without an announcement (the structure bigraph moves a node
            // to the container before rebuilding the chain it leaves). It is announced again at its final place.
            for (BigraphEntity<?> entity : new ArrayList<>(bigraph.getChildrenOf(node))) {
                if (entity instanceof BigraphEntity.NodeEntity<?> child && isTree(child)
                        && POPPBigraphSignature.isKindControl(POPPBigraphSignature.forNode(child))) {
                    bigraph.moveNode((BigraphEntity.NodeEntity<DynamicControl>) child, container());
                }
            }
            removeSubtree(node);
            invalidate(parent);
        }

        private void moved(Moved op) {
            var node = byName.get(op.node());
            var target = byName.get(op.newParent());
            BigraphEntity<?> old = bigraph.getParent(node);
            if (Objects.equals(old, target)) return;
            bigraph.moveNode(node, target);
            invalidate(old);
            invalidate(target);
        }

        private void relationAdded(RelationAdded op) {
            BigraphEntity.Edge edge = bigraph.addEdge(op.edge());
            for (String stub : op.stubs()) bigraph.connectNodeToLink(byName.get(stub), edge);
            // only the link rules of this relation type into the receiver's kind can match first, what they raise
            // enables the rest
            String type = op.edge().substring(0, Math.max(0, op.edge().indexOf(':')));
            receiverOf(op.edge()).map(byName::get).ifPresent(receiver -> seededLinks.add(type + ":" + kind(receiver)));
        }

        private void relationRemoving(RelationRemoving op) {
            List<BigraphEntity.NodeEntity<DynamicControl>> stubs =
                    op.stubs().stream().map(byName::get).filter(Objects::nonNull).toList();
            EObject link = stubs.stream().flatMap(stub -> ports(stub).stream()).map(Mirror::linkOf)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (link != null) {
                // markers on the edge: their owner's reason (reset the owner), or left over from a lower state (drop it)
                List<BigraphEntity.NodeEntity<DynamicControl>> receivers = new ArrayList<>();
                for (var markerNode : nodesOn(link)) {
                    Optional<CoverageMarker> marker = marker(markerNode);
                    if (marker.isEmpty()) continue;
                    var owner = parentNode(markerNode);
                    if (isReason(owner, marker.get())) receivers.add(owner);
                    else removeSubtree(markerNode);
                }
                receivers.forEach(this::reset);
            }
            stubs.forEach(this::removeSubtree);
            if (link != null) {
                bigraph.getEdges().stream().filter(edge -> edge.getInstance() == link).findFirst()
                        .ifPresent(bigraph::removeEdge);
            }
        }

        private BigraphEntity.NodeEntity<DynamicControl> container() {
            return byName.values().stream()
                    .filter(node -> POPPBigraphSignature.forNode(node) == POPPBigraphSignature.POPP)
                    .findFirst().orElseThrow();
        }

        /**
         * Something the state of {@code entity} may rest on changed (a child added, removed, moved or reset): reset it
         * if its state rests on its children, i.e. if it is a gate node, or a domain node with a decomposition reason.
         */
        private void invalidate(BigraphEntity<?> entity) {
            if (!(entity instanceof BigraphEntity.NodeEntity<?> raw) || !isTree(raw)) return;
            var node = (BigraphEntity.NodeEntity<DynamicControl>) raw;
            seeds.add(kind(node));
            if (POPPBigraphSignature.isDecompControl(POPPBigraphSignature.forNode(node))) {
                reset(node);
                return;
            }
            for (var markerNode : markers(node)) {
                CoverageMarker marker = marker(markerNode).orElseThrow();
                if ((marker == CoverageMarker.COV_DECOMP || marker == CoverageMarker.PART_DECOMP) && isReason(node, marker)) {
                    reset(node);
                    return;
                }
            }
        }

        /** Back to UNCOVERED, and everything that rests on {@code node} (see the class comment). */
        private void reset(BigraphEntity.NodeEntity<DynamicControl> node) {
            seeds.add(kind(node));
            if (CoverageBigraph.isIntrinsic(POPPBigraphSignature.forNode(node), mode)) return; // covered by definition
            if (state(node) == Coverage.UNCOVERED) return; // nothing rests on an uncovered node
            List<BigraphEntity.NodeEntity<DynamicControl>> receivers = receiversOf(node);
            markers(node).forEach(this::removeSubtree);
            cell(node, CoverageMarker.UNC);
            invalidate(bigraph.getParent(node));
            receivers.forEach(this::reset);
        }

        /** The domain nodes whose reason marker sits on a relation edge of {@code giver}, i.e. whose giver it is. */
        private List<BigraphEntity.NodeEntity<DynamicControl>> receiversOf(BigraphEntity.NodeEntity<DynamicControl> giver) {
            List<BigraphEntity.NodeEntity<DynamicControl>> out = new ArrayList<>();
            for (BigraphEntity<?> child : bigraph.getChildrenOf(giver)) {
                if (!(child instanceof BigraphEntity.NodeEntity<?> stub) || marker(stub).isPresent() || isTree(stub)) continue;
                for (EObject port : ports(stub)) {
                    EObject link = linkOf(port);
                    if (link == null) continue;
                    for (var markerNode : nodesOn(link)) {
                        Optional<CoverageMarker> marker = marker(markerNode);
                        if (marker.isEmpty()) continue;
                        var owner = parentNode(markerNode);
                        if (owner != giver && isReason(owner, marker.get())) out.add(owner);
                    }
                }
            }
            return out;
        }

        /** Whether {@code marker} is the reason marker of the current state of {@code owner}. */
        private boolean isReason(BigraphEntity.NodeEntity<DynamicControl> owner, CoverageMarker marker) {
            return marker.belongsTo(state(owner));
        }

        private Coverage state(BigraphEntity.NodeEntity<DynamicControl> node) {
            for (var markerNode : markers(node)) {
                Optional<Coverage> state = marker(markerNode).flatMap(CoverageMarker::state);
                if (state.isPresent()) return state.get();
            }
            throw new IllegalStateException("Tree node " + node.getName() + " has no state cell");
        }

        /** The marker children of a tree node: its state cell and reason markers. */
        private List<BigraphEntity.NodeEntity<DynamicControl>> markers(BigraphEntity.NodeEntity<DynamicControl> node) {
            List<BigraphEntity.NodeEntity<DynamicControl>> out = new ArrayList<>();
            for (BigraphEntity<?> child : bigraph.getChildrenOf(node)) {
                if (child instanceof BigraphEntity.NodeEntity<?> markerNode && marker(markerNode).isPresent()) {
                    out.add((BigraphEntity.NodeEntity<DynamicControl>) markerNode);
                }
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        private BigraphEntity.NodeEntity<DynamicControl> parentNode(BigraphEntity.NodeEntity<DynamicControl> node) {
            return (BigraphEntity.NodeEntity<DynamicControl>) bigraph.getParent(node);
        }

        private BigraphEntity.NodeEntity<DynamicControl> add(BigraphEntity<?> parent, String control, String name) {
            var node = bigraph.addNode(parent, signature.getControlByName(control), name);
            byObject.put(node.getInstance(), node);
            return node;
        }

        private void cell(BigraphEntity.NodeEntity<DynamicControl> owner, CoverageMarker marker) {
            add(owner, marker.controlName(), owner.getName() + "." + marker.controlName());
        }

        /** Removes {@code node} with everything below it, disconnecting their ports first. */
        private void removeSubtree(BigraphEntity.NodeEntity<DynamicControl> node) {
            for (BigraphEntity<?> entity : new ArrayList<>(bigraph.getChildrenOf(node))) {
                if (entity instanceof BigraphEntity.NodeEntity<?> child) removeSubtree((BigraphEntity.NodeEntity<DynamicControl>) child);
            }
            for (EObject port : ports(node)) {
                if (linkOf(port) != null) bigraph.disconnectPort(BigraphEntity.create(port, BigraphEntity.Port.class));
            }
            bigraph.removeNode(node);
            byObject.remove(node.getInstance());
            if (byName.get(node.getName()) == node) byName.remove(node.getName());
        }

        // Links, read from the EMF model (the framework's getPointsFromLink cache goes stale on link edits)

        @SuppressWarnings("unchecked")
        private static List<EObject> ports(BigraphEntity.NodeEntity<?> node) {
            EStructuralFeature feature = node.getInstance().eClass().getEStructuralFeature(BigraphMetaModelConstants.REFERENCE_PORT);
            return feature == null ? List.of() : List.copyOf((List<EObject>) node.getInstance().eGet(feature));
        }

        private static EObject linkOf(EObject point) {
            EStructuralFeature feature = point.eClass().getEStructuralFeature(BigraphMetaModelConstants.REFERENCE_LINK);
            return feature == null ? null : (EObject) point.eGet(feature);
        }

        /** The nodes with a port on {@code link}. */
        @SuppressWarnings("unchecked")
        private List<BigraphEntity.NodeEntity<DynamicControl>> nodesOn(EObject link) {
            EStructuralFeature feature = link.eClass().getEStructuralFeature(BigraphMetaModelConstants.REFERENCE_POINT);
            List<BigraphEntity.NodeEntity<DynamicControl>> out = new ArrayList<>();
            for (EObject point : List.copyOf((List<EObject>) link.eGet(feature))) {
                EStructuralFeature nodeRef = point.eClass().getEStructuralFeature(BigraphMetaModelConstants.REFERENCE_NODE);
                if (nodeRef == null) continue; // an inner name
                var node = byObject.get((EObject) point.eGet(nodeRef));
                if (node != null) out.add(node);
            }
            return out;
        }

        private static Optional<CoverageMarker> marker(BigraphEntity.NodeEntity<?> node) {
            return CoverageMarker.forNode(node);
        }

        private static boolean isTree(BigraphEntity.NodeEntity<?> node) {
            return marker(node).isEmpty() && POPPBigraphSignature.isTreeControl(POPPBigraphSignature.forNode(node));
        }

        /** The kind of a tree node: of the domain node, or of the tree a gate node belongs to. */
        private static NodeKind kind(BigraphEntity.NodeEntity<?> node) {
            POPPBigraphSignature control = POPPBigraphSignature.forNode(node);
            return POPPBigraphSignature.isKindControl(control) ? POPPBigraphSignature.toNodeKind(control)
                    : POPPBigraphSignature.gateKindOf(control).orElseThrow();
        }

        /** The receiver of a relation edge named {@code TYPE:source->target} (see {@link POPPBigraph#createRelation}). */
        private static Optional<String> receiverOf(String edgeName) {
            int colon = edgeName.indexOf(':'), arrow = edgeName.indexOf("->");
            if (colon < 0 || arrow < colon) return Optional.empty();
            RelationType type;
            try {
                type = RelationType.valueOf(edgeName.substring(0, colon));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            return switch (type.getCoveragePropagation()) {
                case FORWARD -> Optional.of(edgeName.substring(arrow + 2));
                case BACKWARD -> Optional.of(edgeName.substring(colon + 1, arrow));
                case NONE -> Optional.empty();
            };
        }
    }

    /**
     * Records a structural change as an {@link Op} by node names: the log must not hold on to the structure bigraph's
     * entities, which are detached when they are removed. Position changes do not matter for coverage.
     */
    @Override
    public synchronized void onBigraphEvent(POPPBigraphEvent event) {
        // If language standard is updated to 21 replace with switch casting
        if (event instanceof POPPBigraphEvent.NodeAdded nodeAdded) {
            String parentName = nodeAdded.parent() instanceof BigraphEntity.NodeEntity<?> parent ? parent.getName() : null;
            log.add(new Added(nodeAdded.node().getName(), nodeAdded.node().getControl().getNamedType().stringValue(), parentName));
        } else if (event instanceof POPPBigraphEvent.NodeRemoved nodeRemoved) {
            log.add(new Removed(nodeRemoved.node().getName()));
        } else if (event instanceof POPPBigraphEvent.NodeReparented reparented) {
            log.add(new Moved(reparented.child().getName(), reparented.newParent().getName()));
        } else if (event instanceof POPPBigraphEvent.RelationAdded relationAdded) {
            log.add(new RelationAdded(relationAdded.edge().getName(), names(relationAdded.stubs())));
        } else if (event instanceof POPPBigraphEvent.RelationRemoving relationRemoving) {
            log.add(new RelationRemoving(names(relationRemoving.stubs())));
        }
        // PositionChanged: does not matter for coverage
    }

    private static List<String> names(List<? extends BigraphEntity<?>> stubs) {
        return stubs.stream().map(stub -> ((BigraphEntity.NodeEntity<?>) stub).getName()).toList();
    }
}
