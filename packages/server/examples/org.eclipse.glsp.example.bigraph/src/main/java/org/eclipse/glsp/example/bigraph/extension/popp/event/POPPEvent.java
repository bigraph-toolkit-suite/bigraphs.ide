package org.eclipse.glsp.example.bigraph.extension.popp.event;

import org.eclipse.glsp.example.bigraph.extension.popp.types.*;

/**
 * A domain-model change, emitted while it is applied. The event's fields are authoritative: a listener should read
 * them rather than query the model, which can still be mid-change (see {@link NodeChangedParent}).
 */
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

    /**
     * The node moved from {@code oldParent} to {@code newParent} ({@code null}: root). When it becomes a root, the event
     * is emitted before the node's parent field and the old parent's child list are updated, so use these fields.
     */
    record NodeChangedParent(NodeKind kind, TreeNode<?> node, TreeNode<?> oldParent, TreeNode<?> newParent) implements POPPEvent {
    }

    record RelationCreated(Relation relation) implements POPPEvent {
    }

    record RelationRemoved(Relation relation) implements POPPEvent {
    }
}
