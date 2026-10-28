package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The bigraph encoding of a POPP model, kept in sync with it through explicit calls and announced to listeners.
 *
 * <p>Encoding: every domain node is a node whose control is its kind ({@code Problem}, {@code Goal}, ...). An AND/OR
 * decomposition with n children becomes a decomposition chain of n-1 binary gate nodes ({@code GoalAnd}, {@code ProblemOr}, ...) under the
 * decomposed node (one empty gate node without children, one gate node for a single child). The gate node
 * directly under a domain node is the first one. A relation is an edge between two arity-1 stub nodes, one under each
 * endpoint. Every node is addressable by its id ({@code nodesById}): domain nodes by their model id, gate nodes by
 * {@code <id>_DECOMP[_i]}, stubs by {@code <STUB_CONSTANT>:<source id>-><target id>}. The edge is named
 * {@code <TYPE>:<source id>-><target id>} (so ids must not contain {@code ->}).</p>
 */
@SuppressWarnings("unchecked")
public class POPPBigraph {
    private final static Logger logger = LoggerFactory.getLogger(POPPBigraph.class);

    private final PureBigraphMutable bigraph;
    private final DynamicSignature signature;
    private final List<POPPBigraphListener> listeners = new CopyOnWriteArrayList<>();

    private final Map<String, BigraphEntity.NodeEntity<DynamicControl>> nodesById = new HashMap<>();
    private final Map<String, List<String>> decompGateIds = new HashMap<>();

    public POPPBigraph(PureBigraphMutable bigraph, DynamicSignature signature) {
        this.bigraph = bigraph;
        this.signature = signature;
        hydrate();
    }

    public POPPBigraph(PureBigraphMutable bigraph) {
        this(bigraph, bigraph.getSignature());
    }

    public void addListener(POPPBigraphListener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(POPPBigraphListener listener) { listeners.remove(listener); }

    private void emit(POPPBigraphEvent event) {
        listeners.forEach(l -> l.onBigraphEvent(event));
    }

    /** Moves {@code child} in the raw bigraph and tells the listeners. */
    private void moveAndNotify(BigraphEntity.NodeEntity<DynamicControl> child,
                               BigraphEntity.NodeEntity<DynamicControl> newParent) {
        bigraph.moveNode(child, newParent);
        emit(new POPPBigraphEvent.NodeReparented(child, newParent));
    }

    public PureBigraphMutable getBigraph() { return bigraph; }

    public DynamicSignature getSignature() { return signature; }

    private DynamicSignature requireSignature() {
        if (signature == null) {
            throw new IllegalStateException("This POPPBigraph is read-only (no DynamicSignature); cannot mutate");
        }
        return signature;
    }

    private static POPPBigraphSignature controlOf(BigraphEntity<?> e) {
        return e instanceof BigraphEntity.NodeEntity<?> n ? POPPBigraphSignature.forNode(n) : null;
    }

    /** Whether {@code node} is a domain node, i.e. its control is a node kind. */
    public boolean isDomainNode(BigraphEntity.NodeEntity<?> node) {
        return POPPBigraphSignature.isKindControl(POPPBigraphSignature.forNode(node));
    }

    /** Whether {@code node} is a gate node ({@code GoalAnd}, {@code ProblemOr}, ...). */
    public boolean isGateNode(BigraphEntity.NodeEntity<?> node) {
        return POPPBigraphSignature.isDecompControl(POPPBigraphSignature.forNode(node));
    }

    /** The domain kind of a node. Empty for gate nodes, stubs and the container. */
    public Optional<NodeKind> kindOf(BigraphEntity.NodeEntity<?> node) {
        POPPBigraphSignature c = POPPBigraphSignature.forNode(node);
        return POPPBigraphSignature.isKindControl(c) ? Optional.of(POPPBigraphSignature.toNodeKind(c)) : Optional.empty();
    }

    /** The decomposition type of a gate node. {@code NONE} for every other node. */
    public DecompositionType decompositionTypeOf(BigraphEntity.NodeEntity<?> node) {
        return POPPBigraphSignature.decompositionTypeOf(POPPBigraphSignature.forNode(node));
    }

    /** Creates and announces a gate node. Callers must not announce it again. */
    private BigraphEntity.NodeEntity<DynamicControl> addGateNode(
            BigraphEntity.NodeEntity<DynamicControl> parent, NodeKind kind, DecompositionType type, String id) {
        POPPBigraphSignature control = POPPBigraphSignature.getGateControl(kind, type);
        var gate = addNode(parent, control, id);
        emit(new POPPBigraphEvent.NodeAdded(gate, parent));
        return gate;
    }

    /** Indexes an existing bigraph (e.g. a loaded file): every node by name, and every node's decomposition chain. */
    public void hydrate() {
        for (BigraphEntity.NodeEntity<DynamicControl> node : bigraph.getNodes()) {
            nodesById.put(node.getName(), node);
        }

        for (BigraphEntity.NodeEntity<DynamicControl> node : bigraph.getNodes()) {
            if (!isDomainNode(node)) {
                continue;
            }
            List<BigraphEntity.NodeEntity<DynamicControl>> chain = new ArrayList<>();
            for (BigraphEntity<?> child : bigraph.getChildrenOf(node)) {
                if (child instanceof BigraphEntity.NodeEntity<?> n && isGateNode(n)) {
                    collectGateNodes((BigraphEntity.NodeEntity<DynamicControl>) n, chain);
                }
            }
            if (!chain.isEmpty()) {
                decompGateIds.put(node.getName(), chain.stream().map(BigraphEntity.NodeEntity::getName).toList());
            }
        }
    }

    private void collectGateNodes(BigraphEntity.NodeEntity<DynamicControl> gateNode,
                                   List<BigraphEntity.NodeEntity<DynamicControl>> out) {
        out.add(gateNode);
        for (BigraphEntity<?> child : bigraph.getChildrenOf(gateNode)) {
            if (child instanceof BigraphEntity.NodeEntity<?> n && isGateNode(n)) {
                collectGateNodes((BigraphEntity.NodeEntity<DynamicControl>) n, out);
            }
        }
    }

    public BigraphEntity.RootEntity getDefaultRoot() {
        if (bigraph.getRoots().isEmpty()) {
            return bigraph.addRoot();
        }
        return bigraph.getRoots().getFirst();
    }

    public BigraphEntity.NodeEntity<DynamicControl> findPOPPContainer(BigraphEntity.RootEntity root) {
        for (BigraphEntity<?> child : bigraph.getChildrenOf(root)) {
            if (child instanceof BigraphEntity.NodeEntity<?> node && POPPBigraphSignature.POPP.matches(node)) {
                return (BigraphEntity.NodeEntity<DynamicControl>) node;
            }
        }
        return null;
    }

    private BigraphEntity.NodeEntity<DynamicControl> getOrCreatePOPPContainer() {
        BigraphEntity.RootEntity root = getDefaultRoot();
        BigraphEntity.NodeEntity<DynamicControl> existing = findPOPPContainer(root);
        if (existing != null) {
            nodesById.putIfAbsent("POPP", existing);
            return existing;
        }
        return addNode(root, POPPBigraphSignature.POPP, "POPP");
    }

    /** Raw add: registers the node in the cache but does NOT notify the listeners, callers do that. */
    private BigraphEntity.NodeEntity<DynamicControl> addNode(BigraphEntity<?> parent, POPPBigraphSignature control, String id) {
        DynamicControl ctrl = requireSignature().getControlByName(control.controlName());
        BigraphEntity.NodeEntity<DynamicControl> entity = bigraph.addNode(parent, ctrl, id);
        nodesById.put(id, entity);
        return entity;
    }


    /** Adds a domain node to the POPP container (created if missing) and builds its decomposition chain. */
    public BigraphEntity.NodeEntity<DynamicControl> addTreeNode(TreeNode<? extends TreeNode<?>> node) {
        BigraphEntity.NodeEntity<DynamicControl> container = getOrCreatePOPPContainer();
        BigraphEntity.NodeEntity<DynamicControl> entity = addNode(container, POPPBigraphSignature.getByNodeKind(node.getKind()), node.getId());
        putAttributes(entity, Map.of(
                "description", node.getDescription() == null ? "" : node.getDescription(),
                "layout.x", node.getX(),
                "layout.y", node.getY()));

        emit(new POPPBigraphEvent.NodeAdded(container, getDefaultRoot())); // idempotent, covers the lazily created container
        emit(new POPPBigraphEvent.NodeAdded(entity, container));
        rebuildDecomposition(node);
        return entity;
    }

    /**
     * Merges {@code values} into the node's attributes. {@code NodeEntity.getAttributes()} returns a copy of the
     * EMF-backed list, so writing to it is lost. Only {@code setAttributes} persists.
     */
    private static void putAttributes(BigraphEntity.NodeEntity<DynamicControl> entity, Map<String, Object> values) {
        Map<String, Object> merged = new HashMap<>(entity.getAttributes());
        merged.putAll(values);
        entity.setAttributes(merged);
    }

    /** Removes a domain node and its chain. Its relations and children must already be gone (see the model). */
    public void deleteTreeNode(String id) {
        clearDecompositionChain(id);
        removeNode(id).ifPresent(n -> emit(new POPPBigraphEvent.NodeRemoved(n)));
    }

    public void updatePosition(String id, double x, double y) {
        BigraphEntity.NodeEntity<DynamicControl> entity = requireNode(id);
        putAttributes(entity, Map.of("layout.x", x, "layout.y", y));
        emit(new POPPBigraphEvent.PositionChanged(entity, x, y));
    }

    public void updateDescription(String id, String description) {
        putAttributes(requireNode(id), Map.of("description", description == null ? "" : description));
    }

    /**
     * Moves a domain node from {@code oldParent} to {@code newParent} ({@code null}: the container). Called while the
     * model is mid-change, so the two chains are rebuilt with the node explicitly excluded from the old and included in
     * the new one rather than from the parents' (possibly not yet updated) child lists.
     */
    public void reparentTreeNode(TreeNode<?> node, TreeNode<?> oldParent, TreeNode<?> newParent) {
        BigraphEntity.NodeEntity<DynamicControl> entity = getOrAdd(node);
        BigraphEntity.NodeEntity<DynamicControl> container = getOrCreatePOPPContainer();
        bigraph.moveNode(entity, container);

        if (oldParent != null) {
            rebuildDecomposition(oldParent, node.getId());
        }

        if (newParent == null) {
            emit(new POPPBigraphEvent.NodeReparented(entity, container));
            return;
        }

        if (newParent.getDecompositionType() != DecompositionType.NONE) {
            rebuildDecomposition(newParent, null, node.getId());
        } else {
            moveAndNotify(entity, getOrAdd(newParent));
        }
    }

    /**
     * Raw removal of a node from bigraph + cache, without notifying the listeners: {@link #deleteTreeNode} announces it
     * afterwards, {@link #removeRelation} has announced the stubs with {@code onRelationRemoving} beforehand.
     */
    private Optional<BigraphEntity.NodeEntity<DynamicControl>> removeNode(String id) {
        return Optional.ofNullable(nodesById.remove(id)).map(node -> {
            bigraph.removeNode(node);
            return node;
        });
    }

    public Optional<BigraphEntity.NodeEntity<DynamicControl>> getById(String id) {
        return Optional.ofNullable(nodesById.get(id));
    }

    private BigraphEntity.NodeEntity<DynamicControl> requireNode(String id) {
        return getById(id).orElseThrow(() -> new IllegalStateException("No bigraph node for " + id));
    }

    private BigraphEntity.NodeEntity<DynamicControl> getOrAdd(TreeNode<?> node) {
        return getById(node.getId()).orElseGet(() -> addTreeNode(node));
    }


    public record DecodedDecomposition(
            DecompositionType type,
            List<BigraphEntity.NodeEntity<DynamicControl>> children) {
    }

    /**
     * The decomposition of a domain node as stored: the type of its chain and the domain nodes in the chain, or
     * {@code NONE} and the domain nodes directly under it (a defect the validator reports) if it has no chain.
     */
    public DecodedDecomposition decodeDecomposition(BigraphEntity.NodeEntity<DynamicControl> domainNode) {
        BigraphEntity.NodeEntity<DynamicControl> topGate = null;
        List<BigraphEntity.NodeEntity<DynamicControl>> directDomainChildren = new ArrayList<>();

        for (BigraphEntity<?> child : bigraph.getChildrenOf(domainNode)) {
            if (!(child instanceof BigraphEntity.NodeEntity<?> raw)) {
                continue;
            }
            var node = (BigraphEntity.NodeEntity<DynamicControl>) raw;
            if (isGateNode(node)) {
                topGate = node;
            } else if (isDomainNode(node)) {
                directDomainChildren.add(node);
            }
        }

        if (topGate != null) {
            DecompositionType type = decompositionTypeOf(topGate);
            List<BigraphEntity.NodeEntity<DynamicControl>> children = new ArrayList<>();
            collectGateChildren(topGate, children);
            return new DecodedDecomposition(type, children);
        }
        return new DecodedDecomposition(DecompositionType.NONE, directDomainChildren);
    }

    private void collectGateChildren(BigraphEntity.NodeEntity<DynamicControl> gateNode,
                                      List<BigraphEntity.NodeEntity<DynamicControl>> out) {
        for (BigraphEntity<?> child : bigraph.getChildrenOf(gateNode)) {
            if (!(child instanceof BigraphEntity.NodeEntity<?> raw)) {
                continue;
            }
            var typed = (BigraphEntity.NodeEntity<DynamicControl>) raw;
            if (isGateNode(typed)) {
                collectGateChildren(typed, out);
            } else if (isDomainNode(typed)) {
                out.add(typed);
            }
        }
    }

    public void rebuildDecomposition(TreeNode<?> node) {
        rebuildDecomposition(node, null, null);
    }

    public void rebuildDecomposition(TreeNode<?> node, String excludeChildId) {
        rebuildDecomposition(node, excludeChildId, null);
    }

    /**
     * Replaces the chain under {@code node} by one for its current decomposition type and children, minus
     * {@code excludeChildId} and plus {@code includeChildId} (either may be {@code null}). Needed while a reparenting
     * is half-applied in the model. Children of {@code NONE} are put directly under the node.
     */
    public void rebuildDecomposition(TreeNode<?> node, String excludeChildId, String includeChildId) {
        String nodeId = node.getId();
        clearDecompositionChain(nodeId);

        List<String> childIds = new ArrayList<>(node.getChildren().stream()
                .map(TreeNode::getId)
                .filter(id -> !id.equals(excludeChildId))
                .toList());
        if (includeChildId != null && !childIds.contains(includeChildId)) {
            childIds.add(includeChildId);
        }

        BigraphEntity.NodeEntity<DynamicControl> parent = getOrAdd(node);
        DecompositionType type = node.getDecompositionType();

        if (type == DecompositionType.NONE) {
            for (String childId : childIds) {
                BigraphEntity.NodeEntity<DynamicControl> child = requireNode(childId);
                if (!Objects.equals(bigraph.getParent(child), parent)) {
                    moveAndNotify(child, parent);
                }
            }
            return;
        }

        List<String> gateIds = new ArrayList<>();

        if (childIds.isEmpty()) {
            addGateNode(parent, node.getKind(), type, nodeId + "_DECOMP");
            gateIds.add(nodeId + "_DECOMP");
        } else if (childIds.size() == 1) {
            var topGate = addGateNode(parent, node.getKind(), type, nodeId + "_DECOMP");
            gateIds.add(nodeId + "_DECOMP");
            moveAndNotify(requireNode(childIds.getFirst()), topGate);
        } else {
            BigraphEntity.NodeEntity<DynamicControl> currentParent = parent;
            for (int i = 0; i < childIds.size() - 1; i++) {
                String gateId = i == 0 ? nodeId + "_DECOMP" : nodeId + "_DECOMP_" + i;
                var gateNode = addGateNode(currentParent, node.getKind(), type, gateId);
                gateIds.add(gateId);
                moveAndNotify(requireNode(childIds.get(i)), gateNode);
                currentParent = gateNode;
            }
            moveAndNotify(requireNode(childIds.getLast()), currentParent);
        }

        decompGateIds.put(nodeId, gateIds);
    }

    private void clearDecompositionChain(String nodeId) {
        List<String> gateIds = decompGateIds.remove(nodeId);
        if (gateIds == null) {
            return;
        }
        BigraphEntity.NodeEntity<DynamicControl> container = getOrCreatePOPPContainer();
        for (int i = gateIds.size() - 1; i >= 0; i--) {
            BigraphEntity.NodeEntity<DynamicControl> gateNode = nodesById.remove(gateIds.get(i));
            if (gateNode == null) {
                continue;
            }
            new ArrayList<>(bigraph.getChildrenOf(gateNode)).forEach(child -> {
                if (child instanceof BigraphEntity.NodeEntity<?> n && isDomainNode(n)) {
                    moveAndNotify((BigraphEntity.NodeEntity<DynamicControl>) n, container);
                }
            });
            bigraph.removeNode(gateNode);
            emit(new POPPBigraphEvent.NodeRemoved(gateNode));
        }
    }

    /** Adds the relation's two stubs under its endpoints and the edge connecting them. */
    public BigraphEntity.Edge createRelation(Relation relation) {
        BigraphEntity.NodeEntity<DynamicControl> source = getOrAdd(relation.source());
        BigraphEntity.NodeEntity<DynamicControl> target = getOrAdd(relation.target());
        POPPBigraphSignature sourceStubControl = POPPBigraphSignature.getSourceStub(relation);
        POPPBigraphSignature targetStubControl = POPPBigraphSignature.getTargetStub(relation);

        BigraphEntity.NodeEntity<DynamicControl> sourceStub = addNode(
                source, sourceStubControl, sourceStubControl + ":" + source.getName() + "->" + target.getName());
        BigraphEntity.NodeEntity<DynamicControl> targetStub = addNode(
                target, targetStubControl, targetStubControl + ":" + source.getName() + "->" + target.getName());

        BigraphEntity.Edge edge = bigraph.addEdge(relation.type() + ":" + source.getName() + "->" + target.getName());
        bigraph.connectNodeToLink(sourceStub, edge);
        bigraph.connectNodeToLink(targetStub, edge);

        emit(new POPPBigraphEvent.NodeAdded(sourceStub, source));
        emit(new POPPBigraphEvent.NodeAdded(targetStub, target));
        emit(new POPPBigraphEvent.RelationAdded(edge, List.of(sourceStub, targetStub)));
        return edge;
    }

    /** The {@link BigraphEntity.Edge} encoding a relation, found by its deterministic name (see {@link #createRelation}). */
    public Optional<BigraphEntity.Edge> findRelationEdge(Relation relation) {
        String edgeName = relation.type() + ":" + relation.source().getId() + "->" + relation.target().getId();
        return bigraph.getEdges().stream()
                .filter(e -> e.getName().equals(edgeName))
                .findFirst();
    }

    public void removeRelation(Relation relation) {
        BigraphEntity.Edge edge = findRelationEdge(relation).orElseThrow();
        // The points of a link are ports, not nodes: resolve the stub node that owns each port.
        List<BigraphEntity.NodeEntity<DynamicControl>> stubs = new ArrayList<>();
        for (BigraphEntity<?> point : bigraph.getPointsFromLink(edge)) {
            if (point instanceof BigraphEntity.Port port) {
                BigraphEntity.NodeEntity<DynamicControl> stub =
                        (BigraphEntity.NodeEntity<DynamicControl>) (BigraphEntity.NodeEntity<?>) bigraph.getNodeOfPort(port);
                if (stub != null) stubs.add(stub);
            }
        }

        emit(new POPPBigraphEvent.RelationRemoving(edge, stubs)); // before removal: listeners still need to resolve the entities

        stubs.forEach(stub -> removeNode(stub.getName())); // drops the cache entry as well as the bigraph node
        bigraph.removeEdge(edge);
    }

    public record RelationEndpoints(
            BigraphEntity.NodeEntity<DynamicControl> source,
            BigraphEntity.NodeEntity<DynamicControl> target) {
    }

    /** The source and target domain nodes named by a relation edge. Empty for edges not named like a relation. */
    public Optional<RelationEndpoints> decodeRelation(BigraphEntity.Edge edge) {
        String name = edge.getName();
        int colon = name.indexOf(':');
        int arrow = name.indexOf("->", colon + 1);
        if (colon < 0 || arrow < 0) return Optional.empty();
        return getById(name.substring(colon + 1, arrow)).flatMap(source ->
                getById(name.substring(arrow + 2)).map(target -> new RelationEndpoints(source, target)));
    }
}