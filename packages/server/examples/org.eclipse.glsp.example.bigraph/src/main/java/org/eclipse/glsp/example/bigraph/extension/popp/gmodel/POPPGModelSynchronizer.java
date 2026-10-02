package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionContext;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPSpecificEventListener;
import org.eclipse.glsp.graph.*;


@Singleton
public class POPPGModelSynchronizer extends POPPSpecificEventListener {
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
    protected void onNodeCreated(POPPEvent.NodeCreated e) {
        GNode newNode = factory.createTreeNode(e.node());
        getGModel().addNode(newNode.getId(), newNode);
    }

    @Override
    protected void onNodeDescriptionChanged(POPPEvent.NodeDescriptionChanged e) {
        GNode editedNode = getGModel().getNode(e.node().getId());

        GModelElement descriptionElement = editedNode.getChildren().getFirst();
        if (POPPGModelType.NODE_DESCRIPTION.toString().equals(descriptionElement.getType()) && descriptionElement instanceof GLabel label) {
            label.setText(e.node().getDescription());
        }
    }

    @Override
    protected void onNodeMoved(POPPEvent.NodeMoved e) {
        GNode editedNode = getGModel().getNode(e.node().getId());
        editedNode.setPosition(factory.point(e.node().getX(), e.node().getY()));
    }

    @Override
    protected void onNodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged e) {
        GNode editedNode = getGModel().getNode(e.node().getId());
        editedNode.getArgs().put("decomposition_type", e.node().getDecompositionType().toString());
    }

    @Override
    protected void onNodeRemoved(POPPEvent.NodeRemoved e) {
        context.getOwnState().getGModel().removeNode(e.node().getId());
    }

    @Override
    protected void onNodeChangedParent(POPPEvent.NodeChangedParent e) {
        if (e.oldParent() != null) {
            getGModel().getRoot().getChildren().stream()
                    .filter(element -> element.getId().equals(e.oldParent().getId() + "_decomposes_" + e.node().getId()))
                    .findFirst().ifPresent(oldEdge -> getGModel().getRoot().getChildren().remove(oldEdge));
        }

        if (e.newParent() != null) {
            GEdge decompositionEdge = factory.createDecompositionEdge(e.newParent(), e.node());
            getGModel().getRoot().getChildren().add(decompositionEdge);
        }
    }

    @Override
    protected void onRelationCreated(POPPEvent.RelationCreated e) {
        GEdge relationEdge = factory.createRelationEdge(e.relation());
        getGModel().getRoot().getChildren().add(relationEdge);
    }

    @Override
    protected void onRelationRemoved(POPPEvent.RelationRemoved e) {
        getGModel().getRoot().getChildren().stream()
                .filter(element -> element.getId().equals(
                        e.relation().source().getId() + "_"
                        + e.relation().type().name().toLowerCase() + "_"
                        + e.relation().target().getId()))
                .findFirst().ifPresent(oldEdge -> getGModel().getRoot().getChildren().remove(oldEdge));
    }
}
