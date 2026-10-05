package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPSpecificEventListener;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;

import java.util.Optional;

/**
 * Reacts to {@link POPPEvent}s and forwards each one to the matching
 * {@link POPPBigraph} operation. All bigraph shape/mutation logic lives in {@link POPPBigraph};
 * this class only decides which operation to call for which event.
 */
@Singleton
public class POPPBigraphSynchronizer extends POPPSpecificEventListener {
    private static final Logger LOGGER = LogManager.getLogger(POPPBigraphSynchronizer.class);

    private final POPPBigraph poppBigraph;

    @Inject
    public POPPBigraphSynchronizer(final POPPExtensionContext context) {
        PureBigraphMutable bigraph = context.getBigraphModelState().getMutableBigraph();
        this.poppBigraph = new POPPBigraph(bigraph, bigraph.getSignature());
        this.poppBigraph.addObserver(new POPPBigraphViewMirror(() ->
                context.getBigraphModelState() instanceof BigraphModelState s
                        ? Optional.ofNullable(s.getActiveView())
                        : Optional.empty()));
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
        LOGGER.info("ADDED BIGRAPH NODE");
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
        getPoppBigraph().removeRelation(e.relation());
    }
}