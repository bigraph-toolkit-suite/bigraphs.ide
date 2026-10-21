package org.eclipse.glsp.example.bigraph.extension.popp.types;

/**
 * Dispatches each event type to its own method; all are no-ops by default, so a subclass overrides only the event
 * types it handles.
 */
public abstract class POPPSpecificEventListener implements POPPEventListener {
    @Override
    public void onPOPPEvent(POPPEvent event) {
        // If language standard is updated to 21 replace with switch casting
        if (event instanceof POPPEvent.NodeCreated e) onNodeCreated(e);
        else if (event instanceof POPPEvent.NodeDescriptionChanged e) onNodeDescriptionChanged(e);
        else if (event instanceof POPPEvent.NodeMoved e) onNodeMoved(e);
        else if (event instanceof POPPEvent.NodeDecompositionTypeChanged e) onNodeDecompositionTypeChanged(e);
        else if (event instanceof POPPEvent.NodeRemoved e) onNodeRemoved(e);
        else if (event instanceof POPPEvent.NodeChangedParent e) onNodeChangedParent(e);
        else if (event instanceof POPPEvent.RelationCreated e) onRelationCreated(e);
        else if (event instanceof POPPEvent.RelationRemoved e) onRelationRemoved(e);
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
