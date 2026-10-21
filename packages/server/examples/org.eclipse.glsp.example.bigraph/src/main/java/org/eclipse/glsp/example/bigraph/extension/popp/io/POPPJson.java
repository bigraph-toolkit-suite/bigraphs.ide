package org.eclipse.glsp.example.bigraph.extension.popp.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.*;

/**
 * The JSON exchange format of POPP models (version 1), see {@code popp.schema.json} in the resources. It contains the
 * POPP metamodel and nothing else: the forest of homogeneous trees per node kind and the typed relations between
 * them. Layout positions are optional; coverage is not part of it (it is computed). Example:
 * <pre>{@code
 * {
 *   "format": "popp",
 *   "version": 1,
 *   "trees": {
 *     "problems": [
 *       { "id": "p1", "description": "Frequent bugs", "decomposition": "or",
 *         "layout": { "x": 0, "y": 0 },
 *         "children": [ { "id": "p2", "description": "No automated tests" } ] }
 *     ],
 *     "goals": [ { "id": "g1", "description": "Reliable releases" } ],
 *     "consequences": [], "solutions": [], "successCriteria": [], "successProofs": []
 *   },
 *   "relations": [ { "type": "inverts", "source": "p1", "target": "g1" } ]
 * }
 * }</pre>
 * Rules beyond the syntax: ids are unique across the whole model; {@code decomposition} ({@code and} or {@code or})
 * is given exactly for nodes with children (an inner node without it is decomposed with {@code and}); a node's
 * children are nodes of the same tree, so homogeneity follows from the nesting; a relation's {@code type} must be the
 * one the metamodel defines for the kinds of its source and target ({@link RelationType}).
 */
public final class POPPJson {
    public static final String FORMAT = "popp";
    public static final int VERSION = 1;

    /** The tree arrays by node kind, in the order they are written. */
    private static final Map<NodeKind, String> TREE_KEYS = new LinkedHashMap<>();
    static {
        TREE_KEYS.put(NodeKind.PROBLEM, "problems");
        TREE_KEYS.put(NodeKind.CONSEQUENCE, "consequences");
        TREE_KEYS.put(NodeKind.GOAL, "goals");
        TREE_KEYS.put(NodeKind.SUCCESS_CRITERIA, "successCriteria");
        TREE_KEYS.put(NodeKind.SOLUTION, "solutions");
        TREE_KEYS.put(NodeKind.SUCCESS_PROOF, "successProofs");
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private POPPJson() {}

    /** A file that is not a valid POPP JSON document; {@link #problems()} lists every problem found. */
    public static final class InvalidDocumentException extends RuntimeException {
        private final List<String> problems;

        InvalidDocumentException(List<String> problems) {
            super(problems.size() == 1 ? problems.getFirst()
                    : problems.size() + " problems, first: " + problems.getFirst());
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() { return problems; }
    }

    /** A parsed and checked document, ready to be applied to a model. */
    public record Document(List<Node> roots, List<Link> relations) {}

    public record Node(NodeKind kind, String id, String description, DecompositionType decomposition,
                       Double x, Double y, List<Node> children) {}

    public record Link(RelationType type, String source, String target) {}

    // ------------------------------------------------------------------------------------------------------------
    // Export

    /** Writes {@code model} as a POPP JSON document (with layout positions). */
    public static String write(POPPModel model) {
        JsonObject doc = new JsonObject();
        doc.addProperty("format", FORMAT);
        doc.addProperty("version", VERSION);
        JsonObject trees = new JsonObject();
        for (var entry : TREE_KEYS.entrySet()) {
            JsonArray array = new JsonArray();
            for (TreeNode<?> root : roots(model, entry.getKey())) array.add(node(root));
            trees.add(entry.getValue(), array);
        }
        doc.add("trees", trees);
        JsonArray relations = new JsonArray();
        // sorted, so the same model is always written the same way (the relation graph's order is not stable)
        List<Relation> sorted = new ArrayList<>(model.getRelations().all());
        sorted.sort(Comparator.comparing((Relation r) -> r.type().ordinal())
                .thenComparing(r -> r.source().getId()).thenComparing(r -> r.target().getId()));
        for (Relation r : sorted) {
            JsonObject o = new JsonObject();
            o.addProperty("type", r.type().name().toLowerCase(Locale.ROOT));
            o.addProperty("source", r.source().getId());
            o.addProperty("target", r.target().getId());
            relations.add(o);
        }
        doc.add("relations", relations);
        return GSON.toJson(doc) + "\n";
    }

    private static JsonObject node(TreeNode<?> n) {
        JsonObject o = new JsonObject();
        o.addProperty("id", n.getId());
        o.addProperty("description", n.getDescription() == null ? "" : n.getDescription());
        if (!n.getChildren().isEmpty()) {
            o.addProperty("decomposition", n.getDecompositionType().name().toLowerCase(Locale.ROOT));
        }
        JsonObject layout = new JsonObject();
        layout.addProperty("x", n.getX());
        layout.addProperty("y", n.getY());
        o.add("layout", layout);
        if (!n.getChildren().isEmpty()) {
            JsonArray children = new JsonArray();
            for (TreeNode<?> c : n.getChildren()) children.add(node(c));
            o.add("children", children);
        }
        return o;
    }

    private static List<? extends TreeNode<?>> roots(POPPModel model, NodeKind kind) {
        return switch (kind) {
            case PROBLEM -> model.getProblemRoots();
            case CONSEQUENCE -> model.getConsequenceRoots();
            case GOAL -> model.getGoalRoots();
            case SUCCESS_CRITERIA -> model.getSuccessCriteriaRoots();
            case SOLUTION -> model.getSolutionRoots();
            case SUCCESS_PROOF -> model.getSuccessProofRoots();
        };
    }

    // ------------------------------------------------------------------------------------------------------------
    // Import

    /** Parses and checks a POPP JSON document; throws {@link InvalidDocumentException} with every problem found. */
    public static Document read(String json) {
        List<String> problems = new ArrayList<>();
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new InvalidDocumentException(List.of("Not valid JSON: " + e.getMessage()));
        }
        if (!parsed.isJsonObject()) throw new InvalidDocumentException(List.of("The document must be a JSON object"));
        JsonObject doc = parsed.getAsJsonObject();

        if (!FORMAT.equals(string(doc, "format"))) problems.add("\"format\" must be \"" + FORMAT + "\"");
        JsonElement version = doc.get("version");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                || version.getAsDouble() != VERSION) {
            problems.add("\"version\" must be " + VERSION);
        }

        Map<String, NodeKind> kinds = new HashMap<>();
        List<Node> roots = new ArrayList<>();
        JsonElement trees = doc.get("trees");
        if (trees == null || !trees.isJsonObject()) {
            problems.add("\"trees\" must be an object with the arrays " + String.join(", ", TREE_KEYS.values()));
        } else {
            for (String key : trees.getAsJsonObject().keySet()) {
                if (!TREE_KEYS.containsValue(key)) problems.add("Unknown tree \"" + key + "\"");
            }
            for (var entry : TREE_KEYS.entrySet()) {
                JsonElement array = trees.getAsJsonObject().get(entry.getValue());
                if (array == null) continue; // a missing tree array is an empty one
                if (!array.isJsonArray()) {
                    problems.add("trees." + entry.getValue() + " must be an array");
                    continue;
                }
                int i = 0;
                for (JsonElement e : array.getAsJsonArray()) {
                    Node n = node(e, entry.getKey(), "trees." + entry.getValue() + "[" + i++ + "]", kinds, problems);
                    if (n != null) roots.add(n);
                }
            }
        }

        List<Link> links = new ArrayList<>();
        JsonElement relations = doc.get("relations");
        if (relations != null && !relations.isJsonArray()) {
            problems.add("\"relations\" must be an array");
        } else if (relations != null) {
            Set<String> seen = new HashSet<>();
            int i = 0;
            for (JsonElement e : relations.getAsJsonArray()) {
                String at = "relations[" + i++ + "]";
                if (!e.isJsonObject()) {
                    problems.add(at + " must be an object");
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                String type = string(o, "type"), source = string(o, "source"), target = string(o, "target");
                Optional<RelationType> rt = relationType(type);
                if (rt.isEmpty()) problems.add(at + ": unknown relation type \"" + type + "\"");
                if (source == null || !kinds.containsKey(source)) problems.add(at + ": unknown source node \"" + source + "\"");
                if (target == null || !kinds.containsKey(target)) problems.add(at + ": unknown target node \"" + target + "\"");
                if (rt.isEmpty() || !kinds.containsKey(source) || !kinds.containsKey(target)) continue;
                NodeKind from = kinds.get(source), to = kinds.get(target);
                if (!rt.get().getSupportedNodeTypePair().contains(new RelationType.NodeTypePair(from, to))) {
                    problems.add(at + ": a " + type + " relation cannot go from a " + from.getName() + " to a " + to.getName());
                    continue;
                }
                // a pair of nodes has at most one relation: its type follows from their kinds
                if (!seen.add(source.compareTo(target) < 0 ? source + "|" + target : target + "|" + source)) {
                    problems.add(at + ": the nodes \"" + source + "\" and \"" + target + "\" are already related");
                    continue;
                }
                links.add(new Link(rt.get(), source, target));
            }
        }
        if (!problems.isEmpty()) throw new InvalidDocumentException(problems);
        return new Document(List.copyOf(roots), List.copyOf(links));
    }

    private static Node node(JsonElement e, NodeKind kind, String at, Map<String, NodeKind> kinds, List<String> problems) {
        if (!e.isJsonObject()) {
            problems.add(at + " must be an object");
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        String id = string(o, "id");
        if (id == null || id.isBlank()) {
            problems.add(at + ": \"id\" must be a non-empty string");
        } else if (kinds.putIfAbsent(id, kind) != null) {
            problems.add(at + ": duplicate id \"" + id + "\"");
        }
        String description = o.has("description") ? string(o, "description") : "";
        if (description == null) problems.add(at + ": \"description\" must be a string");

        Double x = null, y = null;
        JsonElement layout = o.get("layout");
        if (layout != null) {
            if (layout.isJsonObject() && number(layout.getAsJsonObject(), "x") != null
                    && number(layout.getAsJsonObject(), "y") != null) {
                x = number(layout.getAsJsonObject(), "x");
                y = number(layout.getAsJsonObject(), "y");
            } else {
                problems.add(at + ": \"layout\" must be an object with the numbers \"x\" and \"y\"");
            }
        }

        List<Node> children = new ArrayList<>();
        JsonElement kids = o.get("children");
        if (kids != null && !kids.isJsonArray()) {
            problems.add(at + ": \"children\" must be an array");
        } else if (kids != null) {
            int i = 0;
            for (JsonElement c : kids.getAsJsonArray()) {
                Node child = node(c, kind, at + ".children[" + i++ + "]", kinds, problems);
                if (child != null) children.add(child);
            }
        }

        DecompositionType decomposition = DecompositionType.NONE;
        String d = o.has("decomposition") ? string(o, "decomposition") : null;
        if (o.has("decomposition")) {
            if ("and".equals(d)) decomposition = DecompositionType.AND;
            else if ("or".equals(d)) decomposition = DecompositionType.OR;
            else problems.add(at + ": \"decomposition\" must be \"and\" or \"or\"");
            if (kids == null || !kids.isJsonArray() || kids.getAsJsonArray().isEmpty()) {
                problems.add(at + ": only a node with children has a decomposition");
            }
        } else if (!children.isEmpty()) {
            decomposition = DecompositionType.AND;
        }
        return new Node(kind, id, description, decomposition, x, y, List.copyOf(children));
    }

    /**
     * Replaces the content of {@code model} with {@code document}: deletes every node (and with them every relation),
     * then restores the nodes with their ids, decompositions and positions, and the relations. Everything goes through
     * the model's API, so its listeners (diagram, bigraph, validation) follow as for any other edit. Nodes without a
     * position are placed in one column per kind.
     */
    public static void apply(Document document, POPPModel model) {
        for (TreeNode<?> n : List.copyOf(model.getAllNodes())) model.deleteNode(n.getId());
        Map<NodeKind, int[]> rows = new EnumMap<>(NodeKind.class);
        for (Node root : document.roots()) restore(root, null, 0, model, rows);
        for (Link l : document.relations()) {
            RelationResult result = model.relate(model.findNode(l.source()), model.findNode(l.target()));
            if (!result.isSuccess()) {
                throw new IllegalStateException("Relation " + l + " was rejected: " + result);
            }
        }
    }

    private static void restore(Node n, TreeNode<?> parent, int depth, POPPModel model, Map<NodeKind, int[]> rows) {
        int column = new ArrayList<>(TREE_KEYS.keySet()).indexOf(n.kind());
        int row = rows.computeIfAbsent(n.kind(), k -> new int[1])[0]++;
        double x = n.x() != null ? n.x() : column * 320 + depth * 40;
        double y = n.y() != null ? n.y() : row * 90;
        TreeNode<?> node = model.restoreNode(n.kind(), n.id(), n.description(), x, y);
        if (parent != null) model.decompose(parent, node);
        for (Node c : n.children()) restore(c, node, depth + 1, model, rows);
        if (n.decomposition() != DecompositionType.NONE) node.setDecompositionType(n.decomposition());
    }

    private static Optional<RelationType> relationType(String name) {
        if (name == null) return Optional.empty();
        return Arrays.stream(RelationType.values()).filter(t -> t.name().equalsIgnoreCase(name)).findFirst();
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static Double number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : null;
    }
}
