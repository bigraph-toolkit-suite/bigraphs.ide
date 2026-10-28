package org.eclipse.glsp.example.bigraph.extension.popp.sync;

import org.eclipse.glsp.example.bigraph.extension.popp.bigraph.POPPBigraph;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.example.bigraph.extension.popp.state.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.state.POPPExtensionState;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPEventListener;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;

import java.util.Optional;

/**
 * Reacts to {@link POPPEvent}s and forwards each one to the matching
 * {@link POPPBigraph} operation. All bigraph shape/mutation logic lives in {@link POPPBigraph}.
 * This class only decides which operation to call for which event.
 */
@Singleton
public class POPPBigraphSynchronizer implements POPPEventListener {
    private static final Logger LOGGER = LogManager.getLogger(POPPBigraphSynchronizer.class);

    private final POPPExtensionState state;

    @Inject
    public POPPBigraphSynchronizer(final POPPExtensionContext context) {
        this.state = context.getOwnState();

        PureBigraphMutable bigraph = context.getBigraphModelState().getMutableBigraph();
        state.setPoppBigraph(new POPPBigraph(bigraph, bigraph.getSignature()));
        state.getPoppBigraph().addListener(new POPPBigraphViewMirror(() ->
                context.getBigraphModelState() instanceof BigraphModelState s
                        ? Optional.ofNullable(s.getActiveView())
                        : Optional.empty()));
    }

    public DynamicSignature getSignature() {
        return getPoppBigraph().getSignature();
    }

    public PureBigraphMutable getBigraph() {
        return getPoppBigraph().getBigraph();
    }

    public POPPBigraph getPoppBigraph() {
        return state.getPoppBigraph();
    }

    @Override
    public void onPOPPEvent(final POPPEvent event) {
        // If language standard is updated to 21 replace with switch casting
        if (event instanceof POPPEvent.NodeCreated e) nodeCreated(e);
        else if (event instanceof POPPEvent.NodeDescriptionChanged e) nodeDescriptionChanged(e);
        else if (event instanceof POPPEvent.NodeMoved e) nodeMoved(e);
        else if (event instanceof POPPEvent.NodeDecompositionTypeChanged e) nodeDecompositionTypeChanged(e);
        else if (event instanceof POPPEvent.NodeRemoved e) nodeRemoved(e);
        else if (event instanceof POPPEvent.NodeChangedParent e) nodeChangedParent(e);
        else if (event instanceof POPPEvent.RelationCreated e) relationCreated(e);
        else if (event instanceof POPPEvent.RelationRemoved e) relationRemoved(e);
        else throw new IllegalStateException("Unhandled POPPEvent: " + event);
    }

    private void nodeCreated(POPPEvent.NodeCreated e) {
        getPoppBigraph().addTreeNode(e.node());
        LOGGER.info("ADDED BIGRAPH NODE");
    }

    private void nodeDescriptionChanged(POPPEvent.NodeDescriptionChanged e) {
        getPoppBigraph().updateDescription(e.node().getId(), e.node().getDescription());
    }

    private void nodeMoved(POPPEvent.NodeMoved e) {
        getPoppBigraph().updatePosition(e.node().getId(), e.node().getX(), e.node().getY());
    }

    private void nodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {
        getPoppBigraph().rebuildDecomposition(e.node());
    }

    private void nodeRemoved(POPPEvent.NodeRemoved e) {
        getPoppBigraph().deleteTreeNode(e.node().getId());
    }

    private void nodeChangedParent(POPPEvent.NodeChangedParent e) {
        getPoppBigraph().reparentTreeNode(e.node(), e.oldParent(), e.newParent());
    }

    private void relationCreated(POPPEvent.RelationCreated e) {
        getPoppBigraph().createRelation(e.relation());
    }

    private void relationRemoved(POPPEvent.RelationRemoved e) {
        getPoppBigraph().removeRelation(e.relation());
    }
}