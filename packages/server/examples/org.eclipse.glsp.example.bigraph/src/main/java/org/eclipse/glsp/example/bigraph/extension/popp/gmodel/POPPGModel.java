package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.graph.GNode;

import java.util.HashMap;
import java.util.Map;

public class POPPGModel {
    private GModelRoot root;

    private final Map<String, GNode> treeNodes = new HashMap<>();

    public POPPGModel() {

    }

    public GModelRoot getRoot() {
        return root;
    }

    public void setRoot(GModelRoot root) {
        this.root = root;
    }

    public void addNode(String id, GNode node) {
        root.getChildren().add(node);
        treeNodes.put(id, node);
    }

    public void removeNode(String id) {
        root.getChildren().remove(treeNodes.get(id));
        treeNodes.remove(id);
    }

    public GNode getNode(String id) {
        return treeNodes.get(id);
    }
}
