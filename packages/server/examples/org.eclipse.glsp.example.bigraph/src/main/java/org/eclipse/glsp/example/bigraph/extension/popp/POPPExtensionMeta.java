package org.eclipse.glsp.example.bigraph.extension.popp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.HashMap;
import java.util.Map;

/** Responsible to serialize data which is not saved in the default xmi file */
public final class POPPExtensionMeta {
    private static final Gson GSON = new GsonBuilder().create();

    public record NodeData(double x, double y, String description) {}

    private final Map<String, NodeData> nodes;

    public POPPExtensionMeta(Map<String, NodeData> nodes) {
        this.nodes = nodes != null ? nodes : new HashMap<>();
    }

    public Map<String, NodeData> getNodes() { return nodes; }

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

    private static final class Payload { Map<String, NodeData> nodes; }
}
