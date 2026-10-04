package org.eclipse.glsp.example.bigraph.extension.popp.types;

import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEventEmitter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Stores typed cross-tree relations; the type and its canonical direction are derived from the two node kinds given. */
public final class RelationGraph extends POPPEventEmitter {

    private static final Map<RelationType.NodeTypePair, RelationType> RELATION_BY_ENDPOINT_KINDS = Arrays
        .stream(RelationType.values())
        .flatMap(type -> type.getSupportedNodeTypePair().stream().map(pair -> Map.entry(pair, type)))
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private final Set<Relation> relations = new LinkedHashSet<>();
    private final Map<TreeNode<?>, Set<Relation>> outgoing = new LinkedHashMap<>();
    private final Map<TreeNode<?>, Set<Relation>> incoming = new LinkedHashMap<>();

    /** Resolves the canonical {@code source -> target} relation for two nodes, in whichever order they're given. */
    private static Optional<Relation> resolve(TreeNode<?> a, TreeNode<?> b) {
        RelationType asGiven = RELATION_BY_ENDPOINT_KINDS.get(new RelationType.NodeTypePair(a.getClass(), b.getClass()));
        if (asGiven != null) {
            return Optional.of(new Relation(a, asGiven, b));
        }
        RelationType reversed = RELATION_BY_ENDPOINT_KINDS.get(new RelationType.NodeTypePair(b.getClass(), a.getClass()));
        if (reversed != null) {
            return Optional.of(new Relation(b, reversed, a));
        }
        return Optional.empty();
    }

    /** Whether the two nodes could be connected at all, e.g. to drive drag-and-drop feedback in the UI. */
    public static boolean canRelate(TreeNode<?> a, TreeNode<?> b) {
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
        emitEvent(new POPPEvent.RelationCreated(relation));
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
        emitEvent(new POPPEvent.RelationRemoved(relation));
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

