package org.eclipse.glsp.example.bigraph.extension.popp.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class RelationGraphTest {

    private static final Map<Class<?>, Supplier<TreeNode<?>>> NODE_FACTORIES = Map.of(
            Problem.class, () -> new Problem("problem", 0, 0),
            Consequence.class, () -> new Consequence("consequence", 0, 0),
            Goal.class, () -> new Goal("goal", 0, 0),
            SuccessCriteria.class, () -> new SuccessCriteria("criteria", 0, 0),
            Solution.class, () -> new Solution("solution", 0, 0),
            SuccessProof.class, () -> new SuccessProof("proof", 0, 0));

    private record ExpectedRelation(Class<?> from, RelationType type, Class<?> to) {
    }

    private static final List<ExpectedRelation> VALID_RELATIONS = List.of(
            new ExpectedRelation(Problem.class, RelationType.CAUSES, Consequence.class),
            new ExpectedRelation(Goal.class, RelationType.CAUSES, SuccessCriteria.class),
            new ExpectedRelation(Problem.class, RelationType.INVERTS, Goal.class),
            new ExpectedRelation(Solution.class, RelationType.REALIZES, Goal.class),
            new ExpectedRelation(Solution.class, RelationType.PRODUCES, SuccessProof.class),
            new ExpectedRelation(SuccessProof.class, RelationType.VALIDATES, SuccessCriteria.class));

    @Test
    void resolvesEveryValidNodeKindCombination() {
        for (ExpectedRelation expected : VALID_RELATIONS) {
            RelationGraph graph = new RelationGraph();
            TreeNode<?> from = NODE_FACTORIES.get(expected.from()).get();
            TreeNode<?> to = NODE_FACTORIES.get(expected.to()).get();
            String description = expected.from().getSimpleName() + " -> " + expected.to().getSimpleName();

            assertTrue(graph.canRelate(from, to), description + " should be relatable");
            assertEquals(RelationResult.CREATED, graph.relate(from, to), description);
            Relation relation = graph.all().stream().findFirst().orElseThrow();
            assertEquals(expected.type(), relation.type(), description);
            assertEquals(from, relation.source(), description + " canonical source");
            assertEquals(to, relation.target(), description + " canonical target");
        }
    }

    @Test
    void rejectsEveryUnsupportedNodeKindCombination() {
        Class<?>[] kinds = {Problem.class, Consequence.class, Goal.class, SuccessCriteria.class, Solution.class,
                SuccessProof.class};
        Set<Set<Class<?>>> validKindPairs = VALID_RELATIONS.stream()
                .map(r -> Set.of(r.from(), r.to()))
                .collect(Collectors.toSet());
        assertEquals(6, validKindPairs.size());

        List<Set<Class<?>>> unsupportedPairs = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            for (int j = i + 1; j < kinds.length; j++) {
                Set<Class<?>> pair = Set.of(kinds[i], kinds[j]);
                if (!validKindPairs.contains(pair)) {
                    unsupportedPairs.add(pair);
                }
            }
        }
        assertEquals(9, unsupportedPairs.size(), "9 of the 15 possible node-kind pairs should be unsupported");

        for (Set<Class<?>> pair : unsupportedPairs) {
            List<Class<?>> kindList = List.copyOf(pair);
            TreeNode<?> a = NODE_FACTORIES.get(kindList.get(0)).get();
            TreeNode<?> b = NODE_FACTORIES.get(kindList.get(1)).get();
            RelationGraph graph = new RelationGraph();
            String description = kindList.get(0).getSimpleName() + " <-> " + kindList.get(1).getSimpleName();

            assertFalse(graph.canRelate(a, b), description + " should not be relatable");
            assertFalse(graph.canRelate(b, a), description + " should not be relatable reversed");
            assertEquals(RelationResult.NO_SUCH_RELATION_FOR_KINDS, graph.relate(a, b), description);
            assertEquals(RelationResult.NO_SUCH_RELATION_FOR_KINDS, graph.relate(b, a), description);
        }
    }

    @Test
    void resolvesRelationsInEitherArgumentOrderAndTracksThem() {
        RelationGraph graph = new RelationGraph();
        Problem problem = new Problem("problem", 0, 0);
        Consequence consequence = new Consequence("consequence", 0, 0);

        assertTrue(graph.canRelate(problem, consequence));
        assertEquals(RelationResult.CREATED, graph.relate(consequence, problem));
        assertEquals(RelationResult.ALREADY_EXISTS, graph.relate(problem, consequence));

        Relation relation = graph.outgoing(problem).stream().findFirst().orElseThrow();
        assertEquals(problem, relation.source());
        assertEquals(RelationType.CAUSES, relation.type());
        assertEquals(consequence, relation.target());
        assertEquals(1, graph.incoming(consequence, RelationType.CAUSES).size());
        assertEquals(1, graph.all().size());

        assertEquals(RelationResult.REMOVED, graph.unrelate(consequence, problem));
        assertEquals(RelationResult.NOT_FOUND, graph.unrelate(problem, consequence));
        assertTrue(graph.all().isEmpty());
    }

    @Test
    void rejectsInvalidRelationRequests() {
        RelationGraph graph = new RelationGraph();
        Problem problem = new Problem("problem", 0, 0);
        Solution solution = new Solution("solution", 0, 0);

        assertFalse(graph.canRelate(problem, null));
        assertFalse(graph.canRelate(problem, problem));
        assertFalse(graph.canRelate(problem, solution));
        assertEquals(RelationResult.NULL_ARGUMENT, graph.relate(null, problem));
        assertEquals(RelationResult.SAME_NODE, graph.relate(problem, problem));
        assertEquals(RelationResult.NO_SUCH_RELATION_FOR_KINDS, graph.relate(problem, solution));
        assertEquals(RelationResult.SAME_NODE, graph.unrelate(problem, problem));
    }

    @Test
    void filtersOutgoingAndIncomingByRelationType() {
        RelationGraph graph = new RelationGraph();
        Problem problem = new Problem("problem", 0, 0);
        Goal goal = new Goal("goal", 0, 0);
        Consequence consequence = new Consequence("consequence", 0, 0);

        assertEquals(RelationResult.CREATED, graph.relate(problem, consequence));
        assertEquals(RelationResult.CREATED, graph.relate(problem, goal));

        assertEquals(2, graph.outgoing(problem).size());
        assertEquals(Set.of(consequence), graph.outgoing(problem, RelationType.CAUSES).stream()
                .map(Relation::target).collect(Collectors.toSet()));
        assertEquals(Set.of(goal), graph.outgoing(problem, RelationType.INVERTS).stream()
                .map(Relation::target).collect(Collectors.toSet()));
        assertTrue(graph.outgoing(problem, RelationType.VALIDATES).isEmpty());

        assertEquals(1, graph.incoming(consequence, RelationType.CAUSES).size());
        assertTrue(graph.incoming(consequence, RelationType.INVERTS).isEmpty());
        assertTrue(graph.incoming(goal, RelationType.CAUSES).isEmpty());
        assertEquals(1, graph.incoming(goal, RelationType.INVERTS).size());
    }
}