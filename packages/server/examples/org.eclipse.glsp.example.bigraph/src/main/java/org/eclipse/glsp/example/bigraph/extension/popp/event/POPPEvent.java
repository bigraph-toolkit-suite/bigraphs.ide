package org.eclipse.glsp.example.bigraph.extension.popp.event;

import org.eclipse.glsp.example.bigraph.extension.popp.types.DecompositionType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.NodeKind;
import org.eclipse.glsp.example.bigraph.extension.popp.types.RelationType;
import org.eclipse.glsp.example.bigraph.extension.popp.types.TreeNode;

/** Emitted after a domain-model mutation has been applied successfully. */
public sealed interface POPPEvent {

    record NodeCreated(NodeKind kind, TreeNode<?> node) implements POPPEvent {
    }

    record NodeDescriptionChanged(NodeKind kind, TreeNode<?> node, String oldDescription) implements POPPEvent {
    }

    record NodeMoved(NodeKind kind, TreeNode<?> node, double oldX, double oldY) implements POPPEvent {
    }

    record NodeDecompositionTypeChanged(NodeKind kind, TreeNode<?> node, DecompositionType oldType)
            implements POPPEvent {
    }

    record NodeRemoved(NodeKind kind, TreeNode<?> node) implements POPPEvent {
    }

    /** There is no guarantee that node is updated before the event is handled, therefore the newParent should be used. */
    record NodeChangeParent(NodeKind kind, TreeNode<?> node, TreeNode<?> oldParent, TreeNode<?> newParent) implements POPPEvent {
    }

    record RelationCreated(TreeNode<?> source, RelationType type, TreeNode<?> target) implements POPPEvent {
    }

    record RelationRemoved(TreeNode<?> source, RelationType type, TreeNode<?> target) implements POPPEvent {
    }
}
