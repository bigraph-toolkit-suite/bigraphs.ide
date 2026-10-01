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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        rebuildDecomposition(node);
        entity.getAttributes().put("description", node.getDescription());
        entity.getAttributes().put("layout.x", node.getX());
        entity.getAttributes().put("layout.y", node.getY());

        mirrorContainerIfNeeded(container);
        mirrorAdd(entity, container);
        return entity;
    }

    /** Removes a TreeNode-backed domain node (and its decomposition chain), mirroring the deletion into the view. */
    public void deleteTreeNode(String id) {
        BigraphEntity.NodeEntity<DynamicControl> entity = getById(id);
        clearDecompositionChain(id);
        removeNode(id);
        if (entity != null) {
            mirrorDelete(entity);
        }
    }

    /** Updates a node's position attributes and its mirrored GNode position. */
    public void updatePosition(String id, double x, double y) {
        BigraphEntity.NodeEntity<DynamicControl> entity = getById(id);
        entity.getAttributes().put("layout.x", x);
        entity.getAttributes().put("layout.y", y);
        mirrorPosition(entity, x, y);
    }

    /** Updates a node's description attribute (not mirrored — the raw bigraph view never renders it). */
    public void updateDescription(String id, String description) {
        getById(id).getAttributes().put("description", description);
    }

    /** Re-parents a domain node (decomposition link change), mirroring the new place-edge into the view. */
    public void reparentTreeNode(TreeNode<?> node, TreeNode<?> oldParent, TreeNode<?> newParent) {
        BigraphEntity.NodeEntity<DynamicControl> entity = getById(node.getId());
        bigraph.moveNode(entity, getOrCreatePOPPContainer());

        if (oldParent != null) {
            rebuildDecomposition(oldParent, node.getId());
        }

        BigraphEntity.NodeEntity<DynamicControl> mirroredParent;
        if (newParent == null) {
            mirroredParent = getOrCreatePOPPContainer();
        } else {
            if (newParent.getDecompositionType() != DecompositionType.NONE) {
                rebuildDecomposition(newParent);
            } else {
                bigraph.moveNode(entity, getById(newParent.getId()));
            }
            // The domain node may now sit under a synthetic *_DECOMP chain node (never mirrored),
            // but visually the child should attach to its semantic parent's own mirrored GNode.
            mirroredParent = getById(newParent.getId());
        }

        mirrorReparent(entity, mirroredParent);
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
        rebuildDecomposition(node, null);
    }

    public void rebuildDecomposition(TreeNode<?> node, String excludeChildId) {
        String nodeId = node.getId();
        clearDecompositionChain(nodeId);

        DecompositionType type = node.getDecompositionType();
        if (type == DecompositionType.NONE) {
            return;
        }

        List<String> childIds = node.getChildren().stream()
                .map(TreeNode::getId)
                .filter(id -> !id.equals(excludeChildId))
                .toList();

        POPPBigraphSignature control = POPPBigraphSignature.getByDecompositionType(type);
        BigraphEntity<?> parent = getById(nodeId);
        List<String> chainIds = new ArrayList<>();

        if (childIds.isEmpty()) {
            addNode(parent, control, nodeId + "_DECOMP");
            chainIds.add(nodeId + "_DECOMP");
        } else if (childIds.size() == 1) {
            BigraphEntity.NodeEntity<DynamicControl> chainRoot = addNode(parent, control, nodeId + "_DECOMP");
            chainIds.add(nodeId + "_DECOMP");
            bigraph.moveNode(getById(childIds.get(0)), chainRoot);
        } else {
            BigraphEntity<?> currentParent = parent;
            for (int i = 0; i < childIds.size() - 1; i++) {
                String chainId = i == 0 ? nodeId + "_DECOMP" : nodeId + "_DECOMP_" + i;
                BigraphEntity.NodeEntity<DynamicControl> chainNode = addNode(currentParent, control, chainId);
                chainIds.add(chainId);
                bigraph.moveNode(getById(childIds.get(i)), chainNode);
                currentParent = chainNode;
            }
            bigraph.moveNode(getById(childIds.get(childIds.size() - 1)),
                    (BigraphEntity.NodeEntity<DynamicControl>) currentParent);
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
                    bigraph.moveNode((BigraphEntity.NodeEntity<DynamicControl>) n, container);
                }
            });
            bigraph.removeNode(chainNode);
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

    /** Re-targets the mirrored node's place-edge to its new parent's mirrored GNode. */
    private void mirrorReparent(BigraphEntity.NodeEntity<DynamicControl> entity,
                                BigraphEntity.NodeEntity<DynamicControl> newParent) {
        view().ifPresent(view -> view.getGModelIdForEntity(entity).ifPresent(childViewId ->
                view.getGModelIdForEntity(newParent).ifPresent(parentViewId ->
                        findPlaceEdgeTo(view, childViewId).ifPresent(edge -> edge.setSourceId(parentViewId)))));
    }

    private void mirrorRelationEdge(BigraphEntity.Edge edge, Collection<BigraphEntity<?>> points) {
        view().ifPresent(view -> view.onAddEdge(edge, points));
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

    /** The "bigraph:place-edge" GEdge rendering this mirrored node's parent/child link, if any. */
    private Optional<GEdge> findPlaceEdgeTo(final BigraphView view, final String childViewId) {
        return view.getOwnerRoot().getChildren().stream()
                .filter(GEdge.class::isInstance)
                .map(GEdge.class::cast)
                .filter(edge -> "bigraph:place-edge".equals(edge.getType()) && childViewId.equals(edge.getTargetId()))
                .findFirst();
    }
}