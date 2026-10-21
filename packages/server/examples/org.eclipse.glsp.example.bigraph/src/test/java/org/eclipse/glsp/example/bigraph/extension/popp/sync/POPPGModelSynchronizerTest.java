package org.eclipse.glsp.example.bigraph.extension.popp.sync;

import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelFactory;
import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelTypes;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.eclipse.glsp.example.bigraph.extension.popp.POPPTestSession;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GLabel;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GPort;
import org.junit.jupiter.api.Test;

/** Edits the domain model and checks the GModel of the POPP variant that {@link POPPGModelSynchronizer} maintains. */
class POPPGModelSynchronizerTest {
    private final POPPTestSession session = new POPPTestSession();
    private final POPPModel model = session.model();
    private final POPPGModelFactory ids = new POPPGModelFactory();

    private GNode gnode(TreeNode<?> node) {
        return assertInstanceOf(GNode.class, session.gmodelElement(node.getId()).orElseThrow(
                () -> new AssertionError("no GNode for " + node)));
    }

    private GLabel label(TreeNode<?> node) {
        return gnode(node).getChildren().stream().filter(GLabel.class::isInstance).map(GLabel.class::cast)
                .findFirst().orElseThrow();
    }

    private GPort port(TreeNode<?> node) {
        return gnode(node).getChildren().stream().filter(GPort.class::isInstance).map(GPort.class::cast)
                .findFirst().orElseThrow();
    }

    private List<GEdge> edges(String type) {
        return session.gmodelRoot().getChildren().stream()
                .filter(GEdge.class::isInstance).map(GEdge.class::cast)
                .filter(e -> e.getType().equals(type)).toList();
    }

    @Test
    void aCreatedNodeBecomesAGNodeOfItsKindWithLabelAndPort() {
        TreeNode<?> goal = model.createNode(NodeKind.GOAL, "Ship it", 40, 70);

        GNode g = gnode(goal);
        assertEquals("popp:goal", g.getType());
        assertEquals(40, g.getPosition().getX());
        assertEquals(70, g.getPosition().getY());
        assertEquals("NONE", g.getArgs().get("decomposition_type"));

        GLabel label = label(goal);
        assertEquals(ids.toDescriptionId(goal), label.getId());
        assertEquals(POPPGModelTypes.NODE_DESCRIPTION, label.getType());
        assertEquals("Ship it", label.getText());

        GPort port = port(goal);
        assertEquals(ids.toPortId(goal), port.getId());
        assertEquals(POPPGModelTypes.DECOMPOSITION_PORT, port.getType());
    }

    @Test
    void everyKindHasItsOwnGModelType() {
        for (NodeKind kind : NodeKind.values()) {
            TreeNode<?> node = model.createNode(kind, kind.getName(), 0, 0);
            assertEquals(POPPGModelTypes.of(kind), gnode(node).getType());
            assertEquals(kind, POPPGModelTypes.nodeKindOf(gnode(node).getType()).orElseThrow());
        }
    }

    @Test
    void aDescriptionChangeUpdatesTheLabelAndTheNodeSize() {
        TreeNode<?> goal = model.createNode(NodeKind.GOAL, "short", 0, 0);
        double height = gnode(goal).getSize().getHeight();

        goal.setDescription("a much longer description that certainly needs more than one line in the diagram, "
                + "and then some more words so that even the widest node wraps it onto several lines");

        assertEquals(goal.getDescription(), label(goal).getText());
        assertTrue(gnode(goal).getSize().getHeight() > height, "the node grows with its text");
        assertTrue(gnode(goal).getSize().getWidth() <= 480);
    }

    @Test
    void aMoveUpdatesThePosition() {
        TreeNode<?> goal = model.createNode(NodeKind.GOAL, "g", 0, 0);
        goal.move(120, 30);
        assertEquals(120, gnode(goal).getPosition().getX());
        assertEquals(30, gnode(goal).getPosition().getY());
    }

    @Test
    void decomposingAddsAnEdgeFromTheParentsPortToTheChild() {
        TreeNode<?> parent = model.createNode(NodeKind.GOAL, "parent", 0, 0);
        TreeNode<?> child = model.createNode(NodeKind.GOAL, "child", 0, 0);

        assertTrue(model.decompose(parent, child).isSuccess());

        List<GEdge> decompositions = edges(POPPGModelTypes.DECOMPOSITION_EDGE);
        assertEquals(1, decompositions.size());
        GEdge edge = decompositions.getFirst();
        assertEquals(ids.toDecompositionEdgeId(parent, child), edge.getId());
        assertEquals(ids.toPortId(parent), edge.getSourceId());
        assertEquals(child.getId(), edge.getTargetId());
        assertEquals("AND", gnode(parent).getArgs().get("decomposition_type"));
        assertEquals("AND", port(parent).getArgs().get("decomposition_type"));
    }

    @Test
    void switchingTheDecompositionTypeUpdatesNodeAndPort() {
        TreeNode<?> parent = model.createNode(NodeKind.GOAL, "parent", 0, 0);
        model.decompose(parent, model.createNode(NodeKind.GOAL, "child", 0, 0));

        parent.setDecompositionType(DecompositionType.OR);

        assertEquals("OR", gnode(parent).getArgs().get("decomposition_type"));
        assertEquals("OR", port(parent).getArgs().get("decomposition_type"));
    }

    @Test
    void reparentingReplacesTheDecompositionEdge() {
        TreeNode<?> first = model.createNode(NodeKind.GOAL, "first", 0, 0);
        TreeNode<?> second = model.createNode(NodeKind.GOAL, "second", 0, 0);
        TreeNode<?> child = model.createNode(NodeKind.GOAL, "child", 0, 0);
        model.decompose(first, child);

        model.decompose(second, child);

        assertEquals(List.of(ids.toDecompositionEdgeId(second, child)),
                edges(POPPGModelTypes.DECOMPOSITION_EDGE).stream().map(GEdge::getId).toList());
        assertEquals("NONE", gnode(first).getArgs().get("decomposition_type"), "first lost its only child");
    }

    @Test
    void flippingParentAndChildReversesTheEdge() {
        TreeNode<?> a = model.createNode(NodeKind.GOAL, "a", 0, 0);
        TreeNode<?> b = model.createNode(NodeKind.GOAL, "b", 0, 0);
        model.decompose(a, b);

        assertTrue(model.decompose(b, a).isSuccess());

        assertEquals(List.of(ids.toDecompositionEdgeId(b, a)),
                edges(POPPGModelTypes.DECOMPOSITION_EDGE).stream().map(GEdge::getId).toList());
    }

    @Test
    void detachingAChildRemovesItsEdge() {
        Goal parent = (Goal) model.createNode(NodeKind.GOAL, "parent", 0, 0);
        Goal child = (Goal) model.createNode(NodeKind.GOAL, "child", 0, 0);
        parent.addChild(child);

        parent.removeChild(child);

        assertTrue(edges(POPPGModelTypes.DECOMPOSITION_EDGE).isEmpty());
        assertEquals("NONE", gnode(parent).getArgs().get("decomposition_type"));
    }

    @Test
    void aRelationBecomesAnEdgeOfItsTypeInCanonicalDirection() {
        TreeNode<?> goal = model.createNode(NodeKind.GOAL, "goal", 0, 0);
        TreeNode<?> criterion = model.createNode(NodeKind.SUCCESS_CRITERIA, "criterion", 0, 0);

        model.relate(criterion, goal); // given in reverse order

        List<GEdge> causes = edges(POPPGModelTypes.of(RelationType.CAUSES));
        assertEquals(1, causes.size());
        Relation relation = model.getRelations().all().iterator().next();
        assertEquals(ids.toRelationEdgeId(relation), causes.getFirst().getId());
        assertEquals(goal.getId(), causes.getFirst().getSourceId());
        assertEquals(criterion.getId(), causes.getFirst().getTargetId());

        model.unrelate(goal, criterion);
        assertTrue(edges(POPPGModelTypes.of(RelationType.CAUSES)).isEmpty());
    }

    @Test
    void deletingANodeRemovesItsGNodeAndEveryEdgeAtIt() {
        TreeNode<?> parent = model.createNode(NodeKind.GOAL, "parent", 0, 0);
        TreeNode<?> node = model.createNode(NodeKind.GOAL, "node", 0, 0);
        TreeNode<?> child = model.createNode(NodeKind.GOAL, "child", 0, 0);
        TreeNode<?> criterion = model.createNode(NodeKind.SUCCESS_CRITERIA, "criterion", 0, 0);
        model.decompose(parent, node);
        model.decompose(node, child);
        model.relate(node, criterion);

        model.deleteNode(node.getId());

        assertTrue(session.gmodelElement(node.getId()).isEmpty());
        assertTrue(edges(POPPGModelTypes.DECOMPOSITION_EDGE).isEmpty());
        assertTrue(edges(POPPGModelTypes.of(RelationType.CAUSES)).isEmpty());
        assertNotNull(gnode(child), "the child stays, as a root");
        assertEquals(3, session.gmodelRoot().getChildren().size(), "parent, child, criterion");
    }

    @Test
    void openingAFileBuildsTheSameGModel() {
        TreeNode<?> parent = model.createNode(NodeKind.GOAL, "parent", 10, 20);
        TreeNode<?> child = model.createNode(NodeKind.GOAL, "child", 30, 40);
        TreeNode<?> criterion = model.createNode(NodeKind.SUCCESS_CRITERIA, "criterion", 50, 60);
        model.decompose(parent, child);
        parent.setDecompositionType(DecompositionType.OR);
        model.relate(parent, criterion);

        POPPTestSession reopened = POPPTestSession.open(session.bigraph);

        List<String> expected = session.gmodelRoot().getChildren().stream().map(GModelElement::getId).sorted().toList();
        List<String> actual = reopened.gmodelRoot().getChildren().stream().map(GModelElement::getId).sorted().toList();
        assertEquals(expected, actual);
        GNode reopenedParent = (GNode) reopened.gmodelElement(parent.getId()).orElseThrow();
        assertEquals("OR", reopenedParent.getArgs().get("decomposition_type"));
        assertEquals(10, reopenedParent.getPosition().getX());
    }
}
