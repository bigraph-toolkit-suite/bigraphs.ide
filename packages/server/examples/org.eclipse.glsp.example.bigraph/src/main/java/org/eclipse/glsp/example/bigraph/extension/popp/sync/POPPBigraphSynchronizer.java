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
                context.getBigraphModelState() instanceof BigraphModelState modelState
                        ? Optional.ofNullable(modelState.getActiveView())
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
        if (event instanceof POPPEvent.NodeCreated created) nodeCreated(created);
        else if (event instanceof POPPEvent.NodeDescriptionChanged descriptionChanged) nodeDescriptionChanged(descriptionChanged);
        else if (event instanceof POPPEvent.NodeMoved moved) nodeMoved(moved);
        else if (event instanceof POPPEvent.NodeDecompositionTypeChanged typeChanged) nodeDecompositionTypeChanged(typeChanged);
        else if (event instanceof POPPEvent.NodeRemoved removed) nodeRemoved(removed);
        else if (event instanceof POPPEvent.NodeChangedParent parentChanged) nodeChangedParent(parentChanged);
        else if (event instanceof POPPEvent.RelationCreated relationCreated) relationCreated(relationCreated);
        else if (event instanceof POPPEvent.RelationRemoved relationRemoved) relationRemoved(relationRemoved);
        else throw new IllegalStateException("Unhandled POPPEvent: " + event);
    }

    private void nodeCreated(POPPEvent.NodeCreated created) {
        getPoppBigraph().addTreeNode(created.node());
        LOGGER.info("ADDED BIGRAPH NODE");
    }

    private void nodeDescriptionChanged(POPPEvent.NodeDescriptionChanged descriptionChanged) {
        getPoppBigraph().updateDescription(descriptionChanged.node().getId(), descriptionChanged.node().getDescription());
    }

    private void nodeMoved(POPPEvent.NodeMoved moved) {
        getPoppBigraph().updatePosition(moved.node().getId(), moved.node().getX(), moved.node().getY());
    }

    private void nodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged typeChanged) {
        getPoppBigraph().rebuildDecomposition(typeChanged.node());
    }

    private void nodeRemoved(POPPEvent.NodeRemoved removed) {
        getPoppBigraph().deleteTreeNode(removed.node().getId());
    }

    private void nodeChangedParent(POPPEvent.NodeChangedParent parentChanged) {
        getPoppBigraph().reparentTreeNode(parentChanged.node(), parentChanged.oldParent(), parentChanged.newParent());
    }

    private void relationCreated(POPPEvent.RelationCreated relationCreated) {
        getPoppBigraph().createRelation(relationCreated.relation());
    }

    private void relationRemoved(POPPEvent.RelationRemoved relationRemoved) {
        getPoppBigraph().removeRelation(relationRemoved.relation());
    }
}