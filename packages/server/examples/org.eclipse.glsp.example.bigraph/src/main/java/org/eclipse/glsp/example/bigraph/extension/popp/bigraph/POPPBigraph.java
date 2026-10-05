package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stateful wrapper around a {@link PureBigraphMutable} that owns all knowledge
 * of how the POPP domain model's shape is encoded in it: the POPP container,
 * binary AND/OR decomposition chains, and relation stub/edge encoding. Both
 * {@link POPPBigraphSynchronizer} (write) and {@code POPPBigraphLoader} (read)
 * go through one instance of this class for every bigraph operation, so the
 * two directions can't drift apart.
 *
 * <p>Every structural change is announced to the registered {@link POPPBigraphObserver}s
 * (e.g. {@link BigraphViewMirror}); this class knows nothing about views. A read-only
 * instance simply has no observers.</p>
 *
 * <p>Holds two caches, populated only by this instance's own write operations:
 * {@code nodesById} (domain/synthetic id → bigraph node) and {@code decompChainIds}
 * (which synthetic {@code *_DECOMP} chain nodes this instance built for a given domain node,
 * so they can be torn down later). Both are seeded from the existing bigraph by {@link #hydrate()}.</p>
 */
@SuppressWarnings("unchecked")
public class POPPBigraph {
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

    public void addObserver(POPPBigraphObserver observer) {
        observers.add(observer);
    }

    public void removeObserver(POPPBigraphObserver observer) {
        observers.remove(observer);
    }

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

    public PureBigraphMutable getBigraph() {
        return bigraph;
    }

    public DynamicSignature getSignature() {
        return signature;
    }

    private DynamicSignature requireSignature() {
        if (signature == null) {
            throw new IllegalStateException("This POPPBigraph is read-only (no DynamicSignature); cannot mutate");
        }
        return signature;
    }

    public void hydrate() {
        for (BigraphEntity.NodeEntity<DynamicControl> node : bigraph.getNodes()) {
            nodesById.put(node.getName(), node);
        }

        for (BigraphEntity.NodeEntity<DynamicControl> node : bigraph.getNodes()) {
            POPPBigraphSignature control = POPPBigraphSignature.forNode(node);
            if (control == null || POPPBigraphSignature.isDecompControl(control)) {
                continue;
            }
            List<BigraphEntity.NodeEntity<DynamicControl>> chain = new ArrayList<>();
            for (BigraphEntity<?> child : bigraph.getChildrenOf(node)) {
                if (child instanceof BigraphEntity.NodeEntity<?> n && isDecompChainNode(n)) {
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
            if (child instanceof BigraphEntity.NodeEntity<?> n && isDecompChainNode(n)) {
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

    /** Raw add: registers the node in the cache but does NOT notify observers, callers do that once the node is fully set up. */
    private BigraphEntity.NodeEntity<DynamicControl> addNode(BigraphEntity<?> parent, POPPBigraphSignature control, String id) {
        DynamicControl ctrl = requireSignature().getControlByName(control.controlName());
        BigraphEntity.NodeEntity<DynamicControl> entity = bigraph.addNode(parent, ctrl, id);
        nodesById.put(id, entity);
        return entity;
    }


    public BigraphEntity.NodeEntity<DynamicControl> addTreeNode(TreeNode<? extends TreeNode<?>> node) {
        BigraphEntity.NodeEntity<DynamicControl> container = getOrCreatePOPPContainer();
        BigraphEntity.NodeEntity<DynamicControl> entity =
                addNode(container, POPPBigraphSignature.getByNodeKind(node.getKind()), node.getId());
        entity.getAttributes().put("description", node.getDescription());
        entity.getAttributes().put("layout.x", node.getX());
        entity.getAttributes().put("layout.y", node.getY());

        fireNodeAdded(container, getDefaultRoot()); // idempotent, covers the lazily created container
        fireNodeAdded(entity, container);
        rebuildDecomposition(node);
        return entity;
    }

    public void deleteTreeNode(String id) {
        clearDecompositionChain(id);
        removeNode(id).ifPresent(this::fireNodeRemoved);
    }

    public void updatePosition(String id, double x, double y) {
        BigraphEntity.NodeEntity<DynamicControl> entity = requireNode(id);
        entity.getAttributes().put("layout.x", x);
        entity.getAttributes().put("layout.y", y);
        firePositionChanged(entity, x, y);
    }

    public void updateDescription(String id, String description) {
        requireNode(id).getAttributes().put("description", description);
    }

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

    /** Raw removal from bigraph + cache. Does not notify observers. */
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

    private boolean isDecompChainNode(BigraphEntity.NodeEntity<?> node) {
        return POPPBigraphSignature.isDecompControl(POPPBigraphSignature.forNode(node));
    }

    public record DecodedDecomposition(
            DecompositionType type,
            List<BigraphEntity.NodeEntity<DynamicControl>> children) {
    }

    public DecodedDecomposition decodeDecomposition(BigraphEntity.NodeEntity<DynamicControl> domainNode) {
        BigraphEntity.NodeEntity<DynamicControl> chainRoot = null;
        List<BigraphEntity.NodeEntity<DynamicControl>> directDomainChildren = new ArrayList<>();

        for (BigraphEntity<?> child : bigraph.getChildrenOf(domainNode)) {
            if (!(child instanceof BigraphEntity.NodeEntity<?> raw)) {
                continue;
            }
            BigraphEntity.NodeEntity<DynamicControl> node = (BigraphEntity.NodeEntity<DynamicControl>) raw;
            POPPBigraphSignature control = POPPBigraphSignature.forNode(node);
            if (control == null) {
                continue;
            }
            if (POPPBigraphSignature.isDecompControl(control)) {
                chainRoot = node;
            } else if (!POPPBigraphSignature.isIncomingStub(control) && !POPPBigraphSignature.isOutgoingStub(control)) {
                directDomainChildren.add(node);
            }
        }

        if (chainRoot != null) {
            DecompositionType type = POPPBigraphSignature.decompositionTypeOf(POPPBigraphSignature.forNode(chainRoot));
            List<BigraphEntity.NodeEntity<DynamicControl>> children = new ArrayList<>();
            collectChainChildren(chainRoot, children);
            return new DecodedDecomposition(type, children);
        }
        return new DecodedDecomposition(DecompositionType.NONE, directDomainChildren);
    }

    private void collectChainChildren(
            BigraphEntity.NodeEntity<DynamicControl> chainNode, List<BigraphEntity.NodeEntity<DynamicControl>> out) {
        for (BigraphEntity<?> child : bigraph.getChildrenOf(chainNode)) {
            if (child instanceof BigraphEntity.NodeEntity<?> node) {
                BigraphEntity.NodeEntity<DynamicControl> typed = (BigraphEntity.NodeEntity<DynamicControl>) node;
                if (isDecompChainNode(typed)) {
                    collectChainChildren(typed, out);
                } else {
                    out.add(typed);
                }
            }
        }
    }

    public void rebuildDecomposition(TreeNode<?> node) {
        rebuildDecomposition(node, null, null);
    }

    public void rebuildDecomposition(TreeNode<?> node, String excludeChildId) {
        rebuildDecomposition(node, excludeChildId, null);
    }

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

        POPPBigraphSignature control = POPPBigraphSignature.getByDecompositionType(type);
        List<String> chainIds = new ArrayList<>();

        if (childIds.isEmpty()) {
            BigraphEntity.NodeEntity<DynamicControl> chainNode = addNode(parent, control, nodeId + "_DECOMP");
            chainIds.add(nodeId + "_DECOMP");
            fireNodeAdded(chainNode, parent);
        } else if (childIds.size() == 1) {
            BigraphEntity.NodeEntity<DynamicControl> chainRoot = addNode(parent, control, nodeId + "_DECOMP");
            chainIds.add(nodeId + "_DECOMP");
            fireNodeAdded(chainRoot, parent);
            moveAndNotify(requireNode(childIds.getFirst()), chainRoot);
        } else {
            BigraphEntity.NodeEntity<DynamicControl> currentParent = parent;
            for (int i = 0; i < childIds.size() - 1; i++) {
                String chainId = i == 0 ? nodeId + "_DECOMP" : nodeId + "_DECOMP_" + i;
                BigraphEntity.NodeEntity<DynamicControl> chainNode = addNode(currentParent, control, chainId);
                chainIds.add(chainId);
                fireNodeAdded(chainNode, currentParent);
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
                if (child instanceof BigraphEntity.NodeEntity<?> n) {
                    moveAndNotify((BigraphEntity.NodeEntity<DynamicControl>) n, container);
                }
            });
            bigraph.removeNode(chainNode);
            fireNodeRemoved(chainNode);
        }
    }

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

    /** The {@link BigraphEntity.Edge} encoding a relation, by its deterministic name see: {@link #createRelation}. */
    public Optional<BigraphEntity.Edge> findRelationEdge(Relation relation) {
        String edgeName = relation.type() + ":" + relation.source().getId() + "->" + relation.target().getId();
        return bigraph.getEdges().stream()
                .filter(e -> e.getName().equals(edgeName))
                .findFirst();
    }

    public void removeRelation(Relation relation) {
        BigraphEntity.Edge edge = findRelationEdge(relation).orElseThrow();
        List<BigraphEntity<?>> stubs = new ArrayList<>(bigraph.getPointsFromLink(edge));

        fireRelationRemoving(edge, stubs); // before removal: observers still need to resolve the entities

        stubs.forEach(stub -> {
            if (stub instanceof BigraphEntity.NodeEntity<?> node) {
                removeNode(node.getName());
            }
        });
        bigraph.removeEdge(edge);
    }

    public record RelationEndpoints(
            BigraphEntity.NodeEntity<DynamicControl> source,
            BigraphEntity.NodeEntity<DynamicControl> target) {
    }

    public Optional<RelationEndpoints> decodeRelation(BigraphEntity.Edge edge) {
        String name = edge.getName();
        int colon = name.indexOf(':');
        int arrow = name.indexOf("->", colon + 1);
        if (colon < 0 || arrow < 0) return Optional.empty();
        return getById(name.substring(colon + 1, arrow)).flatMap(source ->
                getById(name.substring(arrow + 2)).map(target -> new RelationEndpoints(source, target)));
    }
}