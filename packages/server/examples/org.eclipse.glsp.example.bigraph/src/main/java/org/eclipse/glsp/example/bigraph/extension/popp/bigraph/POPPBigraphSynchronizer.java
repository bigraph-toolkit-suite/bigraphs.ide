package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEventListener;

public class POPPBigraphSynchronizer implements POPPEventListener {
    @Override
    public void onPOPPEvent(POPPEvent event) {
        switch (event) {
            case POPPEvent.NodeCreated e -> onNodeCreated(e);
            case POPPEvent.NodeDescriptionChanged e -> onNodeDescriptionChanged(e);
            case POPPEvent.NodeMoved e -> onNodeMoved(e);
            case POPPEvent.NodeDecompositionTypeChanged e -> onNodeDecompositionTypeChanged(e);
            case POPPEvent.NodeRemoved e -> onNodeRemoved(e);
            case POPPEvent.RelationCreated e -> onRelationCreated(e);
            case POPPEvent.RelationRemoved e -> onRelationRemoved(e);
            default -> {}
        }
    }

    private void onNodeCreated(POPPEvent.NodeCreated e) {}
    private void onNodeDescriptionChanged(POPPEvent.NodeDescriptionChanged e) {}
    private void onNodeMoved(POPPEvent.NodeMoved e) {}
    private void onNodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {}
    private void onNodeRemoved(POPPEvent.NodeRemoved e) {}
    private void onRelationCreated(POPPEvent.RelationCreated e) {}
    private void onRelationRemoved(POPPEvent.RelationRemoved e) {}
}
