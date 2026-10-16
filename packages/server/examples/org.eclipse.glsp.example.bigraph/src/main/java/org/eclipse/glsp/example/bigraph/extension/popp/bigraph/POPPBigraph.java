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
 * The bigraph encoding of a POPP model, kept in sync with it through explicit calls and announced to observers.
 *
 * <p>Encoding: every domain node is a node whose control is its kind ({@code Problem}, {@code Goal}, ...). An AND/OR
 * decomposition with n children becomes a chain of n-1 binary {@code AndDecomp}/{@code OrDecomp} nodes under the
 * decomposed node (one empty chain node without children, one chain node for a single child); the chain node
 * directly under a domain node is the first one. A relation is an edge between two arity-1 stub nodes, one under each
 * endpoint. Every node is addressable by its id ({@code nodesById}): domain nodes by their model id, chain nodes by
 * {@code <id>_DECOMP[_i]}, stubs by {@code <STUB_CONSTANT>:<source id>-><target id>}; the edge is named
 * {@code <TYPE>:<source id>-><target id>} (so ids must not contain {@code ->}).</p>
 */
@SuppressWarnings("unchecked")
public class POPPBigraph {
    private final static Logger logger = LoggerFactory.getLogger(POPPBigraph.class);

    private final PureBigraphMutable bigraph;
    private final DynamicSignature signature;
    private final List<POPPBigraphObserver> observers = new CopyOnWriteArrayList<>();

    private final Map<String, BigraphEntity.NodeEntity<DynamicControl>> nodesById = new HashMap<>();
    private final Map<String, List<String>> decompChainIds = new HashMap<>();

    public POPPBigraph(PureBigraphMutable bigraph, DynamicSignature signature) {
        this.bigraph = bigraph;
        this.signature = signature;
        hydrate();
    }

    public POPPBigraph(PureBigraphMutable bigraph) {
        this(bigraph, bigraph.getSignature());
    }

    public void addObserver(POPPBigraphObserver observer) { observers.add(observer); }

    public void removeObserver(POPPBigraphObserver observer) { observers.remove(observer); }

    private void fireNodeAdded(BigraphEntity.NodeEntity<DynamicControl> node, BigraphEntity<?> parent) {
        observers.forEach(o -> o.onNodeAdded(node, parent));
    }

    private void fireNodeRemoved(BigraphEntity.NodeEntity<DynamicControl> node) {
        observers.forEach(o -> o.onNodeRemoved(node));
    }

    private void fireNodeReparented(BigraphEntity.NodeEntity<DynamicControl> child,
                                    BigraphEntity.NodeEntity<DynamicControl> newParent) {
        observers.forEach(o -> o.onNodeReparented(child, newParent));
    }

    private void firePositionChanged(BigraphEntity.NodeEntity<DynamicControl> node, double x, double y) {
        observers.forEach(o -> o.onPositionChanged(node, x, y));
    }

    private void fireRelationAdded(BigraphEntity.Edge edge, List<? extends BigraphEntity<?>> stubs) {
        observers.forEach(o -> o.onRelationAdded(edge, stubs));
    }

    private void fireRelationRemoving(BigraphEntity.Edge edge, List<? extends BigraphEntity<?>> stubs) {
        observers.forEach(o -> o.onRelationRemoving(edge, stubs));
    }

    /** Moves {@code child} in the raw bigraph and tells observers. */
    private void moveAndNotify(BigraphEntity.NodeEntity<DynamicControl> child,
                               BigraphEntity.NodeEntity<DynamicControl> newParent) {
        bigraph.moveNode(child, newParent);
        fireNodeReparented(child, newParent);
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

    /** Whether {@code node} is a decomposition chain node ({@code AndDecomp} or {@code OrDecomp}). */
    public boolean isChainNode(BigraphEntity.NodeEntity<?> node) {
        return POPPBigraphSignature.isDecompControl(POPPBigraphSignature.forNode(node));
    }

    /** The domain kind of a node; empty for chain nodes, stubs and the container. */
    public Optional<NodeKind> kindOf(BigraphEntity.NodeEntity<?> node) {
        POPPBigraphSignature c = POPPBigraphSignature.forNode(node);
        return POPPBigraphSignature.isKindControl(c) ? Optional.of(POPPBigraphSignature.toNodeKind(c)) : Optional.empty();
    }

    /** The decomposition type of a chain node; {@code NONE} for every other node. */
    public DecompositionType decompositionTypeOf(BigraphEntity.NodeEntity<?> node) {
        return POPPBigraphSignature.decompositionTypeOf(POPPBigraphSignature.forNode(node));
    }

    /** Creates and announces a chain node. Callers must not announce it again. */
    private BigraphEntity.NodeEntity<DynamicControl> addChainNode(
            BigraphEntity.NodeEntity<DynamicControl> parent, DecompositionType type, String id) {
        var chain = addNode(parent, POPPBigraphSignature.getByDecompositionType(type), id);
        fireNodeAdded(chain, parent);
        return chain;
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
                if (child instanceof BigraphEntity.NodeEntity<?> n && isChainNode(n)) {
                    collectChainNodes((BigraphEntity.NodeEntity<DynamicControl>) n, chain);
                }
            }
            if (!chain.isEmpty()) {
                decompChainIds.put(node.getName(), chain.stream().map(BigraphEntity.NodeEntity::getName).toList());
            }
        }
    }

    private void collectChainNodes(BigraphEntity.NodeEntity<DynamicControl> chainNode,
                                   List<BigraphEntity.NodeEntity<DynamicControl>> out) {
        out.add(chainNode);
        for (BigraphEntity<?> child : bigraph.getChildrenOf(chainNode)) {
            if (child instanceof BigraphEntity.NodeEntity<?> n && isChainNode(n)) {
                collectChainNodes((BigraphEntity.NodeEntity<DynamicControl>) n, out);
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

    /** Raw add: registers the node in the cache but does NOT notify observers, callers do that. */
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

        fireNodeAdded(container, getDefaultRoot()); // idempotent, covers the lazily created container
        fireNodeAdded(entity, container);
        rebuildDecomposition(node);
        return entity;
    }

    /**
     * Merges {@code values} into the node's attributes. {@code NodeEntity.getAttributes()} returns a copy of the
     * EMF-backed list, so writing to it is lost; only {@code setAttributes} persists.
     */
    private static void putAttributes(BigraphEntity.NodeEntity<DynamicControl> entity, Map<String, Object> values) {
        Map<String, Object> merged = new HashMap<>(entity.getAttributes());
        merged.putAll(values);
        entity.setAttributes(merged);
    }

    /** Removes a domain node and its chain; its relations and children must already be gone (see the model). */
    public void deleteTreeNode(String id) {
        clearDecompositionChain(id);
        removeNode(id).ifPresent(this::fireNodeRemoved);
    }

    public void updatePosition(String id, double x, double y) {
        BigraphEntity.NodeEntity<DynamicControl> entity = requireNode(id);
        putAttributes(entity, Map.of("layout.x", x, "layout.y", y));
        firePositionChanged(entity, x, y);
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
            fireNodeReparented(entity, container);
            return;
        }

        if (newParent.getDecompositionType() != DecompositionType.NONE) {
            rebuildDecomposition(newParent, null, node.getId());
        } else {
            moveAndNotify(entity, getOrAdd(newParent));
        }
    }

    /**
     * Raw removal of a node from bigraph + cache, without notifying observers: {@link #deleteTreeNode} announces it
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
        BigraphEntity.NodeEntity<DynamicControl> chainRoot = null;
        List<BigraphEntity.NodeEntity<DynamicControl>> directDomainChildren = new ArrayList<>();

        for (BigraphEntity<?> child : bigraph.getChildrenOf(domainNode)) {
            if (!(child instanceof BigraphEntity.NodeEntity<?> raw)) {
                continue;
            }
            var node = (BigraphEntity.NodeEntity<DynamicControl>) raw;
            if (isChainNode(node)) {
                chainRoot = node;
            } else if (isDomainNode(node)) {
                directDomainChildren.add(node);
            }
        }

        if (chainRoot != null) {
            DecompositionType type = decompositionTypeOf(chainRoot);
            List<BigraphEntity.NodeEntity<DynamicControl>> children = new ArrayList<>();
            collectChainChildren(chainRoot, children);
            return new DecodedDecomposition(type, children);
        }
        return new DecodedDecomposition(DecompositionType.NONE, directDomainChildren);
    }

    private void collectChainChildren(BigraphEntity.NodeEntity<DynamicControl> chainNode,
                                      List<BigraphEntity.NodeEntity<DynamicControl>> out) {
        for (BigraphEntity<?> child : bigraph.getChildrenOf(chainNode)) {
            if (!(child instanceof BigraphEntity.NodeEntity<?> raw)) {
                continue;
            }
            var typed = (BigraphEntity.NodeEntity<DynamicControl>) raw;
            if (isChainNode(typed)) {
                collectChainChildren(typed, out);
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
     * {@code excludeChildId} and plus {@code includeChildId} (either may be {@code null}); needed while a reparenting
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

        List<String> chainIds = new ArrayList<>();

        if (childIds.isEmpty()) {
            addChainNode(parent, type, nodeId + "_DECOMP");
            chainIds.add(nodeId + "_DECOMP");
        } else if (childIds.size() == 1) {
            var chainRoot = addChainNode(parent, type, nodeId + "_DECOMP");
            chainIds.add(nodeId + "_DECOMP");
            moveAndNotify(requireNode(childIds.getFirst()), chainRoot);
        } else {
            BigraphEntity.NodeEntity<DynamicControl> currentParent = parent;
            for (int i = 0; i < childIds.size() - 1; i++) {
                String chainId = i == 0 ? nodeId + "_DECOMP" : nodeId + "_DECOMP_" + i;
                var chainNode = addChainNode(currentParent, type, chainId);
                chainIds.add(chainId);
                moveAndNotify(requireNode(childIds.get(i)), chainNode);
                currentParent = chainNode;
            }
            moveAndNotify(requireNode(childIds.getLast()), currentParent);
        }

        decompChainIds.put(nodeId, chainIds);
    }

    private void clearDecompositionChain(String nodeId) {
        List<String> chainIds = decompChainIds.remove(nodeId);
        if (chainIds == null) {
            return;
        }
        BigraphEntity.NodeEntity<DynamicControl> container = getOrCreatePOPPContainer();
        for (int i = chainIds.size() - 1; i >= 0; i--) {
            BigraphEntity.NodeEntity<DynamicControl> chainNode = nodesById.remove(chainIds.get(i));
            if (chainNode == null) {
                continue;
            }
            new ArrayList<>(bigraph.getChildrenOf(chainNode)).forEach(child -> {
                if (child instanceof BigraphEntity.NodeEntity<?> n && isDomainNode(n)) {
                    moveAndNotify((BigraphEntity.NodeEntity<DynamicControl>) n, container);
                }
            });
            bigraph.removeNode(chainNode);
            fireNodeRemoved(chainNode);
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

        fireNodeAdded(sourceStub, source);
        fireNodeAdded(targetStub, target);
        fireRelationAdded(edge, List.of(sourceStub, targetStub));
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

        fireRelationRemoving(edge, stubs); // before removal: observers still need to resolve the entities

        stubs.forEach(stub -> removeNode(stub.getName())); // drops the cache entry as well as the bigraph node
        bigraph.removeEdge(edge);
    }

    public record RelationEndpoints(
            BigraphEntity.NodeEntity<DynamicControl> source,
            BigraphEntity.NodeEntity<DynamicControl> target) {
    }

    /** The source and target domain nodes named by a relation edge; empty for edges not named like a relation. */
    public Optional<RelationEndpoints> decodeRelation(BigraphEntity.Edge edge) {
        String name = edge.getName();
        int colon = name.indexOf(':');
        int arrow = name.indexOf("->", colon + 1);
        if (colon < 0 || arrow < 0) return Optional.empty();
        return getById(name.substring(colon + 1, arrow)).flatMap(source ->
                getById(name.substring(arrow + 2)).map(target -> new RelationEndpoints(source, target)));
    }
}