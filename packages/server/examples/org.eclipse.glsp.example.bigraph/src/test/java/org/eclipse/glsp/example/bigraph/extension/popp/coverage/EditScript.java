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
        Action a = action == Action.MIXED ? Action.values()[rnd.nextInt(4)] : action;
        return switch (a) {
            case LINK -> link();
            case TYPE -> type();
            case MOVE -> move();
            case NODE -> node();
            case MIXED -> throw new IllegalStateException();
        };
    }

    private String link() {
        if (relations.isEmpty()) return "no relation";
        Pair p = relations.get(rnd.nextInt(relations.size()));
        if (removed.remove(p)) {
            model.relate(p.a(), p.b());
            return "relate " + p.a().getDescription() + " -> " + p.b().getDescription();
        }
        model.unrelate(p.a(), p.b());
        removed.add(p);
        return "unrelate " + p.a().getDescription() + " -> " + p.b().getDescription();
    }

    private String type() {
        List<TreeNode<?>> decomposed = inner.stream().filter(n -> !n.getChildren().isEmpty()).toList();
        if (decomposed.isEmpty()) return "no decomposed node";
        TreeNode<?> n = decomposed.get(rnd.nextInt(decomposed.size()));
        DecompositionType t = n.getDecompositionType() == DecompositionType.AND ? DecompositionType.OR : DecompositionType.AND;
        n.setDecompositionType(t);
        return "type " + n.getDescription() + " " + t;
    }

    private String move() {
        if (children.isEmpty()) return "no child";
        Pair p = children.get(rnd.nextInt(children.size()));
        if (detached.remove(p)) {
            model.decompose(p.a(), p.b());
            return "attach " + p.b().getDescription() + " to " + p.a().getDescription();
        }
        p.b().setParent(null);
        detached.add(p);
        return "detach " + p.b().getDescription() + " from " + p.a().getDescription();
    }

    private String node() {
        if (!created.isEmpty() && rnd.nextBoolean()) {
            TreeNode<?> n = created.remove(rnd.nextInt(created.size()));
            model.deleteNode(n.getId());
            return "delete " + n.getDescription();
        }
        if (inner.isEmpty()) return "no parent";
        TreeNode<?> parent = inner.get(rnd.nextInt(inner.size()));
        TreeNode<?> n = model.createNode(parent.getKind(), "new " + (counter++), 0, 0);
        model.decompose(parent, n);
        created.add(n);
        return "create " + n.getDescription() + " under " + parent.getDescription();
    }
}
