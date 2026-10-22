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
import java.util.function.Predicate;

/**
 * Coverage kept in sync with the structure bigraph instead of recomputed: one coverage bigraph per mode, rewritten to
 * its fixpoint once and from then on edited in place. A listener of {@link POPPBigraph} (next to the synchronizer's
 * view mirror) records the structural operations; the next session of a mode replays them on that mode's coverage
 * bigraph, resets the states that rested on what changed, rewrites the bigraph to its fixpoint again (with
 * {@link ScheduledRewritingCoverageAnalyzer#rewriteToFixpoint}, scheduled by the engine's options) and reads the
 * session off it. There is no state table besides the bigraph: states are the state cells, reasons the reason markers
 * the rewrites left, and what a state rests on is read off the bigraph.
 * <p>
 * <b>Sync.</b> The structure bigraph announces every node (container, domain and gate nodes, stubs) and every
 * relation, in order (see {@link POPPBigraphEvent}). The coverage bigraph keeps the structure bigraph's node names
 * through rewriting, so the operations are replayed by name: a node is added (a tree node with a fresh state cell,
 * {@code Unc}, or {@code Cov} and {@code CovIntrinsic} if its kind is intrinsic in the mode, see
 * {@link CoverageBigraph}), removed or moved; a relation edge is
 * added between its stubs, or removed together with them. Rewriting renames edges, so a relation is found through its
 * stubs, not by its edge name.
 * <p>
 * <b>Reset.</b> Coverage is the least fixpoint of monotone rules. A change that only adds (a relation, a node, an OR
 * child) can only raise states, so the old fixpoint is a valid start. A change that removes support (a relation, a
 * node, an AND child, AND instead of OR) can lower states, so every state that rested on the removed part goes back to
 * UNCOVERED first. What a state rests on is in the bigraph:
 * <ul>
 *   <li>a gate node rests on its slots (its children);</li>
 *   <li>a domain node whose reason marker is {@code CovDecomp}/{@code PartDecomp} rests on its topmost gate node;</li>
 *   <li>a domain node whose reason marker is {@code CovLink}/{@code PartLink} rests on the giver at the other end of
 *       the relation edge that marker is linked to.</li>
 * </ul>
 * Resetting a node removes its state cell and reason markers, adds {@code Unc} and resets, transitively, everything
 * that rests on it: its parent if that is a gate node or a domain node with a decomposition reason, and every domain
 * node whose reason marker sits on one of its relation edges. Derivations were established in order, so they form a
 * DAG, and this leaves only states that are still derivable. A node added, removed or moved below a tree node resets
 * that node by the first two rules; a relation removed resets the receivers whose reason marker is linked to its edge.
 * Intrinsic nodes (domain and gate nodes of an intrinsically covered kind) are never reset.
 * <p>
 * <b>Seed.</b> A reset or new node can only be upgraded by rules whose receiver has its kind (gate, lift and link
 * rules for that kind); a node whose children changed likewise; a new relation first only by the link rules of its
 * type into its receiver's kind. Those rules are queued; the worklist queues whatever their upgrades enable. Every other rule had no
 * match at the old fixpoint, and nothing it needs has changed.
 * <p>
 * The coverage bigraph is wrapped for editing ({@link PureBigraphMutable} over the same EMF instance model) and wrapped
 * again, with empty caches, for matching: the framework caches a link's points and does not invalidate the cache on
 * link edits ({@code getPointsFromLink}), so links are read from the EMF model directly here.
 * <p>
 * The coverage analyzer of the POPP extension. Register it with {@link POPPBigraph#addListener}; the first session of a
 * mode, and a session on a different {@link POPPBigraph} or model (a reloaded file), evaluates from scratch. Needs a jLibBig whose AgentMatcher keeps links
 * ({@link ScheduledRewritingCoverageAnalyzer#REWRITE_KEEPS_LINKS}).
 */
public final class IncrementalCoverageAnalyzer implements CoverageAnalyzer, POPPBigraphListener {

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
    private final ScheduledRewritingCoverageAnalyzer engine;
    /** The operations not yet replayed by every mode. {@code log} and {@code logOffset} are guarded by {@code this}. */
    private final List<Op> log = new ArrayList<>();
    private int logOffset; // index of log.get(0) in the whole history
    /**
     * Per mode: its coverage bigraph and session, written only while holding the mode's lock. Sessions of different
     * modes share nothing but the log, so they run in parallel ({@link CoverageAnalyzer#sessions}).
     */
    private final Map<CoverageMode, Synced> synced = new ConcurrentHashMap<>();
    private final Map<CoverageMode, Object> modeLocks = new ConcurrentHashMap<>();
    private volatile Stats lastStats;

    /**
     * What the last finished session did (of any mode, when sessions run in parallel): from scratch or incremental, operations replayed, states reset, rules seeded, and the
     * time to replay the operations and reset (the rewriting is in {@link #lastEngineStats()}).
     */
    public record Stats(boolean incremental, int operations, int reset, int seeded, long syncNanos) {}

    /** {@code options}: the engine's schedule. */
    public IncrementalCoverageAnalyzer(CoverageInput input, ScheduledRewritingCoverageAnalyzer.Options options) {
        this.input = input;
        this.engine = new ScheduledRewritingCoverageAnalyzer(input, options);
    }

    /** With the default schedule ({@link ScheduledRewritingCoverageAnalyzer.Options#DEFAULT}). */
    public IncrementalCoverageAnalyzer(CoverageInput input) {
        this(input, ScheduledRewritingCoverageAnalyzer.Options.DEFAULT);
    }

    public Stats lastStats() { return lastStats; }

    /** The engine's counters of the last session. */
    public RewritingStats lastEngineStats() { return engine.lastStats(); }

    /** Record a {@link RewritingTrace} of the engine in every following session (slower; not for benchmarks). */
    public void setTracing(boolean on) { engine.setTracing(on); }

    /** The engine's trace of the most recent session that evaluated something, if tracing was on. */
    public RewritingTrace lastTrace() { return engine.lastTrace(); }

    /** The coverage bigraph of {@code mode} at its last fixpoint (for inspection and tests). */
    public Optional<PureBigraph> coverageBigraph(CoverageMode mode) {
        return Optional.ofNullable(synced.get(mode)).map(Synced::coverage);
    }

    /**
     * Thread-safe: sessions of the same mode run one after another, sessions of different modes in parallel. The
     * structure bigraph must not change during a session, which holds since edits and sessions are both handled on the
     * GLSP dispatch thread, which waits for the sessions.
     */
    @Override
    public CoverageSession session(CoverageMode mode) {
        synchronized (modeLocks.computeIfAbsent(mode, m -> new Object())) {
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
        ScheduledRewritingCoverageAnalyzer.Fixpoint next;
        if (fromScratch) {
            next = engine.rewriteToFixpoint(mode, CoverageBigraph.build(source, mode), engine.options().seed());
            lastStats = new Stats(false, 0, 0, -1, 0);
        } else if (previous.cursor() == head) {
            lastStats = new Stats(true, 0, 0, 0, 0);
            return previous.session();
        } else {
            long t0 = System.nanoTime();
            Mirror mirror = new Mirror(previous.coverage(), mode);
            for (Op op : ops) mirror.apply(op);
            PureBigraph synced = mirror.forMatching();
            long syncNanos = System.nanoTime() - t0;
            // a seeded schedule starts from the rules the edit can have enabled; an unseeded one queues every rule,
            // as from scratch (for comparing the seed; without a worklist every round tries every rule anyway)
            Predicate<CompiledRule> seed = engine.options().seed() == ScheduledRewritingCoverageAnalyzer.Options.ALL_RULES
                    ? ScheduledRewritingCoverageAnalyzer.Options.ALL_RULES : mirror::seeds;
            int seeded = (int) ScheduledRewritingCoverageAnalyzer.rules().stream().filter(seed).count();
            lastStats = new Stats(true, ops.size(), mirror.reset, seeded, syncNanos);
            next = engine.rewriteToFixpoint(mode, synced, seed);
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
        final PureBigraphMutable g;
        final DynamicSignature sig;
        final CoverageMode mode;
        /** The structure nodes (container, tree nodes, stubs) by name: an index into {@link #g}, not state. */
        final Map<String, BigraphEntity.NodeEntity<DynamicControl>> byName = new HashMap<>();
        /** Every node by its EMF object, to resolve the link points read from the EMF model. */
        final Map<EObject, BigraphEntity.NodeEntity<DynamicControl>> byObject = new HashMap<>();
        /** Kinds of reset, new or changed tree nodes: every rule with a receiver of that kind is seeded. */
        final Set<NodeKind> seeds = EnumSet.noneOf(NodeKind.class);
        /** Added relations, as (relation type, receiver kind): only the link rules for those are seeded. */
        final Set<String> seededLinks = new HashSet<>();
        int reset;

        Mirror(PureBigraph coverage, CoverageMode mode) {
            this.sig = coverage.getSignature();
            this.g = PureBigraphBuilder.create(sig, coverage.getMetaModel(), coverage.getInstanceModel()).createMutable();
            this.mode = mode;
            for (BigraphEntity.NodeEntity<DynamicControl> n : g.getNodes()) {
                byObject.put(n.getInstance(), n);
                if (marker(n).isEmpty()) byName.put(n.getName(), n);
            }
        }

        boolean seeds(CompiledRule r) {
            Optional<NodeKind> kind = ScheduledRewritingCoverageAnalyzer.receiverKind(r);
            if (kind.isEmpty()) return false;
            return seeds.contains(kind.get())
                    || r.isLinkRule() && seededLinks.contains(r.relation() + ":" + kind.get());
        }

        /** The edited bigraph, wrapped anew (empty caches) for matching and rewriting. */
        PureBigraph forMatching() {
            return PureBigraphBuilder.create(sig, g.getMetaModel(), g.getInstanceModel()).create();
        }

        void apply(Op op) {
            // If language standard is updated to 21 replace with switch casting
            if (op instanceof Added a) added(a);
            else if (op instanceof Removed r) removed(r);
            else if (op instanceof Moved m) moved(m);
            else if (op instanceof RelationAdded r) relationAdded(r);
            else if (op instanceof RelationRemoving r) relationRemoving(r);
            else throw new IllegalStateException("Unhandled operation: " + op);
        }

        private void added(Added a) {
            if (byName.containsKey(a.node())) return; // repeated announcement (the container)
            BigraphEntity<?> parent = a.parent() == null ? g.getRoots().getFirst() : byName.get(a.parent());
            var n = add(parent, a.control(), a.node());
            byName.put(a.node(), n);
            if (!isTree(n)) return; // container, stubs: no state
            NodeKind kind = kind(n);
            seeds.add(kind);
            if (CoverageBigraph.isIntrinsic(POPPBigraphSignature.forNode(n), mode)) {
                cell(n, CoverageMarker.COV);
                cell(n, CoverageMarker.COV_INTRINSIC);
            } else {
                cell(n, CoverageMarker.UNC);
            }
            invalidate(parent); // a new gate node is a new slot of its parent
        }

        private void removed(Removed r) {
            var n = byName.get(r.node());
            if (n == null) return;
            BigraphEntity<?> parent = g.getParent(n);
            if (isTree(n)) receiversOf(n).forEach(this::reset); // none in practice: relations are removed first
            // A domain node still below it was moved out without an announcement (the structure bigraph moves a node
            // to the container before rebuilding the chain it leaves); it is announced again at its final place.
            for (BigraphEntity<?> c : new ArrayList<>(g.getChildrenOf(n))) {
                if (c instanceof BigraphEntity.NodeEntity<?> child && isTree(child)
                        && POPPBigraphSignature.isKindControl(POPPBigraphSignature.forNode(child))) {
                    g.moveNode((BigraphEntity.NodeEntity<DynamicControl>) child, container());
                }
            }
            removeSubtree(n);
            invalidate(parent);
        }

        private void moved(Moved m) {
            var n = byName.get(m.node());
            var target = byName.get(m.newParent());
            BigraphEntity<?> old = g.getParent(n);
            if (Objects.equals(old, target)) return;
            g.moveNode(n, target);
            invalidate(old);
            invalidate(target);
        }

        private void relationAdded(RelationAdded r) {
            BigraphEntity.Edge edge = g.addEdge(r.edge());
            for (String stub : r.stubs()) g.connectNodeToLink(byName.get(stub), edge);
            // only the link rules of this relation type into the receiver's kind can match first; what they raise
            // enables the rest
            String type = r.edge().substring(0, Math.max(0, r.edge().indexOf(':')));
            receiverOf(r.edge()).map(byName::get).ifPresent(n -> seededLinks.add(type + ":" + kind(n)));
        }

        private void relationRemoving(RelationRemoving r) {
            List<BigraphEntity.NodeEntity<DynamicControl>> stubs = r.stubs().stream().map(byName::get).filter(Objects::nonNull).toList();
            EObject link = stubs.stream().flatMap(s -> ports(s).stream()).map(Mirror::linkOf).filter(Objects::nonNull)
                    .findFirst().orElse(null);
            if (link != null) {
                // markers on the edge: their owner's reason (reset the owner), or left over from a lower state (drop it)
                List<BigraphEntity.NodeEntity<DynamicControl>> receivers = new ArrayList<>();
                for (var m : nodesOn(link)) {
                    Optional<CoverageMarker> marker = marker(m);
                    if (marker.isEmpty()) continue;
                    var owner = parentNode(m);
                    if (isReason(owner, marker.get())) receivers.add(owner);
                    else removeSubtree(m);
                }
                receivers.forEach(this::reset);
            }
            stubs.forEach(this::removeSubtree);
            if (link != null) {
                g.getEdges().stream().filter(e -> e.getInstance() == link).findFirst().ifPresent(g::removeEdge);
            }
        }

        private BigraphEntity.NodeEntity<DynamicControl> container() {
            return byName.values().stream().filter(n -> POPPBigraphSignature.forNode(n) == POPPBigraphSignature.POPP)
                    .findFirst().orElseThrow();
        }

        /**
         * Something {@code p}'s state may rest on changed (a child added, removed, moved or reset): reset {@code p}
         * if its state rests on its children, i.e. if it is a gate node, or a domain node with a decomposition reason.
         */
        private void invalidate(BigraphEntity<?> p) {
            if (!(p instanceof BigraphEntity.NodeEntity<?> raw) || !isTree(raw)) return;
            var n = (BigraphEntity.NodeEntity<DynamicControl>) raw;
            seeds.add(kind(n));
            if (POPPBigraphSignature.isDecompControl(POPPBigraphSignature.forNode(n))) {
                reset(n);
                return;
            }
            for (var m : markers(n)) {
                CoverageMarker k = marker(m).orElseThrow();
                if ((k == CoverageMarker.COV_DECOMP || k == CoverageMarker.PART_DECOMP) && isReason(n, k)) {
                    reset(n);
                    return;
                }
            }
        }

        /** Back to UNCOVERED, and everything that rests on {@code n} (see the class comment). */
        private void reset(BigraphEntity.NodeEntity<DynamicControl> n) {
            seeds.add(kind(n));
            if (CoverageBigraph.isIntrinsic(POPPBigraphSignature.forNode(n), mode)) return; // covered by definition
            if (state(n) == Coverage.UNCOVERED) return; // nothing rests on an uncovered node
            List<BigraphEntity.NodeEntity<DynamicControl>> receivers = receiversOf(n);
            markers(n).forEach(this::removeSubtree);
            cell(n, CoverageMarker.UNC);
            reset++;
            invalidate(g.getParent(n));
            receivers.forEach(this::reset);
        }

        /** The domain nodes whose reason marker sits on a relation edge of {@code n}, i.e. whose giver {@code n} is. */
        private List<BigraphEntity.NodeEntity<DynamicControl>> receiversOf(BigraphEntity.NodeEntity<DynamicControl> n) {
            List<BigraphEntity.NodeEntity<DynamicControl>> out = new ArrayList<>();
            for (BigraphEntity<?> c : g.getChildrenOf(n)) {
                if (!(c instanceof BigraphEntity.NodeEntity<?> stub) || marker(stub).isPresent() || isTree(stub)) continue;
                for (EObject port : ports(stub)) {
                    EObject link = linkOf(port);
                    if (link == null) continue;
                    for (var m : nodesOn(link)) {
                        Optional<CoverageMarker> marker = marker(m);
                        if (marker.isEmpty()) continue;
                        var owner = parentNode(m);
                        if (owner != n && isReason(owner, marker.get())) out.add(owner);
                    }
                }
            }
            return out;
        }

        /** Whether {@code m} is the reason marker of {@code owner}'s current state. */
        private boolean isReason(BigraphEntity.NodeEntity<DynamicControl> owner, CoverageMarker m) {
            return m.belongsTo(state(owner));
        }

        private Coverage state(BigraphEntity.NodeEntity<DynamicControl> n) {
            for (var m : markers(n)) {
                Optional<Coverage> s = marker(m).flatMap(CoverageMarker::state);
                if (s.isPresent()) return s.get();
            }
            throw new IllegalStateException("Tree node " + n.getName() + " has no state cell");
        }

        /** The marker children of a tree node: its state cell and reason markers. */
        private List<BigraphEntity.NodeEntity<DynamicControl>> markers(BigraphEntity.NodeEntity<DynamicControl> n) {
            List<BigraphEntity.NodeEntity<DynamicControl>> out = new ArrayList<>();
            for (BigraphEntity<?> c : g.getChildrenOf(n)) {
                if (c instanceof BigraphEntity.NodeEntity<?> m && marker(m).isPresent()) {
                    out.add((BigraphEntity.NodeEntity<DynamicControl>) m);
                }
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        private BigraphEntity.NodeEntity<DynamicControl> parentNode(BigraphEntity.NodeEntity<DynamicControl> n) {
            return (BigraphEntity.NodeEntity<DynamicControl>) g.getParent(n);
        }

        private BigraphEntity.NodeEntity<DynamicControl> add(BigraphEntity<?> parent, String control, String name) {
            var n = g.addNode(parent, sig.getControlByName(control), name);
            byObject.put(n.getInstance(), n);
            return n;
        }

        private void cell(BigraphEntity.NodeEntity<DynamicControl> owner, CoverageMarker m) {
            add(owner, m.controlName(), owner.getName() + "." + m.controlName());
        }

        /** Removes {@code n} with everything below it, disconnecting their ports first. */
        private void removeSubtree(BigraphEntity.NodeEntity<DynamicControl> n) {
            for (BigraphEntity<?> c : new ArrayList<>(g.getChildrenOf(n))) {
                if (c instanceof BigraphEntity.NodeEntity<?> child) removeSubtree((BigraphEntity.NodeEntity<DynamicControl>) child);
            }
            for (EObject port : ports(n)) {
                if (linkOf(port) != null) g.disconnectPort(BigraphEntity.create(port, BigraphEntity.Port.class));
            }
            g.removeNode(n);
            byObject.remove(n.getInstance());
            if (byName.get(n.getName()) == n) byName.remove(n.getName());
        }

        // Links, read from the EMF model (the framework's getPointsFromLink cache goes stale on link edits)

        @SuppressWarnings("unchecked")
        private static List<EObject> ports(BigraphEntity.NodeEntity<?> n) {
            EStructuralFeature f = n.getInstance().eClass().getEStructuralFeature(BigraphMetaModelConstants.REFERENCE_PORT);
            return f == null ? List.of() : List.copyOf((List<EObject>) n.getInstance().eGet(f));
        }

        private static EObject linkOf(EObject point) {
            EStructuralFeature f = point.eClass().getEStructuralFeature(BigraphMetaModelConstants.REFERENCE_LINK);
            return f == null ? null : (EObject) point.eGet(f);
        }

        /** The nodes with a port on {@code link}. */
        @SuppressWarnings("unchecked")
        private List<BigraphEntity.NodeEntity<DynamicControl>> nodesOn(EObject link) {
            EStructuralFeature f = link.eClass().getEStructuralFeature(BigraphMetaModelConstants.REFERENCE_POINT);
            List<BigraphEntity.NodeEntity<DynamicControl>> out = new ArrayList<>();
            for (EObject point : List.copyOf((List<EObject>) link.eGet(f))) {
                EStructuralFeature nodeRef = point.eClass().getEStructuralFeature(BigraphMetaModelConstants.REFERENCE_NODE);
                if (nodeRef == null) continue; // an inner name
                var node = byObject.get((EObject) point.eGet(nodeRef));
                if (node != null) out.add(node);
            }
            return out;
        }

        private static Optional<CoverageMarker> marker(BigraphEntity.NodeEntity<?> n) {
            return CoverageMarker.forNode(n);
        }

        private static boolean isTree(BigraphEntity.NodeEntity<?> n) {
            return marker(n).isEmpty() && POPPBigraphSignature.isTreeControl(POPPBigraphSignature.forNode(n));
        }

        /** The kind of a tree node: of the domain node, or of the tree a gate node belongs to. */
        private static NodeKind kind(BigraphEntity.NodeEntity<?> n) {
            POPPBigraphSignature c = POPPBigraphSignature.forNode(n);
            return POPPBigraphSignature.isKindControl(c) ? POPPBigraphSignature.toNodeKind(c)
                    : POPPBigraphSignature.gateKindOf(c).orElseThrow();
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
        if (event instanceof POPPBigraphEvent.NodeAdded e) {
            String parentName = e.parent() instanceof BigraphEntity.NodeEntity<?> p ? p.getName() : null;
            log.add(new Added(e.node().getName(), e.node().getControl().getNamedType().stringValue(), parentName));
        } else if (event instanceof POPPBigraphEvent.NodeRemoved e) {
            log.add(new Removed(e.node().getName()));
        } else if (event instanceof POPPBigraphEvent.NodeReparented e) {
            log.add(new Moved(e.child().getName(), e.newParent().getName()));
        } else if (event instanceof POPPBigraphEvent.RelationAdded e) {
            log.add(new RelationAdded(e.edge().getName(), names(e.stubs())));
        } else if (event instanceof POPPBigraphEvent.RelationRemoving e) {
            log.add(new RelationRemoving(names(e.stubs())));
        }
        // PositionChanged: does not matter for coverage
    }

    private static List<String> names(List<? extends BigraphEntity<?>> stubs) {
        return stubs.stream().map(s -> ((BigraphEntity.NodeEntity<?>) s).getName()).toList();
    }
}
