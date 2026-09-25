package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPSpecificEventListener;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
public class POPPBigraphSynchronizer extends POPPSpecificEventListener {
    private final PureBigraphMutable bigraph;
    private final DynamicSignature signature;

    private final Map<String, BigraphEntity.NodeEntity<DynamicControl>> nodesById = new HashMap<>();

    public POPPBigraphSynchronizer(final PureBigraphMutable bigraph, final DynamicSignature signature) {
        this.bigraph = bigraph;
        this.signature = signature;
    }

    public DynamicSignature getSignature() {
        return signature;
    }

    public PureBigraphMutable getBigraph() {
        return bigraph;
    }

    public BigraphEntity.RootEntity getDefaultRoot(){
        if (bigraph.getRoots().isEmpty()) {
            return bigraph.addRoot();
        }

        return bigraph.getRoots().getFirst();
    }

    public BigraphEntity.NodeEntity<DynamicControl> getPOPPContainer() {
        BigraphEntity.RootEntity root = getDefaultRoot();
        for (BigraphEntity<?> child : bigraph.getChildrenOf(root)) {
            if (child instanceof BigraphEntity.NodeEntity<?> node && POPPBigraphSignature.POPP.matches(node)) {
                return (BigraphEntity.NodeEntity<DynamicControl>) node;
            }
        }

        return addNode(root, POPPBigraphSignature.POPP, "POPP");
    }

    private BigraphEntity.NodeEntity<DynamicControl> addNode(final BigraphEntity<?> parent,
                                                             final POPPBigraphSignature control,
                                                             final String id) {
        DynamicControl ctrl = signature.getControlByName(control.controlName());
        BigraphEntity.NodeEntity<DynamicControl> entity = bigraph.addNode(parent, ctrl, id);
        nodesById.put(id, entity);
        return entity;
    }

    private void removeNode(final String id){
        bigraph.removeNode(nodesById.get(id));
        nodesById.remove(id);
    }

    public BigraphEntity.NodeEntity<DynamicControl> getById(String id) {
        return nodesById.get(id);
    }

    @Override
    protected void onNodeCreated(POPPEvent.NodeCreated e) {
        //TODO make decomposition binary for easier coverage checks
        BigraphEntity.NodeEntity<DynamicControl> entity = addNode(getPOPPContainer(), POPPBigraphSignature.getByNodeKind(e.kind()), e.node().getId());
        if (e.node().getDecompositionType() != DecompositionType.NONE) {
            addNode(entity, POPPBigraphSignature.getByDecompositionType(e.node().getDecompositionType()), e.node().getId() + "_DECOMP");
        }
        entity.getAttributes().put("description", e.node().getDescription());
        entity.getAttributes().put("x", e.node().getX());
        entity.getAttributes().put("y", e.node().getY());
    }

    @Override
    protected void onNodeDescriptionChanged(POPPEvent.NodeDescriptionChanged e) {
        getById(e.node().getId()).getAttributes().put("description", e.node().getDescription());
    }

    @Override
    protected void onNodeMoved(POPPEvent.NodeMoved e) {
        BigraphEntity.NodeEntity<DynamicControl> entity = getById(e.node().getId());
        entity.getAttributes().put("x", e.node().getX());
        entity.getAttributes().put("y", e.node().getY());
    }

    @Override
    protected void onNodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {
        //TODO make decomposition binary for easier coverage checks
        BigraphEntity.NodeEntity<DynamicControl> formerDecompNode = getById(e.node().getId() + "_DECOMP");
        if (e.node().getDecompositionType() != DecompositionType.NONE) {
            BigraphEntity.NodeEntity<DynamicControl> newDecompNode = addNode(getById(e.node().getId()), POPPBigraphSignature.getByDecompositionType(e.node().getDecompositionType()), e.node().getId() + "_DECOMP");

            if (formerDecompNode != null) {
                bigraph.getChildrenOf(formerDecompNode).forEach(child -> {
                    if (child instanceof BigraphEntity.NodeEntity<?>) bigraph.moveNode((BigraphEntity.NodeEntity<DynamicControl>) child, newDecompNode);
                });
            }
        }

        if (formerDecompNode != null) {
            bigraph.removeNode(formerDecompNode);
        }
    }

    @Override
    protected void onNodeRemoved(POPPEvent.NodeRemoved e) {
        removeNode(e.node().getId());
    }

    @Override
    protected void onNodeChangedParent(POPPEvent.NodeChangedParent e) {
        BigraphEntity.NodeEntity<DynamicControl> entity = getById(e.node().getId());
        BigraphEntity.NodeEntity<DynamicControl> parent = e.newParent() == null ? getPOPPContainer() : getById(e.newParent().getId());
        bigraph.moveNode(entity, parent);
    }

    @Override
    protected void onRelationCreated(POPPEvent.RelationCreated e) {
        BigraphEntity.NodeEntity<DynamicControl> source = getById(e.relation().source().getId());
        BigraphEntity.NodeEntity<DynamicControl> target = getById(e.relation().target().getId());
        POPPBigraphSignature sourceStubControl = POPPBigraphSignature.getSourceStub(e.relation());
        POPPBigraphSignature targetStubControl = POPPBigraphSignature.getTargetStub(e.relation());
        BigraphEntity.NodeEntity<DynamicControl> sourceStub = addNode(source, sourceStubControl, sourceStubControl + ":" + source.getName() + "->" + target.getName());
        BigraphEntity.NodeEntity<DynamicControl> targetStub = addNode(target, targetStubControl, targetStubControl + ":" + source.getName() + "->" + target.getName());
        BigraphEntity.Edge relationLink = bigraph.addEdge(e.relation().type() + ":" + source.getName() + "->" + target.getName());
        bigraph.connectNodeToLink(sourceStub, relationLink);
        bigraph.connectNodeToLink(targetStub, relationLink);
    }

    @Override
    protected void onRelationRemoved(POPPEvent.RelationRemoved e) {
        BigraphEntity.Edge relationEdge = bigraph.getEdges().stream()
                .filter(edge -> edge.getName().equals(e.type() + ":" + e.source().getId() + "->" + e.target().getId()))
                .findFirst().orElseThrow();
        List<BigraphEntity<?>> relationStubs = bigraph.getPointsFromLink(relationEdge);
        relationStubs.forEach(stub -> {
            if (stub instanceof BigraphEntity.NodeEntity<?> node) {
                bigraph.removeNode((BigraphEntity.NodeEntity<DynamicControl>) node);
            }
        });
        bigraph.removeEdge(relationEdge);
    }
}
