package org.eclipse.glsp.example.bigraph.extension.popp.types;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Stores typed cross-tree relations; the type and its canonical direction are derived from the two node kinds given. */
public final class RelationGraph {

    private record KindPair(Class<?> from, Class<?> to) {
    }

    private static final Map<KindPair, RelationType> RELATION_BY_KIND_PAIR = Map.ofEntries(
        Map.entry(new KindPair(Problem.class, Consequence.class), RelationType.CAUSES),
        Map.entry(new KindPair(Goal.class, SuccessCriteria.class), RelationType.CAUSES),
        Map.entry(new KindPair(Problem.class, Goal.class), RelationType.INVERTS),
        Map.entry(new KindPair(Consequence.class, SuccessCriteria.class), RelationType.INVERTS),
        Map.entry(new KindPair(Solution.class, Goal.class), RelationType.REALIZES),
        Map.entry(new KindPair(Solution.class, SuccessProof.class), RelationType.PRODUCES),
        Map.entry(new KindPair(SuccessProof.class, SuccessCriteria.class), RelationType.VALIDATES)
    );

    private final Set<Relation> relations = new LinkedHashSet<>();
    private final Map<TreeNode<?>, Set<Relation>> outgoing = new LinkedHashMap<>();
    private final Map<TreeNode<?>, Set<Relation>> incoming = new LinkedHashMap<>();

    /** Resolves the canonical {@code source -> target} relation for two nodes, in whichever order they're given. */
    private static Optional<Relation> resolve(TreeNode<?> a, TreeNode<?> b) {
        RelationType asGiven = RELATION_BY_KIND_PAIR.get(new KindPair(a.getClass(), b.getClass()));
        if (asGiven != null) {
            return Optional.of(new Relation(a, asGiven, b));
        }
        RelationType reversed = RELATION_BY_KIND_PAIR.get(new KindPair(b.getClass(), a.getClass()));
        if (reversed != null) {
            return Optional.of(new Relation(b, reversed, a));
        }
        return Optional.empty();
    }

    /** Whether the two nodes could be connected at all, e.g. to drive drag-and-drop feedback in the UI. */
    public boolean canRelate(TreeNode<?> a, TreeNode<?> b) {
        return a != null && b != null && a != b && resolve(a, b).isPresent();
    }

    public RelationResult relate(TreeNode<?> a, TreeNode<?> b) {
        if (a == null || b == null) {
            return RelationResult.NULL_ARGUMENT;
        }
        if (a == b) {
            return RelationResult.SAME_NODE;
        }
        Optional<Relation> resolved = resolve(a, b);
        if (resolved.isEmpty()) {
            return RelationResult.NO_SUCH_RELATION_FOR_KINDS;
        }
        Relation relation = resolved.get();
        if (!relations.add(relation)) {
            return RelationResult.ALREADY_EXISTS;
        }
        outgoing.computeIfAbsent(relation.source(), n -> new LinkedHashSet<>()).add(relation);
        incoming.computeIfAbsent(relation.target(), n -> new LinkedHashSet<>()).add(relation);
        return RelationResult.CREATED;
    }

    public RelationResult unrelate(TreeNode<?> a, TreeNode<?> b) {
        if (a == null || b == null) {
            return RelationResult.NULL_ARGUMENT;
        }
        if (a == b) {
            return RelationResult.SAME_NODE;
        }
        Optional<Relation> resolved = resolve(a, b);
        if (resolved.isEmpty()) {
            return RelationResult.NO_SUCH_RELATION_FOR_KINDS;
        }
        Relation relation = resolved.get();
        if (!relations.remove(relation)) {
            return RelationResult.NOT_FOUND;
        }
        outgoing.getOrDefault(relation.source(), Set.of()).remove(relation);
        incoming.getOrDefault(relation.target(), Set.of()).remove(relation);
        return RelationResult.REMOVED;
    }

    public Set<Relation> outgoing(TreeNode<?> node) {
        return Set.copyOf(outgoing.getOrDefault(node, Set.of()));
    }

    public Set<Relation> incoming(TreeNode<?> node) {
        return Set.copyOf(incoming.getOrDefault(node, Set.of()));
    }

    public Set<Relation> outgoing(TreeNode<?> node, RelationType type) {
        return outgoing(node).stream().filter(r -> r.type() == type).collect(Collectors.toUnmodifiableSet());
    }

    public Set<Relation> incoming(TreeNode<?> node, RelationType type) {
        return incoming(node).stream().filter(r -> r.type() == type).collect(Collectors.toUnmodifiableSet());
    }

    public Set<Relation> all() {
        return Set.copyOf(relations);
    }
}

