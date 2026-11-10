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
        GNode newNode = factory.createTreeNode(created.node());
        getGModel().addNode(newNode.getId(), newNode);
    }

    private void nodeDescriptionChanged(POPPEvent.NodeDescriptionChanged descriptionChanged) {
        GNode editedNode = getGModel().getNode(descriptionChanged.node().getId());
        factory.applyLayout(editedNode, descriptionChanged.node());
    }

    private void nodeMoved(POPPEvent.NodeMoved moved) {
        GNode editedNode = getGModel().getNode(moved.node().getId());
        factory.applyLayout(editedNode, moved.node());
    }

    private void nodeDecompositionTypeChanged(POPPEvent.NodeDecompositionTypeChanged typeChanged) {
        GNode editedNode = getGModel().getNode(typeChanged.node().getId());
        editedNode.getArgs().put("decomposition_type", typeChanged.node().getDecompositionType().toString());
        factory.applyLayout(editedNode, typeChanged.node());
    }

    private void nodeRemoved(POPPEvent.NodeRemoved removed) {
        context.getOwnState().getGModel().removeNode(removed.node().getId());
    }

    private void nodeChangedParent(POPPEvent.NodeChangedParent parentChanged) {
        if (parentChanged.oldParent() != null) {
            getGModel().getRoot().getChildren().stream()
                    .filter(element -> element.getId().equals(factory.toDecompositionEdgeId(parentChanged.oldParent(), parentChanged.node())))
                    .findFirst().ifPresent(oldEdge -> getGModel().getRoot().getChildren().remove(oldEdge));
        }

        if (parentChanged.newParent() != null) {
            GEdge decompositionEdge = factory.createDecompositionEdge(parentChanged.newParent(), parentChanged.node());
            getGModel().getRoot().getChildren().add(decompositionEdge);
        }
    }

    private void relationCreated(POPPEvent.RelationCreated relationCreated) {
        GEdge relationEdge = factory.createRelationEdge(relationCreated.relation());
        getGModel().getRoot().getChildren().add(relationEdge);
    }

    private void relationRemoved(POPPEvent.RelationRemoved relationRemoved) {
        getGModel().getRoot().getChildren().stream()
                .filter(element -> element.getId().equals(factory.toRelationEdgeId(relationRemoved.relation())))
                .findFirst().ifPresent(oldEdge -> getGModel().getRoot().getChildren().remove(oldEdge));
    }
}
