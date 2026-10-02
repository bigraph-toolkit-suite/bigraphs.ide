package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Relation;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;
import org.eclipse.glsp.example.bigraph.views.BigraphView;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.graph.GraphFactory;

import java.util.*;
import java.util.function.Supplier;

/**
 * Stateful wrapper around a {@link PureBigraphMutable} that owns all knowledge
 * of how the POPP domain model's shape is encoded in it: the POPP container,
 * binary AND/OR decomposition chains, and relation stub/edge encoding. Both
 * {@link POPPBigraphSynchronizer} (write) and {@code POPPBigraphLoader} (read)
 * go through one instance of this class for every bigraph operation, so the
 * two directions can't drift apart.
 *
 * <p>Also keeps the core "bigraph" variant's {@link BigraphView} mirrored,
 * that view only ever updates through its public {@code on*} callbacks and
 * has no idea POPP exists, so every method here that changes the bigraph's
 * shape also tells the view about it. This only runs when a view is actually
 * bound (see {@link #POPPBigraph(PureBigraphMutable, DynamicSignature,
 * Supplier)}) since the read-only loader path never supplies one.</p>
 *
 * <p>Holds two caches, populated only by this instance's own write operations:
 * {@code nodesById} (domain/synthetic id → bigraph node, so a node this
 * instance created can be found again without scanning) and
 * {@code decompChainIds} (which synthetic {@code *_DECOMP} chain nodes this
 * instance built for a given domain node, so they can be torn down later).
 * A read-only instance (see {@link #POPPBigraph(PureBigraphMutable)}) never
 * populates either cache, since every read method here works by direct
 * traversal of the bigraph rather than cache lookups.
 */
@SuppressWarnings("unchecked")
public class POPPBigraph {
    private final PureBigraphMutable bigraph;
    private final DynamicSignature signature;
    private final Supplier<Optional<BigraphView>> viewSupplier;

    private final Map<String, BigraphEntity.NodeEntity<DynamicControl>> nodesById = new HashMap<>();
    private final Map<String, List<String>> decompChainIds = new HashMap<>();

    public POPPBigraph(PureBigraphMutable bigraph, DynamicSignature signature, Supplier<Optional<BigraphView>> viewSupplier) {
        this.bigraph = bigraph;
        this.signature = signature;
        this.viewSupplier = viewSupplier;
        hydrate(); // ensure existing nodes are added to decompChainIds + nodesById
    }

    public POPPBigraph(PureBigraphMutable bigraph, DynamicSignature signature) {
        this(bigraph, signature, Optional::empty);
    }

    public POPPBigraph(PureBigraphMutable bigraph) {
        this(bigraph, bigraph.getSignature(), Optional::empty);
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

    public BigraphEntity.NodeEntity<DynamicControl> getOrCreatePOPPContainer() {
        BigraphEntity.RootEntity root = getDefaultRoot();
        BigraphEntity.NodeEntity<DynamicControl> existing = findPOPPContainer(root);
        if (existing != null) {
            nodesById.putIfAbsent("POPP", existing);
            return existing;
        }
        return addNode(root, POPPBigraphSignature.POPP, "POPP");
    }

    public BigraphEntity.NodeEntity<DynamicControl> addNode(BigraphEntity<?> parent, POPPBigraphSignature control, String id) {
        DynamicControl ctrl = requireSignature().getControlByName(control.controlName());
        BigraphEntity.NodeEntity<DynamicControl> entity = bigraph.addNode(parent, ctrl, id);
        nodesById.put(id, entity);
        return entity;
    }

    public BigraphEntity.NodeEntity<DynamicControl> addTreeNode(TreeNode<? extends TreeNode<?>> node){
        BigraphEntity.NodeEntity<DynamicControl> container = getOrCreatePOPPContainer();
        BigraphEntity.NodeEntity<DynamicControl> entity = addNode(container, POPPBigraphSignature.getByNodeKind(node.getKind()), node.getId());
        entity.getAttributes().put("description", node.getDescription());
        entity.getAttributes().put("layout.x", node.getX());
        entity.getAttributes().put("layout.y", node.getY());

        mirrorContainerIfNeeded(container);
        mirrorAdd(entity, container);
        rebuildDecomposition(node);
        return entity;
    }

    public void deleteTreeNode(String id) {
        BigraphEntity.NodeEntity<DynamicControl> entity = getById(id);
        clearDecompositionChain(id);
        removeNode(id);
        if (entity != null) {
            mirrorDelete(entity);
        }
    }

    public void updatePosition(String id, double x, double y) {
        BigraphEntity.NodeEntity<DynamicControl> entity = getById(id);
        entity.getAttributes().put("layout.x", x);
        entity.getAttributes().put("layout.y", y);
        mirrorPosition(entity, x, y);
    }

    public void updateDescription(String id, String description) {
        getById(id).getAttributes().put("description", description);
    }

    public void reparentTreeNode(TreeNode<?> node, TreeNode<?> oldParent, TreeNode<?> newParent) {
        BigraphEntity.NodeEntity<DynamicControl> entity = getById(node.getId());
        bigraph.moveNode(entity, getOrCreatePOPPContainer());

        if (oldParent != null) {
            rebuildDecomposition(oldParent, node.getId());
        }

        if (newParent == null) {
            mirrorAttachToParent(entity, getOrCreatePOPPContainer());
            return;
        }

        if (newParent.getDecompositionType() != DecompositionType.NONE) {
            rebuildDecomposition(newParent, null, node.getId());
        } else {
            BigraphEntity.NodeEntity<DynamicControl> newParentEntity = getById(newParent.getId());
            bigraph.moveNode(entity, newParentEntity);
            mirrorAttachToParent(entity, newParentEntity);
        }
    }

    public void removeNode(String id) {
        BigraphEntity.NodeEntity<DynamicControl> node = nodesById.remove(id);
        if (node != null) {
            bigraph.removeNode(node);
        }
    }

    public BigraphEntity.NodeEntity<DynamicControl> getById(String id) {
        return nodesById.get(id);
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

        DecompositionType type = node.getDecompositionType();
        if (type == DecompositionType.NONE) {
            BigraphEntity.NodeEntity<DynamicControl> parentEntity = getById(nodeId);
            for (String childId : childIds) {
                BigraphEntity.NodeEntity<DynamicControl> child = getById(childId);
                if (child != null && !Objects.equals(bigraph.getParent(child), parentEntity)) {
                    bigraph.moveNode(child, parentEntity);
                    mirrorAttachToParent(child, parentEntity);
                }
            }
            return;
        }

        POPPBigraphSignature control = POPPBigraphSignature.getByDecompositionType(type);
        BigraphEntity<?> parent = getById(nodeId);
        List<String> chainIds = new ArrayList<>();

        if (childIds.isEmpty()) {
            BigraphEntity.NodeEntity<DynamicControl> chainNode = addNode(parent, control, nodeId + "_DECOMP");
            chainIds.add(nodeId + "_DECOMP");
            mirrorAdd(chainNode, parent);
        } else if (childIds.size() == 1) {
            BigraphEntity.NodeEntity<DynamicControl> chainRoot = addNode(parent, control, nodeId + "_DECOMP");
            chainIds.add(nodeId + "_DECOMP");
            mirrorAdd(chainRoot, parent);
            BigraphEntity.NodeEntity<DynamicControl> onlyChild = getById(childIds.get(0));
            bigraph.moveNode(onlyChild, chainRoot);
            mirrorAttachToParent(onlyChild, chainRoot);
        } else {
            BigraphEntity<?> currentParent = parent;
            for (int i = 0; i < childIds.size() - 1; i++) {
                String chainId = i == 0 ? nodeId + "_DECOMP" : nodeId + "_DECOMP_" + i;
                BigraphEntity.NodeEntity<DynamicControl> chainNode = addNode(currentParent, control, chainId);
                chainIds.add(chainId);
                mirrorAdd(chainNode, currentParent);
                BigraphEntity.NodeEntity<DynamicControl> child = getById(childIds.get(i));
                bigraph.moveNode(child, chainNode);
                mirrorAttachToParent(child, chainNode);
                currentParent = chainNode;
            }
            BigraphEntity.NodeEntity<DynamicControl> lastChild = getById(childIds.get(childIds.size() - 1));
            bigraph.moveNode(lastChild, (BigraphEntity.NodeEntity<DynamicControl>) currentParent);
            mirrorAttachToParent(lastChild, (BigraphEntity.NodeEntity<DynamicControl>) currentParent);
        }

        decompChainIds.put(nodeId, chainIds);
    }

    public void clearDecompositionChain(String nodeId) {
        List<String> chainIds = decompChainIds.remove(nodeId);
        if (chainIds == null) {
            return;
        }
        BigraphEntity.NodeEntity<DynamicControl> container = getOrCreatePOPPContainer();
        for (int i = chainIds.size() - 1; i >= 0; i--) {
            String chainId = chainIds.get(i);
            BigraphEntity.NodeEntity<DynamicControl> chainNode = nodesById.remove(chainId);
            if (chainNode == null) {
                continue;
            }
            new ArrayList<>(bigraph.getChildrenOf(chainNode)).forEach(child -> {
                if (child instanceof BigraphEntity.NodeEntity<?> n) {
                    BigraphEntity.NodeEntity<DynamicControl> typed = (BigraphEntity.NodeEntity<DynamicControl>) n;
                    bigraph.moveNode(typed, container);
                    mirrorAttachToParent(typed, container);
                }
            });
            bigraph.removeNode(chainNode);
            mirrorDelete(chainNode);
        }
    }

    public BigraphEntity.Edge createRelation(Relation relation) {
        BigraphEntity.NodeEntity<DynamicControl> source = getById(relation.source().getId());
        BigraphEntity.NodeEntity<DynamicControl> target = getById(relation.target().getId());
        POPPBigraphSignature sourceStubControl = POPPBigraphSignature.getSourceStub(relation);
        POPPBigraphSignature targetStubControl = POPPBigraphSignature.getTargetStub(relation);

        BigraphEntity.NodeEntity<DynamicControl> sourceStub = addNode(
                source, sourceStubControl, sourceStubControl + ":" + source.getName() + "->" + target.getName());
        BigraphEntity.NodeEntity<DynamicControl> targetStub = addNode(
                target, targetStubControl, targetStubControl + ":" + source.getName() + "->" + target.getName());

        BigraphEntity.Edge edge = bigraph.addEdge(relation.type() + ":" + source.getName() + "->" + target.getName());
        bigraph.connectNodeToLink(sourceStub, edge);
        bigraph.connectNodeToLink(targetStub, edge);

        mirrorAdd(sourceStub, source);
        mirrorAdd(targetStub, target);
        mirrorRelationEdge(edge, List.of(sourceStub, targetStub));
        return edge;
    }

    /** The {@link BigraphEntity.Edge} encoding a relation, by its deterministic name — see {@link #createRelation}. */
    public Optional<BigraphEntity.Edge> findRelationEdge(RelationType type, String sourceId, String targetId) {
        String edgeName = type + ":" + sourceId + "->" + targetId;
        return bigraph.getEdges().stream()
                .filter(e -> e.getName().equals(edgeName))
                .findFirst();
    }

    public void removeRelation(RelationType type, String sourceId, String targetId) {
        BigraphEntity.Edge edge = findRelationEdge(type, sourceId, targetId).orElseThrow();
        List<BigraphEntity<?>> stubs = new ArrayList<>(bigraph.getPointsFromLink(edge));

        mirrorDeleteRelation(edge, stubs);

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
        BigraphEntity.NodeEntity<DynamicControl> source = null;
        BigraphEntity.NodeEntity<DynamicControl> target = null;

        for (BigraphEntity<?> point : bigraph.getPointsFromLink(edge)) {
            if (!(point instanceof BigraphEntity.NodeEntity<?> stub)) {
                continue;
            }
            POPPBigraphSignature stubControl = POPPBigraphSignature.forNode((BigraphEntity.NodeEntity<DynamicControl>) stub);
            if (stubControl == null) {
                continue;
            }
            BigraphEntity<?> owner = bigraph.getParent((BigraphEntity.NodeEntity<DynamicControl>) stub);
            if (!(owner instanceof BigraphEntity.NodeEntity<?> ownerNode)) {
                continue;
            }
            if (POPPBigraphSignature.isOutgoingStub(stubControl)) {
                source = (BigraphEntity.NodeEntity<DynamicControl>) ownerNode;
            } else if (POPPBigraphSignature.isIncomingStub(stubControl)) {
                target = (BigraphEntity.NodeEntity<DynamicControl>) ownerNode;
            }
        }

        if (source == null || target == null) {
            return Optional.empty();
        }
        return Optional.of(new RelationEndpoints(source, target));
    }

    private Optional<BigraphView> view() {
        return viewSupplier.get();
    }

    /** Mirrors the (lazily created) POPP container node into the view the first time it's needed. */
    private void mirrorContainerIfNeeded(BigraphEntity.NodeEntity<DynamicControl> container) {
        view().ifPresent(view -> {
            if (view.getGModelIdForEntity(container).isEmpty()) {
                view.onAddNode(container, getDefaultRoot(), container.getControl());
            }
        });
    }

    private void mirrorAdd(BigraphEntity.NodeEntity<DynamicControl> entity, BigraphEntity<?> parent) {
        view().ifPresent(view -> {
            if (view.getGModelIdForEntity(entity).isEmpty()) {
                view.onAddNode(entity, parent, entity.getControl());
            }
        });
    }

    private void mirrorDelete(BigraphEntity.NodeEntity<DynamicControl> entity) {
        view().ifPresent(view -> view.getGModelIdForEntity(entity).ifPresent(view::onDeleteNode));
    }

    private void mirrorPosition(BigraphEntity.NodeEntity<DynamicControl> entity, double x, double y) {
        view().ifPresent(view -> view.getGModelIdForEntity(entity).ifPresent(viewId ->
                findGNode(view, viewId).ifPresent(gNode -> {
                    GPoint point = GraphFactory.eINSTANCE.createGPoint();
                    point.setX(x);
                    point.setY(y);
                    gNode.setPosition(point);
                })));
    }

    /** Re-targets an existing place-edge to a new parent, or creates one if the child never had one. */
    private void mirrorAttachToParent(BigraphEntity.NodeEntity<DynamicControl> child, BigraphEntity.NodeEntity<DynamicControl> parent) {
        view().ifPresent(view -> view.getGModelIdForEntity(child).ifPresent(childId ->
                view.getGModelIdForEntity(parent).ifPresent(parentId ->
                        view.onMoveNode(childId, parentId))));
    }

    private void mirrorRelationEdge(BigraphEntity.Edge edge, Collection<BigraphEntity<?>> points) {
        view().ifPresent(view -> {
            // Core's LinkRenderer only wires Port/InnerName/OuterName points, not plain stub nodes
            // (see LinkRenderer#createLinkConnections), so the hyperedge-to-stub links are built here instead.
            GNode hyperEdgeNode = view.onAddEdge(edge, List.of(), Optional.empty());
            points.forEach(point -> view.getGModelIdForEntity(point).ifPresent(targetId -> {
                GEdge linkConnection = GraphFactory.eINSTANCE.createGEdge();
                linkConnection.setId("popp_link_conn_" + UUID.randomUUID());
                linkConnection.setType("bigraph:link-connection");
                linkConnection.setSourceId(hyperEdgeNode.getId());
                linkConnection.setTargetId(targetId);
                linkConnection.getCssClasses().add("bigraph-link-connection");
                view.getOwnerRoot().getChildren().add(linkConnection);
            }));
        });
    }

    /** Must be called BEFORE the edge/stubs are removed from the raw bigraph (their view ids are looked up here). */
    private void mirrorDeleteRelation(BigraphEntity.Edge edge, Collection<BigraphEntity<?>> points) {
        view().ifPresent(view -> {
            view.getGModelIdForEntity(edge).ifPresent(view::onDeleteEdge);
            points.forEach(point -> view.getGModelIdForEntity(point).ifPresent(view::onDeleteNode));
        });
    }

    private Optional<GNode> findGNode(final BigraphView view, final String gModelId) {
        return view.getOwnerRoot().getChildren().stream()
                .filter(GNode.class::isInstance)
                .map(GNode.class::cast)
                .filter(n -> n.getId().equals(gModelId))
                .findFirst();
    }
}