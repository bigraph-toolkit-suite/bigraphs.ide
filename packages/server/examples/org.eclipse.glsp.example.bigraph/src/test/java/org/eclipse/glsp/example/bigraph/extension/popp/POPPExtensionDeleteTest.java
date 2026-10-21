package org.eclipse.glsp.example.bigraph.extension.popp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.eclipse.glsp.example.bigraph.extension.popp.gmodel.POPPGModelFactory;
import org.eclipse.glsp.example.bigraph.extension.popp.types.*;
import org.junit.jupiter.api.Test;

/** Deleting POPP elements by GModel id, as the delete key and the context menu do. */
class POPPExtensionDeleteTest {
    private final POPPTestSession session = new POPPTestSession();
    private final POPPModel model = session.model();
    private final POPPGModelFactory ids = new POPPGModelFactory();

    private TreeNode<?> node(NodeKind kind, String description) {
        return model.createNode(kind, description, 0, 0);
    }

    private Relation onlyRelation() {
        return model.getRelations().all().iterator().next();
    }

    @Test
    void deletingARelationEdgeRemovesTheRelationEverywhere() {
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");
        model.relate(goal, criterion);
        String edgeId = ids.toRelationEdgeId(onlyRelation());
        assertTrue(session.gmodelElement(edgeId).isPresent());

        assertTrue(POPPExtension.deleteElements(model, List.of(edgeId)));

        assertTrue(model.getRelations().all().isEmpty());
        assertTrue(session.gmodelElement(edgeId).isEmpty());
        assertTrue(session.bigraph.getEdges().isEmpty(), "edge and stubs are gone from the bigraph");
        assertNotNull(model.findNode(goal.getId()), "both nodes stay");
        assertNotNull(model.findNode(criterion.getId()));
    }

    @Test
    void deletingADecompositionEdgeDetachesTheChild() {
        TreeNode<?> parent = node(NodeKind.GOAL, "parent");
        TreeNode<?> child = node(NodeKind.GOAL, "child");
        model.decompose(parent, child);
        String edgeId = ids.toDecompositionEdgeId(parent, child);

        assertTrue(POPPExtension.deleteElements(model, List.of(edgeId)));

        assertTrue(child.getParent().isEmpty());
        assertTrue(model.getGoalRoots().contains(child));
        assertEquals(DecompositionType.NONE, parent.getDecompositionType());
        assertTrue(session.gmodelElement(edgeId).isEmpty());
        assertNotNull(model.findNode(child.getId()));
    }

    @Test
    void oneOfSeveralChildrenLeavesTheRestOfTheDecomposition() {
        TreeNode<?> parent = node(NodeKind.GOAL, "parent");
        TreeNode<?> a = node(NodeKind.GOAL, "a");
        TreeNode<?> b = node(NodeKind.GOAL, "b");
        model.decompose(parent, a);
        model.decompose(parent, b);
        parent.setDecompositionType(DecompositionType.OR);

        POPPExtension.deleteElements(model, List.of(ids.toDecompositionEdgeId(parent, a)));

        assertEquals(List.of(b), parent.getChildren());
        assertEquals(DecompositionType.OR, parent.getDecompositionType());
    }

    @Test
    void aNodeSelectedTogetherWithItsEdgesIsDeletedCleanly() {
        TreeNode<?> parent = node(NodeKind.GOAL, "parent");
        TreeNode<?> goal = node(NodeKind.GOAL, "goal");
        TreeNode<?> criterion = node(NodeKind.SUCCESS_CRITERIA, "criterion");
        model.decompose(parent, goal);
        model.relate(goal, criterion);

        assertTrue(POPPExtension.deleteElements(model, List.of(
                goal.getId(), ids.toRelationEdgeId(onlyRelation()), ids.toDecompositionEdgeId(parent, goal))));

        assertNull(model.findNode(goal.getId()));
        assertTrue(model.getRelations().all().isEmpty());
        assertTrue(parent.getChildren().isEmpty());
        assertEquals(2, session.gmodelRoot().getChildren().size(), "parent and criterion");
    }

    @Test
    void unknownIdsAreNotHandled() {
        node(NodeKind.GOAL, "goal");
        assertFalse(POPPExtension.deleteElements(model, List.of("missing", "a_decomposes_b", "x_causes_y")));
        assertEquals(1, model.getAllNodes().size());
    }
}
