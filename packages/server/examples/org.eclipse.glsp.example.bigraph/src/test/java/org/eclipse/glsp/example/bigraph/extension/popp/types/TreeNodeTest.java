package org.eclipse.glsp.example.bigraph.extension.popp.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TreeNodeTest {

    @Test
    void maintainsHierarchyAndRejectsCycles() {
        Problem root = new Problem("root");
        Problem child = new Problem("child");
        Problem grandchild = new Problem("grandchild");

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
        Problem firstRoot = new Problem("first-root");
        Problem secondRoot = new Problem("second-root");
        Problem left = new Problem("left");
        Problem right = new Problem("right");
        Problem leaf = new Problem("leaf");

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
        assertThrows(IllegalArgumentException.class, () -> new Problem(null));
        assertThrows(IllegalArgumentException.class, () -> new Problem("  "));

        Problem root = new Problem("root");
        Problem child = new Problem("child");
        root.addChild(child);
        assertThrows(UnsupportedOperationException.class, () -> root.getChildren().add(new Problem("other")));
    }

    @Test
    void decompositionCannotBeResetToNoneWhileChildrenExist() {
        Problem root = new Problem("root");
        root.addChild(new Problem("child"));

        assertTrue(root.setDecompositionType(DecompositionType.AND));
        assertFalse(root.setDecompositionType(DecompositionType.NONE));
        assertEquals(DecompositionType.AND, root.getDecompositionType());
    }
}