package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEventListener;
import org.eclipse.glsp.graph.*;


@Singleton
public class POPPGModelSynchronizer implements POPPEventListener {
    private final POPPExtensionContext context;
    private final POPPGModelFactory factory;

    @Inject
    public POPPGModelSynchronizer(POPPExtensionContext context) {
        this.context = context;
        this.factory = new POPPGModelFactory();
        context.getOwnState().getPoppModel().addListener(this);
    }

    public POPPGModel getGModel() {
        return context.getOwnState().getGModel();
    }

    @Override
    public void onPOPPEvent(POPPEvent event) {
        switch (event) {
            case POPPEvent.NodeCreated e -> onNodeCreated(e);
            case POPPEvent.NodeDescriptionChanged e -> onNodeDescriptionChanged(e);
            case POPPEvent.NodeMoved e -> onNodeMoved(e);
            case POPPEvent.NodeDecompositionTypeChanged e -> onNodeDecompositionTypeChanged(e);
            case POPPEvent.NodeRemoved e -> onNodeRemoved(e);
            case POPPEvent.NodeChangeParent e -> onNodeChangeParent(e);
            case POPPEvent.RelationCreated e -> onRelationCreated(e);
            case POPPEvent.RelationRemoved e -> onRelationRemoved(e);
            default -> {}
        }
    }

    private void onNodeCreated(POPPEvent.NodeCreated e) {
        GNode newNode = factory.createTreeNode(e.node());
        getGModel().addNode(newNode.getId(), newNode);
    }

    private void onNodeDescriptionChanged(POPPEvent.NodeDescriptionChanged e) {
        GNode editedNode = getGModel().getNode(e.node().getId());

        GModelElement descriptionElement = editedNode.getChildren().getFirst();
        if (POPPGModelType.NODE_DESCRIPTION.toString().equals(descriptionElement.getType()) && descriptionElement instanceof GLabel label) {
            label.setText(e.node().getDescription());
        }
    }

    private void onNodeMoved(POPPEvent.NodeMoved e) {
        GNode editedNode = getGModel().getNode(e.node().getId());
        editedNode.setPosition(factory.point(e.node().getX(), e.node().getY()));
    }

    private void onNodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {
        GNode editedNode = getGModel().getNode(e.node().getId());
        editedNode.getArgs().put("decomposition_type", e.node().getDecompositionType().toString());
    }

    private void onNodeRemoved(POPPEvent.NodeRemoved e) {
        context.getOwnState().getGModel().removeNode(e.node().getId());
    }

    public void onNodeChangeParent(POPPEvent.NodeChangeParent e) {
        GEdge decompositionEdge = factory.createDecompositionEdge(e.newParent(), e.node());
        getGModel().getRoot().getChildren().add(decompositionEdge);
        getGModel().getRoot().getChildren().stream()
                .filter(element -> element.getId().equals(e.oldParent().getId() + "_decomposes_" + e.node().getId()))
                .findFirst().ifPresent(oldEdge -> getGModel().getRoot().getChildren().remove(oldEdge));
    }

    private void onRelationCreated(POPPEvent.RelationCreated e) {
        GEdge relationEdge = factory.createRelationEdge(e.relation());
        getGModel().getRoot().getChildren().add(relationEdge);
    }

    private void onRelationRemoved(POPPEvent.RelationRemoved e) {
        getGModel().getRoot().getChildren().stream()
                .filter(element -> element.getId().equals(e.source().getId() + "_" + e.type().name().toLowerCase() + "_" + e.target().getId()))
                .findFirst().ifPresent(oldEdge -> getGModel().getRoot().getChildren().remove(oldEdge));
    }
}
