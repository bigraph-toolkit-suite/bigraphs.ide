package org.eclipse.glsp.example.bigraph.extension.popp.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}