package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPSpecificEventListener;

public class POPPBigraphSynchronizer extends POPPSpecificEventListener {
    @Override
    protected void onNodeCreated(POPPEvent.NodeCreated e) {}

    @Override
    protected void onNodeDescriptionChanged(POPPEvent.NodeDescriptionChanged e) {}

    @Override
    protected void onNodeMoved(POPPEvent.NodeMoved e) {}

    @Override
    protected void onNodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {}

    @Override
    protected void onNodeRemoved(POPPEvent.NodeRemoved e) {}

    @Override
    protected void onNodeChangedParent(POPPEvent.NodeChangedParent e) {}

    @Override
    protected void onRelationCreated(POPPEvent.RelationCreated e) {}

    @Override
    protected void onRelationRemoved(POPPEvent.RelationRemoved e) {}
}
