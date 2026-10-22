package org.eclipse.glsp.example.bigraph.extension.popp.sync;

import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModel;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelFactory;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eclipse.glsp.example.bigraph.extension.popp.state.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.types.POPPEventListener;
import org.eclipse.glsp.graph.*;

/** Keeps the GModel the user interacts with in sync with the domain model, by applying its events. */
@Singleton
public class POPPGModelSynchronizer implements POPPEventListener {
    private final POPPExtensionContext context;
    private final POPPGModelFactory factory;

    @Inject
    public POPPGModelSynchronizer(POPPExtensionContext context) {
        this.context = context;
        this.factory = new POPPGModelFactory();
    }

    public POPPGModel getGModel() {
        return context.getOwnState().getGModel();
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
        GNode newNode = factory.createTreeNode(e.node());
        getGModel().addNode(newNode.getId(), newNode);
    }

    private void nodeDescriptionChanged(POPPEvent.NodeDescriptionChanged e) {
        GNode editedNode = getGModel().getNode(e.node().getId());
        factory.applyLayout(editedNode, e.node());
    }

    private void nodeMoved(POPPEvent.NodeMoved e) {
        GNode editedNode = getGModel().getNode(e.node().getId());
        factory.applyLayout(editedNode, e.node());
    }

    private void nodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {
        GNode editedNode = getGModel().getNode(e.node().getId());
        editedNode.getArgs().put("decomposition_type", e.node().getDecompositionType().toString());
        factory.applyLayout(editedNode, e.node());
    }

    private void nodeRemoved(POPPEvent.NodeRemoved e) {
        context.getOwnState().getGModel().removeNode(e.node().getId());
    }

    private void nodeChangedParent(POPPEvent.NodeChangedParent e) {
        if (e.oldParent() != null) {
            getGModel().getRoot().getChildren().stream()
                    .filter(element -> element.getId().equals(factory.toDecompositionEdgeId(e.oldParent(), e.node())))
                    .findFirst().ifPresent(oldEdge -> getGModel().getRoot().getChildren().remove(oldEdge));
        }

        if (e.newParent() != null) {
            GEdge decompositionEdge = factory.createDecompositionEdge(e.newParent(), e.node());
            getGModel().getRoot().getChildren().add(decompositionEdge);
        }
    }

    private void relationCreated(POPPEvent.RelationCreated e) {
        GEdge relationEdge = factory.createRelationEdge(e.relation());
        getGModel().getRoot().getChildren().add(relationEdge);
    }

    private void relationRemoved(POPPEvent.RelationRemoved e) {
        getGModel().getRoot().getChildren().stream()
                .filter(element -> element.getId().equals(factory.toRelationEdgeId(e.relation())))
                .findFirst().ifPresent(oldEdge -> getGModel().getRoot().getChildren().remove(oldEdge));
    }
}
