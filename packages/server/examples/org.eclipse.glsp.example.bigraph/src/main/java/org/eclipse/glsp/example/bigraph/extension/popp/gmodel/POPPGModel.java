package org.eclipse.glsp.example.bigraph.extension.popp.gmodel;

import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.graph.GNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** GModelRoot wrapper with a domain id to gmodel lookup table. */
public class POPPGModel {
    private final Supplier<GModelRoot> root;
    private final Map<String, GNode> treeNodes = new HashMap<>();

    /** {@code root}: the POPP variant's root in the diagram's model state, read on every access. */
    public POPPGModel(final Supplier<GModelRoot> root) {
        this.root = root;
    }

    public GModelRoot getRoot() {
        return root.get();
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
