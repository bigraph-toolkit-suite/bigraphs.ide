package org.eclipse.glsp.example.bigraph.extension.popp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Responsible to serialize data which is not saved in the default xmi file
 */
public record POPPExtensionMeta(Map<String, NodeData> nodes) {
    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Persisted state of one node. {@code properties} carries subtype-specific state (e.g. a proof's status)
     * exactly as {@link TreeNode#exportProperties()} produced it, so adding state to a node kind needs no
     * change here. Files written before {@code properties} existed load with an empty map.
     */
    public record NodeData(double x, double y, String description, Map<String, String> properties) {
        public NodeData {
            properties = properties != null ? Map.copyOf(properties) : Map.of();
        }

        public static NodeData extractInfo(TreeNode<?> node) {
            return new NodeData(node.getX(), node.getY(), node.getDescription(), node.exportProperties());
        }

        /** Applies this data to a freshly loaded node, going through the node's own setters so events fire. */
        public void restoreInfo(TreeNode<?> node) {
            if (description != null) {
                node.setDescription(description);
            }
            node.move(x, y);
            node.importProperties(properties);
        }
    }

    public POPPExtensionMeta(Map<String, NodeData> nodes) {
        this.nodes = nodes != null ? nodes : new HashMap<>();
    }

    public static POPPExtensionMeta fromJson(String json) {
        if (json == null || json.isBlank()) return new POPPExtensionMeta(new HashMap<>());
        Payload p = GSON.fromJson(json, Payload.class);
        return new POPPExtensionMeta(p != null && p.nodes != null ? p.nodes : new HashMap<>());
    }

    public String toJson() {
        Payload p = new Payload();
        p.nodes = nodes;
        return GSON.toJson(p);
    }

    private static final class Payload {
        Map<String, NodeData> nodes;
    }
}
