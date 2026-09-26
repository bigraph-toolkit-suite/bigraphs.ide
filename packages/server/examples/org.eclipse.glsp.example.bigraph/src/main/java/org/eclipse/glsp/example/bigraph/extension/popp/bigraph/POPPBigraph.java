package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.Relation;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stateful wrapper around a {@link PureBigraphMutable} that owns all knowledge
 * of how the POPP domain model's shape is encoded in it: the POPP container,
 * binary AND/OR decomposition chains, and relation stub/edge encoding. Both
 * {@link POPPBigraphSynchronizer} (write) and {@code POPPBigraphLoader} (read)
 * go through one instance of this class for every bigraph operation, so the
 * two directions can't drift apart.
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

    private final Map<String, BigraphEntity.NodeEntity<DynamicControl>> nodesById = new HashMap<>();
    private final Map<String, List<String>> decompChainIds = new HashMap<>();

    public POPPBigraph(PureBigraphMutable bigraph, DynamicSignature signature) {
        this.bigraph = bigraph;
        this.signature = signature;
    }

    public POPPBigraph(PureBigraphMutable bigraph) {
        this(bigraph, null);
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
        BigraphEntity.NodeEntity<DynamicControl> entity = addNode(getOrCreatePOPPContainer(), POPPBigraphSignature.getByNodeKind(node.getKind()), node.getId());
        rebuildDecomposition(node);
        entity.getAttributes().put("description", node.getDescription());
        entity.getAttributes().put("x", node.getX());
        entity.getAttributes().put("y", node.getY());
        return entity;
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
        return edge;
    }

    public void removeRelation(RelationType type, String sourceId, String targetId) {
        String edgeName = type + ":" + sourceId + "->" + targetId;
        BigraphEntity.Edge edge = bigraph.getEdges().stream()
                .filter(e -> e.getName().equals(edgeName))
                .findFirst()
                .orElseThrow();
        List<BigraphEntity<?>> stubs = bigraph.getPointsFromLink(edge);
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
}