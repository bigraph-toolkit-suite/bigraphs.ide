package org.eclipse.glsp.example.bigraph.extension.popp.types;

import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEvent;
import org.eclipse.glsp.example.bigraph.extension.popp.event.POPPEventListener;
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
            POPPEvent.NodeChangedParent e = (POPPEvent.NodeChangedParent) event;
            assertEquals(e.node(), child);
            assertEquals(e.newParent(), root);
            assertNull(e.oldParent());
        });

        assertFalse(eventHandled.get());

        root.addChild(child);

        assertTrue(eventHandled.get());
    }

    @Test
    void changeDecompositionTypeEventIsDispatched(){
        Problem problem = new Problem("problem", 0, 0);
        AtomicBoolean eventHandled = new AtomicBoolean(false);
        DecompositionType initial = problem.getDecompositionType();
        DecompositionType or = DecompositionType.OR;

        problem.addListener((event) -> {
            eventHandled.set(true);
            assertInstanceOf(POPPEvent.NodeDecompositionTypeChanged.class, event);
            POPPEvent.NodeDecompositionTypeChanged e = (POPPEvent.NodeDecompositionTypeChanged) event;
            assertEquals(problem, e.node());
            assertEquals(or, e.node().getDecompositionType());
            assertEquals(initial, e.oldType());
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
            POPPEvent.NodeDescriptionChanged e = (POPPEvent.NodeDescriptionChanged) event;
            assertEquals(problem, e.node());
            assertEquals(newDescription, e.node().getDescription());
            assertEquals(initial, e.oldDescription());
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
            POPPEvent.NodeMoved e = (POPPEvent.NodeMoved) event;
            assertEquals(problem, e.node());
            assertEquals(newX, e.node().getX());
            assertEquals(newY, e.node().getY());
            assertEquals(0, e.oldX());
            assertEquals(0, e.oldY());
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
}