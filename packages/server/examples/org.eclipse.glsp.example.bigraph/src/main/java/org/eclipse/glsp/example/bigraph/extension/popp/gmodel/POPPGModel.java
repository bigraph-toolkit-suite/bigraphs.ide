package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtension;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPExtensionState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.graph.GNode;

import java.util.HashMap;
import java.util.Map;

public class POPPGModel {
    private final POPPExtensionState state;
    private final Map<String, GNode> treeNodes = new HashMap<>();

    public POPPGModel(final POPPExtensionState state) {
        this.state = state;
    }

    public GModelRoot getRoot() {
        if (state.getBigraphModelState() instanceof BigraphModelState bigraphModelState) {
            return bigraphModelState.getVariantRoot(POPPExtension.POPP_VARIANT_ID);
        }
        return null;
    }


    public void addNode(String id, GNode node) {
        getRoot().getChildren().add(node);
        treeNodes.put(id, node);
    }

    public void removeNode(String id) {
        getRoot().getChildren().remove(treeNodes.get(id));
        treeNodes.remove(id);
    }

    public GNode getNode(String id) {
        return treeNodes.get(id);
    }
}
