package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPSpecificEventListener;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;

import java.util.Optional;

/**
 * Reacts to {@link POPPEvent}s and forwards each one to the matching
 * {@link POPPBigraph} operation. All bigraph shape/mutation logic — including
 * keeping the core bigraph view mirrored — lives in {@link POPPBigraph}; this
 * class only decides which operation to call for which event.
 */
@Singleton
public class POPPBigraphSynchronizer extends POPPSpecificEventListener {
    private final POPPBigraph poppBigraph;

    @Inject
    public POPPBigraphSynchronizer(final POPPExtensionContext context) {
        PureBigraphMutable bigraph = context.getBigraphModelState().getMutableBigraph();
        this.poppBigraph = new POPPBigraph(bigraph, bigraph.getSignature(), () ->
                context.getBigraphModelState() instanceof BigraphModelState bigraphModelState
                        ? Optional.ofNullable(bigraphModelState.getActiveView())
                        : Optional.empty());
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
        getPoppBigraph().updateDescription(e.node().getId(), e.node().getDescription());
    }

    @Override
    protected void onNodeMoved(POPPEvent.NodeMoved e) {
        getPoppBigraph().updatePosition(e.node().getId(), e.node().getX(), e.node().getY());
    }

    @Override
    protected void onNodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {
        getPoppBigraph().rebuildDecomposition(e.node());
    }

    @Override
    protected void onNodeRemoved(POPPEvent.NodeRemoved e) {
        getPoppBigraph().deleteTreeNode(e.node().getId());
    }

    @Override
    protected void onNodeChangedParent(POPPEvent.NodeChangedParent e) {
        getPoppBigraph().reparentTreeNode(e.node(), e.oldParent(), e.newParent());
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