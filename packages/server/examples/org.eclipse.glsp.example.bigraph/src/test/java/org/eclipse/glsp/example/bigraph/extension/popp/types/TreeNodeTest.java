package org.eclipse.glsp.example.bigraph.extension.popp.types;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TreeNodeTest {

    @Test
    void maintainsHierarchyAndRejectsCycles() {
        Problem root = new Problem("root", 0, 0);
        Problem child = new Problem("child", 0, 0);
        Problem grandchild = new Problem("grandchild", 0, 0);

        assertTrue(root.addChild(child));
        assertTrue(child.addChild(grandchild));
        assertEquals(root, child.getParent().orElseThrow());
        assertTrue(grandchild.isChildOf(root));
        assertFalse(root.addChild(root));
        assertFalse(grandchild.addChild(root));
        assertFalse(root.addChild(child));

        assertTrue(root.removeChild(child));
        assertTrue(child.getParent().isEmpty());
        assertFalse(root.removeChild(child));
    }

    @Test
    void reparentsNodesAndCalculatesHeightAndWidth() {
        Problem firstRoot = new Problem("first-root", 0, 0);
        Problem secondRoot = new Problem("second-root", 0, 0);
        Problem left = new Problem("left", 0, 0);
        Problem right = new Problem("right", 0, 0);
        Problem leaf = new Problem("leaf", 0, 0);

        assertTrue(firstRoot.addChild(left));
        assertTrue(firstRoot.addChild(right));
        assertTrue(left.addChild(leaf));
        assertEquals(3, firstRoot.getHeight());
        assertEquals(2, firstRoot.getWidth());

        assertTrue(secondRoot.addChild(left));
        assertTrue(firstRoot.getChildren().stream().noneMatch(left::equals));
        assertEquals(secondRoot, left.getParent().orElseThrow());
    }

    @Test
    void validatesIdsAndCopiesChildren() {
        assertThrows(IllegalArgumentException.class, () -> new Problem(null, "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Problem("  ", "", 0, 0));

        Problem root = new Problem("root", 0, 0);
        Problem child = new Problem("child", 0, 0);
        root.addChild(child);
        assertThrows(UnsupportedOperationException.class, () -> root.getChildren().add(new Problem("other", 0, 0)));
    }

    @Test
    void decompositionCannotBeResetToNoneWhileChildrenExist() {
        Problem root = new Problem("root", 0, 0);
        root.addChild(new Problem("child", 0, 0));

        assertTrue(root.setDecompositionType(DecompositionType.AND));
        assertFalse(root.setDecompositionType(DecompositionType.NONE));
        assertEquals(DecompositionType.AND, root.getDecompositionType());
    }

    @Test
    void changeParentEventIsDispatched(){
        Problem root = new Problem("root", 0, 0);
        Problem child = new Problem("child", 0, 0);
        AtomicBoolean eventHandled = new AtomicBoolean(false);

        child.addListener((event) -> {
            eventHandled.set(true);
            assertInstanceOf(POPPEvent.NodeChangedParent.class, event);
            POPPEvent.NodeChangedParent parentChanged = (POPPEvent.NodeChangedParent) event;
            assertEquals(parentChanged.node(), child);
            assertEquals(parentChanged.newParent(), root);
            assertNull(parentChanged.oldParent());
        });

        assertFalse(eventHandled.get());

        root.addChild(child);

        assertTrue(eventHandled.get());
    }

    @Test
    void changeDecompositionTypeEventIsDispatched(){
        Problem problem = new Problem("problem", 0, 0);
        problem.addChild(new Problem("child", 0, 0)); // only a node with children has a decomposition
        AtomicBoolean eventHandled = new AtomicBoolean(false);
        DecompositionType initial = problem.getDecompositionType();
        DecompositionType or = DecompositionType.OR;

        problem.addListener((event) -> {
            eventHandled.set(true);
            assertInstanceOf(POPPEvent.NodeDecompositionTypeChanged.class, event);
            POPPEvent.NodeDecompositionTypeChanged typeChanged = (POPPEvent.NodeDecompositionTypeChanged) event;
            assertEquals(problem, typeChanged.node());
            assertEquals(or, typeChanged.node().getDecompositionType());
            assertEquals(initial, typeChanged.oldType());
        });

        assertFalse(eventHandled.get());

        problem.setDecompositionType(or);

        assertTrue(eventHandled.get());
    }

    @Test
    void changeDescriptionEventIsDispatched(){
        Problem problem = new Problem("problem", 0, 0);
        AtomicBoolean eventHandled = new AtomicBoolean(false);
        String initial = problem.getDescription();
        String newDescription = "new description";

        problem.addListener((event) -> {
            eventHandled.set(true);
            assertInstanceOf(POPPEvent.NodeDescriptionChanged.class, event);
            POPPEvent.NodeDescriptionChanged descriptionChanged = (POPPEvent.NodeDescriptionChanged) event;
            assertEquals(problem, descriptionChanged.node());
            assertEquals(newDescription, descriptionChanged.node().getDescription());
            assertEquals(initial, descriptionChanged.oldDescription());
        });

        assertFalse(eventHandled.get());

        problem.setDescription(newDescription);

        assertTrue(eventHandled.get());
    }

    @Test
    void moveEventIsDispatched(){
        Problem problem = new Problem("problem", 0, 0);
        AtomicBoolean eventHandled = new AtomicBoolean(false);
        double newX = 100;
        double newY = -100;

        problem.addListener((event) -> {
            eventHandled.set(true);
            assertInstanceOf(POPPEvent.NodeMoved.class, event);
            POPPEvent.NodeMoved moved = (POPPEvent.NodeMoved) event;
            assertEquals(problem, moved.node());
            assertEquals(newX, moved.node().getX());
            assertEquals(newY, moved.node().getY());
            assertEquals(0, moved.oldX());
            assertEquals(0, moved.oldY());
        });

        assertFalse(eventHandled.get());

        problem.move(newX, newY);

        assertTrue(eventHandled.get());
    }

    @Test
    void testSubscribeAndUnsubscribe(){
        Problem problem = new Problem("problem", 0, 0);
        AtomicInteger eventsHandled = new AtomicInteger(0);
        String desc1 = "desc1";
        String desc2 = "desc2";
        String desc3 = "desc3";

        POPPEventListener listener = (event) -> {
            assertInstanceOf(POPPEvent.NodeDescriptionChanged.class, event);
            eventsHandled.incrementAndGet();
        };

        assertEquals(0, eventsHandled.get());
        problem.setDescription(desc1);
        assertEquals(0, eventsHandled.get());

        problem.addListener(listener);
        assertEquals(0, eventsHandled.get());
        problem.setDescription(desc2);
        assertEquals(1, eventsHandled.get());

        problem.removeListener(listener);
        problem.setDescription(desc3);
        assertEquals(1, eventsHandled.get());
    }

    /**
     * Regression: moving a child to another parent left the old parent listening to it. Attaching the old parent
     * below that child then made the two forward every event to each other (StackOverflowError on the next event).
     */
    @Test
    void movingAChildAndAttachingTheOldParentBelowItDoesNotLoopEvents() {
        POPPModel model = new POPPModel();
        TreeNode<?> nodeA = model.createNode(NodeKind.PROBLEM, "a", 0, 0);
        TreeNode<?> nodeB = model.createNode(NodeKind.PROBLEM, "b", 0, 0);
        TreeNode<?> nodeC = model.createNode(NodeKind.PROBLEM, "c", 0, 0);
        AtomicInteger moves = new AtomicInteger();
        model.addListener(e -> { if (e instanceof POPPEvent.NodeChangedParent) moves.incrementAndGet(); });

        assertEquals(DecompositionResult.ATTACHED, model.decompose(nodeA, nodeC));
        moves.set(0);
        assertEquals(DecompositionResult.ATTACHED, model.decompose(nodeB, nodeC)); // move c from a to b
        assertEquals(1, moves.get(), "the move still reaches the model, exactly once");
        assertEquals(DecompositionResult.ATTACHED, model.decompose(nodeC, nodeA)); // a below its former child

        assertDoesNotThrow(() -> nodeA.setDescription("renamed")); // looped before the fix
        assertEquals(java.util.List.of(nodeB), model.getProblemRoots());
    }

    @Test
    void aNodeHasADecompositionExactlyWhileItHasChildren() {
        POPPModel model = new POPPModel();
        Problem nodeA = (Problem) model.createNode(NodeKind.PROBLEM, "a", 0, 0);
        Problem nodeB = (Problem) model.createNode(NodeKind.PROBLEM, "b", 0, 0);
        Problem nodeC = (Problem) model.createNode(NodeKind.PROBLEM, "c", 0, 0);

        nodeA.addChild(nodeB);
        assertEquals(DecompositionType.AND, nodeA.getDecompositionType());

        nodeB.addChild(nodeA); // flip: a becomes b's child and has no children left
        assertEquals(DecompositionType.NONE, nodeA.getDecompositionType());
        assertEquals(DecompositionType.AND, nodeB.getDecompositionType());

        nodeC.addChild(nodeA); // move a from b to c: b loses its last child
        assertEquals(DecompositionType.NONE, nodeB.getDecompositionType());

        nodeC.removeChild(nodeA);
        assertEquals(DecompositionType.NONE, nodeC.getDecompositionType());
    }

    @Test
    void aLeafHasNoDecompositionToSet() {
        Problem leaf = new Problem("leaf", 0, 0);
        assertFalse(leaf.setDecompositionType(DecompositionType.AND));
        assertFalse(leaf.setDecompositionType(DecompositionType.OR));
        assertTrue(leaf.setDecompositionType(DecompositionType.NONE));
        assertEquals(DecompositionType.NONE, leaf.getDecompositionType());
    }

    @Test
    void settingTheCurrentDecompositionTypeEmitsNothing() {
        Problem root = new Problem("root", 0, 0);
        root.addChild(new Problem("child", 0, 0));
        AtomicInteger events = new AtomicInteger();
        root.addListener(e -> events.incrementAndGet());

        assertTrue(root.setDecompositionType(DecompositionType.AND));
        assertEquals(0, events.get());
        assertTrue(root.setDecompositionType(DecompositionType.OR));
        assertEquals(1, events.get());
    }

    @Test
    void attachingTheDirectParentFlipsTheTwo() {
        Problem nodeA = new Problem("a", 0, 0);
        Problem nodeB = new Problem("b", 0, 0);
        nodeA.addChild(nodeB);

        assertTrue(nodeB.addChild(nodeA));

        assertTrue(nodeB.getParent().isEmpty());
        assertEquals(nodeB, nodeA.getParent().orElseThrow());
        assertEquals(java.util.List.of(nodeA), nodeB.getChildren());
        assertTrue(nodeA.getChildren().isEmpty());
        assertEquals(DecompositionType.NONE, nodeA.getDecompositionType());
        assertEquals(DecompositionType.AND, nodeB.getDecompositionType());
    }

    @Test
    void anExistingDescendantOrAFartherAncestorIsRefused() {
        Problem root = new Problem("root", 0, 0);
        Problem child = new Problem("child", 0, 0);
        Problem grandchild = new Problem("grandchild", 0, 0);
        root.addChild(child);
        child.addChild(grandchild);

        assertFalse(root.addChild(grandchild), "already below root");
        assertFalse(grandchild.addChild(root), "would close a cycle");
        assertFalse(root.addChild(null));
        assertEquals(child, grandchild.getParent().orElseThrow());
    }

    @Test
    void setParentRefusesDescendantsAndIsANoOpForTheCurrentParent() {
        Problem root = new Problem("root", 0, 0);
        Problem child = new Problem("child", 0, 0);
        root.addChild(child);
        AtomicInteger events = new AtomicInteger();
        root.addListener(e -> events.incrementAndGet());

        assertFalse(root.setParent(child));
        assertFalse(root.setParent(root));
        assertTrue(child.setParent(root));
        assertEquals(0, events.get());
    }

    @Test
    void setParentNullMakesARootAndEmitsOneEventThroughTheOldParent() {
        Problem root = new Problem("root", 0, 0);
        Problem child = new Problem("child", 0, 0);
        root.addChild(child);
        java.util.List<POPPEvent> events = new java.util.ArrayList<>();
        root.addListener(events::add);

        assertTrue(child.setParent(null));

        assertTrue(child.getParent().isEmpty());
        assertTrue(root.getChildren().isEmpty());
        assertEquals(1, events.stream().filter(POPPEvent.NodeChangedParent.class::isInstance).count());
        POPPEvent.NodeChangedParent changed = (POPPEvent.NodeChangedParent) events.stream()
                .filter(POPPEvent.NodeChangedParent.class::isInstance).findFirst().orElseThrow();
        assertEquals(root, changed.oldParent());
        assertNull(changed.newParent());
    }

    @Test
    void eventsOfDeepDescendantsReachTheRootsListeners() {
        Problem root = new Problem("root", 0, 0);
        Problem child = new Problem("child", 0, 0);
        Problem grandchild = new Problem("grandchild", 0, 0);
        root.addChild(child);
        child.addChild(grandchild);
        java.util.List<POPPEvent> events = new java.util.ArrayList<>();
        root.addListener(events::add);

        grandchild.setDescription("changed");

        assertEquals(1, events.size());
        assertEquals(grandchild, ((POPPEvent.NodeDescriptionChanged) events.get(0)).node());
    }

    @Test
    void aDetachedChildNoLongerForwardsEvents() {
        Problem root = new Problem("root", 0, 0);
        Problem child = new Problem("child", 0, 0);
        root.addChild(child);
        root.removeChild(child);
        AtomicInteger events = new AtomicInteger();
        root.addListener(e -> events.incrementAndGet());

        child.move(1, 1);

        assertEquals(0, events.get());
    }

    @Test
    void identityIsKindAndId() {
        Problem problem = new Problem("same", "p", 0, 0);
        Goal goal = new Goal("same", "g", 0, 0);
        assertNotEquals(problem, goal);
        assertEquals(problem, new Problem("same", "other description", 1, 1));
        assertEquals(problem.hashCode(), new Problem("same", "x", 0, 0).hashCode());
    }

    @Test
    void heightAndWidthOfALeaf() {
        Problem leaf = new Problem("leaf", 0, 0);
        assertEquals(1, leaf.getHeight());
        assertEquals(1, leaf.getWidth());
    }

    @Test
    void setParentMovesTheNodeIntoTheNewParentsChildren() {
        Problem first = new Problem("first", 0, 0);
        Problem second = new Problem("second", 0, 0);
        Problem child = new Problem("child", 0, 0);
        first.addChild(child);
        java.util.List<POPPEvent> events = new java.util.ArrayList<>();
        first.addListener(events::add);

        assertTrue(child.setParent(second));

        assertEquals(java.util.List.of(child), second.getChildren());
        assertTrue(first.getChildren().isEmpty());
        assertEquals(second, child.getParent().orElseThrow());
        assertEquals(1, events.stream().filter(POPPEvent.NodeChangedParent.class::isInstance).count());
    }
}
