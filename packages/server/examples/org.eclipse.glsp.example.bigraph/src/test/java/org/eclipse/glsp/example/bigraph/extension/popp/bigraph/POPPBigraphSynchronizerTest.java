package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.extension.popp.POPPTestSession;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.Test;

/** Edits the domain model and checks the bigraph that {@link POPPBigraphSynchronizer} maintains. */
class POPPBigraphSynchronizerTest {
    private final POPPTestSession session = new POPPTestSession();
    private final POPPModel model = session.model();

    private BigraphEntity.NodeEntity<DynamicControl> entity(String name) {
        return session.bigraph.getNodes().stream().filter(n -> n.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no bigraph node " + name));
    }

    private boolean exists(String name) {
        return session.bigraph.getNodes().stream().anyMatch(n -> n.getName().equals(name));
    }

    private String parentOf(String name) {
        return ((BigraphEntity.NodeEntity<?>) session.bigraph.getParent(entity(name))).getName();
    }

    private static String control(BigraphEntity.NodeEntity<?> node) {
        return node.getControl().getNamedType().stringValue();
    }

    private Set<String> decodedChildren(TreeNode<?> node) {
        return session.popp().decodeDecomposition(entity(node.getId())).children().stream()
                .map(BigraphEntity.NodeEntity::getName).collect(Collectors.toSet());
    }

    @Test
    void aCreatedNodeIsADomainNodeInTheContainerWithItsAttributes() {
        TreeNode<?> goal = model.createNode(NodeKind.GOAL, "Ship it", 12, 34);

        var node = entity(goal.getId());
        assertEquals("Goal", control(node));
        assertEquals("POPP", parentOf(goal.getId()));
        Map<String, Object> attributes = node.getAttributes();
        assertEquals("Ship it", attributes.get("description"));
        assertEquals(12.0, ((Number) attributes.get("layout.x")).doubleValue());
        assertEquals(34.0, ((Number) attributes.get("layout.y")).doubleValue());
    }

    @Test
    void descriptionAndMoveUpdateTheAttributes() {
        TreeNode<?> goal = model.createNode(NodeKind.GOAL, "old", 0, 0);
        goal.setDescription("new");
        goal.move(5, 6);

        Map<String, Object> attributes = entity(goal.getId()).getAttributes();
        assertEquals("new", attributes.get("description"));
        assertEquals(5.0, ((Number) attributes.get("layout.x")).doubleValue());
        assertEquals(6.0, ((Number) attributes.get("layout.y")).doubleValue());
    }

    @Test
    void decomposingBuildsABinaryChainUnderTheParent() {
        TreeNode<?> parent = model.createNode(NodeKind.GOAL, "parent", 0, 0);
        List<TreeNode<?>> children = List.of(
                model.createNode(NodeKind.GOAL, "a", 0, 0),
                model.createNode(NodeKind.GOAL, "b", 0, 0),
                model.createNode(NodeKind.GOAL, "c", 0, 0));
        children.forEach(c -> model.decompose(parent, c));

        assertEquals("AndDecomp", control(entity(parent.getId() + "_DECOMP")));
        assertEquals(parent.getId(), parentOf(parent.getId() + "_DECOMP"));
        assertEquals(parent.getId() + "_DECOMP", parentOf(parent.getId() + "_DECOMP_1"));
        assertFalse(exists(parent.getId() + "_DECOMP_2"), "three children need two chain nodes");
        assertEquals(DecompositionType.AND, session.popp().decodeDecomposition(entity(parent.getId())).type());
        assertEquals(children.stream().map(TreeNode::getId).collect(Collectors.toSet()), decodedChildren(parent));
    }

    @Test
    void switchingToOrRebuildsTheChainWithOrNodes() {
        TreeNode<?> parent = model.createNode(NodeKind.SUCCESS_CRITERIA, "parent", 0, 0);
        model.decompose(parent, model.createNode(NodeKind.SUCCESS_CRITERIA, "a", 0, 0));
        model.decompose(parent, model.createNode(NodeKind.SUCCESS_CRITERIA, "b", 0, 0));

        parent.setDecompositionType(DecompositionType.OR);

        assertEquals("OrDecomp", control(entity(parent.getId() + "_DECOMP")));
        assertEquals(DecompositionType.OR, session.popp().decodeDecomposition(entity(parent.getId())).type());
        assertEquals(2, decodedChildren(parent).size());
        assertTrue(session.bigraph.getNodes().stream().noneMatch(n -> control(n).equals("AndDecomp")));
    }

    @Test
    void removingTheLastChildRemovesTheChainAndReturnsTheChildToTheContainer() {
        Goal parent = (Goal) model.createNode(NodeKind.GOAL, "parent", 0, 0);
        Goal child = (Goal) model.createNode(NodeKind.GOAL, "child", 0, 0);
        parent.addChild(child);

        parent.removeChild(child);

        assertFalse(exists(parent.getId() + "_DECOMP"));
        assertEquals("POPP", parentOf(child.getId()));
        assertEquals(DecompositionType.NONE, session.popp().decodeDecomposition(entity(parent.getId())).type());
    }

    @Test
    void reparentingMovesTheChildIntoTheNewParentsChain() {
        TreeNode<?> first = model.createNode(NodeKind.GOAL, "first", 0, 0);
        TreeNode<?> second = model.createNode(NodeKind.GOAL, "second", 0, 0);
        TreeNode<?> child = model.createNode(NodeKind.GOAL, "child", 0, 0);
        TreeNode<?> other = model.createNode(NodeKind.GOAL, "other", 0, 0);
        model.decompose(first, child);
        model.decompose(first, other);

        model.decompose(second, child);

        assertEquals(Set.of(other.getId()), decodedChildren(first));
        assertEquals(Set.of(child.getId()), decodedChildren(second));
    }

    @Test
    void aRelationIsAnEdgeBetweenTwoTypedStubs() {
        TreeNode<?> solution = model.createNode(NodeKind.SOLUTION, "solution", 0, 0);
        TreeNode<?> proof = model.createNode(NodeKind.SUCCESS_PROOF, "proof", 0, 0);

        model.relate(proof, solution); // either order: the type fixes the direction
        String name = solution.getId() + "->" + proof.getId();

        var out = entity("PRODUCES_OUTGOING:" + name);
        var in = entity("PRODUCES_INCOMING:" + name);
        assertEquals("ProducesOutgoing", control(out));
        assertEquals("ProducesIncoming", control(in));
        assertEquals(solution.getId(), parentOf(out.getName()));
        assertEquals(proof.getId(), parentOf(in.getName()));
        var edge = session.bigraph.getEdges().stream().filter(e -> e.getName().equals("PRODUCES:" + name))
                .findFirst().orElseThrow();
        assertEquals(2, session.bigraph.getPointsFromLink(edge).size());

        model.unrelate(solution, proof);
        assertFalse(exists(out.getName()));
        assertFalse(exists(in.getName()));
        assertTrue(session.bigraph.getEdges().isEmpty());
    }

    @Test
    void deletingANodeRemovesItsChainAndRelationsButKeepsItsChildren() {
        TreeNode<?> node = model.createNode(NodeKind.GOAL, "node", 0, 0);
        TreeNode<?> child = model.createNode(NodeKind.GOAL, "child", 0, 0);
        TreeNode<?> criterion = model.createNode(NodeKind.SUCCESS_CRITERIA, "criterion", 0, 0);
        model.decompose(node, child);
        model.relate(node, criterion);

        model.deleteNode(node.getId());

        assertFalse(exists(node.getId()));
        assertFalse(exists(node.getId() + "_DECOMP"));
        assertTrue(session.bigraph.getEdges().isEmpty());
        assertEquals(Set.of("POPP", child.getId(), criterion.getId()),
                session.bigraph.getNodes().stream().map(BigraphEntity.NodeEntity::getName).collect(Collectors.toSet()));
        assertEquals("POPP", parentOf(child.getId()));
    }

    @Test
    void theCoreBigraphViewMirrorsTheChanges() {
        var view = session.modelState.getActiveView();
        TreeNode<?> goal = model.createNode(NodeKind.GOAL, "goal", 0, 0);
        TreeNode<?> criterion = model.createNode(NodeKind.SUCCESS_CRITERIA, "criterion", 0, 0);
        model.relate(goal, criterion);
        var goalEntity = entity(goal.getId());
        var edge = session.bigraph.getEdges().stream().findFirst().orElseThrow();

        assertTrue(view.getGModelIdForEntity(goalEntity).isPresent());
        assertTrue(view.getGModelIdForEntity(edge).isPresent());

        model.unrelate(goal, criterion);
        model.deleteNode(goal.getId());
        assertTrue(view.getGModelIdForEntity(goalEntity).isEmpty());
        assertTrue(view.getGModelIdForEntity(edge).isEmpty());
    }

    @Test
    void aBigraphWrittenByTheEditorLoadsBackIntoTheSameModel() {
        TreeNode<?> problem = model.createNode(NodeKind.PROBLEM, "problem", 1, 2);
        TreeNode<?> sub = model.createNode(NodeKind.PROBLEM, "sub", 3, 4);
        TreeNode<?> sub2 = model.createNode(NodeKind.PROBLEM, "sub2", 5, 6);
        TreeNode<?> goal = model.createNode(NodeKind.GOAL, "goal", 7, 8);
        TreeNode<?> consequence = model.createNode(NodeKind.CONSEQUENCE, "consequence", 9, 10);
        model.decompose(problem, sub);
        model.decompose(problem, sub2);
        problem.setDecompositionType(DecompositionType.OR);
        model.relate(problem, goal);
        model.relate(problem, consequence);

        POPPModel loaded = POPPTestSession.open(session.bigraph).model();

        assertEquals(5, loaded.getAllNodes().size());
        TreeNode<?> loadedProblem = loaded.findNode(problem.getId());
        assertEquals(NodeKind.PROBLEM, loadedProblem.getKind());
        assertEquals("problem", loadedProblem.getDescription());
        assertEquals(DecompositionType.OR, loadedProblem.getDecompositionType());
        assertEquals(Set.of(sub.getId(), sub2.getId()),
                loadedProblem.getChildren().stream().map(TreeNode::getId).collect(Collectors.toSet()));
        assertEquals(problem.getId(), loaded.findNode(sub.getId()).getParent().orElseThrow().getId());
        assertEquals(model.getRelations().all().stream().map(r -> r.source().getId() + r.type() + r.target().getId())
                        .collect(Collectors.toSet()),
                loaded.getRelations().all().stream().map(r -> r.source().getId() + r.type() + r.target().getId())
                        .collect(Collectors.toSet()));
        assertEquals(7.0, loaded.findNode(goal.getId()).getX());
    }
}
