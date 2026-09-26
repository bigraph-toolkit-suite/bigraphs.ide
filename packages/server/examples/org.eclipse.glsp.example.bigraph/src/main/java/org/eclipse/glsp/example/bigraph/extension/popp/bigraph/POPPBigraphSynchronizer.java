package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPSpecificEventListener;
import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;

/**
 * Reacts to {@link POPPEvent}s and keeps a bigraph in sync with the domain
 * model. All bigraph shape/mutation logic lives in {@link POPPBigraph}, this
 * class only decides, per event, which {@link POPPBigraph} operations to call.
 */
public class POPPBigraphSynchronizer extends POPPSpecificEventListener {
    private final POPPBigraph poppBigraph;

    public POPPBigraphSynchronizer(final PureBigraphMutable bigraph, final DynamicSignature signature) {
        this.poppBigraph = new POPPBigraph(bigraph, signature);
    }

    public DynamicSignature getSignature() {
        return poppBigraph.getSignature();
    }

    public PureBigraphMutable getBigraph() {
        return poppBigraph.getBigraph();
    }

    public POPPBigraph getPoppBigraph() {
        return poppBigraph;
    }

    @Override
    protected void onNodeCreated(POPPEvent.NodeCreated e) {
        getPoppBigraph().addTreeNode(e.node());
    }

    @Override
    protected void onNodeDescriptionChanged(POPPEvent.NodeDescriptionChanged e) {
        getPoppBigraph().getById(e.node().getId()).getAttributes().put("description", e.node().getDescription());
    }

    @Override
    protected void onNodeMoved(POPPEvent.NodeMoved e) {
        BigraphEntity.NodeEntity<DynamicControl> entity = getPoppBigraph().getById(e.node().getId());
        entity.getAttributes().put("x", e.node().getX());
        entity.getAttributes().put("y", e.node().getY());
    }

    @Override
    protected void onNodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {
        getPoppBigraph().rebuildDecomposition(e.node());
    }

    @Override
    protected void onNodeRemoved(POPPEvent.NodeRemoved e) {
        getPoppBigraph().clearDecompositionChain(e.node().getId());
        getPoppBigraph().removeNode(e.node().getId());
    }

    @Override
    protected void onNodeChangedParent(POPPEvent.NodeChangedParent e) {
        BigraphEntity.NodeEntity<DynamicControl> entity = getPoppBigraph().getById(e.node().getId());
        getBigraph().moveNode(entity, getPoppBigraph().getOrCreatePOPPContainer());

        if (e.oldParent() != null) {
            getPoppBigraph().rebuildDecomposition(e.oldParent(), e.node().getId());
        }

        if (e.newParent() == null) {
            return;
        }

        if (e.newParent().getDecompositionType() != DecompositionType.NONE) {
            getPoppBigraph().rebuildDecomposition(e.newParent());
        } else {
            getBigraph().moveNode(entity, getPoppBigraph().getById(e.newParent().getId()));
        }
    }

    @Override
    protected void onRelationCreated(POPPEvent.RelationCreated e) {
        getPoppBigraph().createRelation(e.relation());
    }

    @Override
    protected void onRelationRemoved(POPPEvent.RelationRemoved e) {
        getPoppBigraph().removeRelation(e.type(), e.source().getId(), e.target().getId());
    }
}