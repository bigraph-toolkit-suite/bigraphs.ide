package org.eclipse.glsp.example.bigraph.extension.popp.event;

/** Simple helper to split each event type into its own handler method.
 *  If only one event type should be handled, only this method needs to be overridden by the subclass. */
public abstract class POPPSpecificEventListener implements POPPEventListener {
    @Override
    public void onPOPPEvent(POPPEvent event) {
        switch (event) {
            case POPPEvent.NodeCreated e -> onNodeCreated(e);
            case POPPEvent.NodeDescriptionChanged e -> onNodeDescriptionChanged(e);
            case POPPEvent.NodeMoved e -> onNodeMoved(e);
            case POPPEvent.NodeDecompositionTypeChanged e -> onNodeDecompositionTypeChanged(e);
            case POPPEvent.NodeRemoved e -> onNodeRemoved(e);
            case POPPEvent.NodeChangedParent e -> onNodeChangedParent(e);
            case POPPEvent.RelationCreated e -> onRelationCreated(e);
            case POPPEvent.RelationRemoved e -> onRelationRemoved(e);
            default -> {}
        }
    }

    protected void onNodeCreated(POPPEvent.NodeCreated e) {}
    protected void onNodeDescriptionChanged(POPPEvent.NodeDescriptionChanged e) {}
    protected void onNodeMoved(POPPEvent.NodeMoved e) {}
    protected void onNodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {}
    protected void onNodeRemoved(POPPEvent.NodeRemoved e) {}
    protected void onNodeChangedParent(POPPEvent.NodeChangedParent e) {}
    protected void onRelationCreated(POPPEvent.RelationCreated e) {}
    protected void onRelationRemoved(POPPEvent.RelationRemoved e) {}
}
