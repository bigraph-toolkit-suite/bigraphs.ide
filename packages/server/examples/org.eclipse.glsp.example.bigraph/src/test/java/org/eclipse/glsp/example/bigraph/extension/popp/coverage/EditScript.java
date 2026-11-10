package org.eclipse.glsp.example.bigraph.extension.popp.coverage;

import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

import java.util.*;

/**
 * Reproducible edits on a model, for the incremental-coverage tests. Every action is a
 * toggle within the model's own structure, so a long sequence stays near the starting model:
 * <ul>
 *   <li>{@link Action#LINK}: remove one of the model's relations, or put a removed one back.</li>
 *   <li>{@link Action#TYPE}: switch a decomposed node between AND and OR.</li>
 *   <li>{@link Action#MOVE}: detach a decomposition child from its parent, or attach a detached one again.</li>
 *   <li>{@link Action#NODE}: create a node under a decomposed node of its kind, or delete one created before.</li>
 *   <li>{@link Action#MIXED}: one of the four, at random.</li>
 * </ul>
 * The candidates are taken from the model when the script is created. {@link #undo()} brings the model back to that
 * state, and {@link #reseed} starts another reproducible sequence from it.
 */
public final class EditScript {
    public enum Action { LINK, TYPE, MOVE, NODE, MIXED }

    private record Pair(TreeNode<?> a, TreeNode<?> b) {}

    private final POPPModel model;
    private Random rnd;
    private final List<Pair> relations = new ArrayList<>();
    private final Set<Pair> removed = new HashSet<>();
    private final List<TreeNode<?>> inner = new ArrayList<>();
    private final List<Pair> children = new ArrayList<>(); // (parent, child)
    private final Set<Pair> detached = new HashSet<>();
    private final List<TreeNode<?>> created = new ArrayList<>();
    private final Map<TreeNode<?>, DecompositionType> types = new HashMap<>();
    private int counter;

    public EditScript(POPPModel model, long seed) {
        this.model = model;
        this.rnd = new Random(seed);
        for (Relation r : model.getRelations().all()) relations.add(new Pair(r.source(), r.target()));
        relations.sort(Comparator.comparing((Pair p) -> p.a().getDescription()).thenComparing(p -> p.b().getDescription()));
        List<TreeNode<?>> nodes = new ArrayList<>(model.getAllNodes());
        nodes.sort(Comparator.comparing(TreeNode::getDescription));
        for (TreeNode<?> n : nodes) {
            if (!n.getChildren().isEmpty()) {
                inner.add(n);
                types.put(n, n.getDecompositionType());
            }
            n.getParent().ifPresent(p -> children.add(new Pair((TreeNode<?>) p, n)));
        }
    }

    /** Continues with the random sequence of {@code seed} (the same seed gives the same edits from the same state). */
    public void reseed(long seed) {
        // scrambled: java.util.Random gives nearly the same first numbers for consecutive seeds
        rnd = new Random(new SplittableRandom(seed).nextLong());
    }

    /**
     * Undoes every edit so far: puts removed relations back, attaches detached children again, deletes the created
     * nodes and restores the decomposition types. The model is then structurally the one the script was created on.
     */
    public void undo() {
        for (Pair p : relations) if (removed.remove(p)) model.relate(p.a(), p.b());
        for (Pair p : children) if (detached.remove(p)) model.decompose(p.a(), p.b());
        for (TreeNode<?> n : created) model.deleteNode(n.getId());
        created.clear();
        types.forEach((n, t) -> { if (n.getDecompositionType() != t) n.setDecompositionType(t); });
    }

    /** Performs one edit of {@code action}. Returns what it did (for failure reports). */
    public String step(Action action) {
        Action performed = action == Action.MIXED ? Action.values()[rnd.nextInt(4)] : action;
        return switch (performed) {
            case LINK -> link();
            case TYPE -> type();
            case MOVE -> move();
            case NODE -> node();
            case MIXED -> throw new IllegalStateException();
        };
    }

    private String link() {
        if (relations.isEmpty()) return "no relation";
        Pair pair = relations.get(rnd.nextInt(relations.size()));
        if (removed.remove(pair)) {
            model.relate(pair.a(), pair.b());
            return "relate " + pair.a().getDescription() + " -> " + pair.b().getDescription();
        }
        model.unrelate(pair.a(), pair.b());
        removed.add(pair);
        return "unrelate " + pair.a().getDescription() + " -> " + pair.b().getDescription();
    }

    private String type() {
        List<TreeNode<?>> decomposed = inner.stream().filter(n -> !n.getChildren().isEmpty()).toList();
        if (decomposed.isEmpty()) return "no decomposed node";
        TreeNode<?> decomposedNode = decomposed.get(rnd.nextInt(decomposed.size()));
        DecompositionType decompositionType = decomposedNode.getDecompositionType() == DecompositionType.AND ? DecompositionType.OR : DecompositionType.AND;
        decomposedNode.setDecompositionType(decompositionType);
        return "type " + decomposedNode.getDescription() + " " + decompositionType;
    }

    private String move() {
        if (children.isEmpty()) return "no child";
        Pair pair = children.get(rnd.nextInt(children.size()));
        if (detached.remove(pair)) {
            model.decompose(pair.a(), pair.b());
            return "attach " + pair.b().getDescription() + " to " + pair.a().getDescription();
        }
        pair.b().setParent(null);
        detached.add(pair);
        return "detach " + pair.b().getDescription() + " from " + pair.a().getDescription();
    }

    private String node() {
        if (!created.isEmpty() && rnd.nextBoolean()) {
            TreeNode<?> node = created.remove(rnd.nextInt(created.size()));
            model.deleteNode(node.getId());
            return "delete " + node.getDescription();
        }
        if (inner.isEmpty()) return "no parent";
        TreeNode<?> parent = inner.get(rnd.nextInt(inner.size()));
        TreeNode<?> node = model.createNode(parent.getKind(), "new " + (counter++), 0, 0);
        model.decompose(parent, node);
        created.add(node);
        return "create " + node.getDescription() + " under " + parent.getDescription();
    }
}
