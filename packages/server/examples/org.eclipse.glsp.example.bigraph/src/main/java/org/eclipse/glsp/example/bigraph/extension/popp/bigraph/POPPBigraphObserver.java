package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;

import java.util.List;

/**
 * Notified by {@link POPPBigraph} after (or, where noted, just before) it changes the shape of the
 * underlying bigraph. All methods are no-ops by default so implementations override only what they need.
 *
 * <p>Every bigraph node is announced, including the marker nodes (kind, role) that live as children of
 * tree nodes: the observer's picture is meant to match the stored bigraph exactly.</p>
 *w
 * <p>Ordering guarantees:</p>
 * <ul>
 *   <li>A node is announced after its parent, so {@code parent} is always already known.</li>
 *   <li>A node's children (markers, stubs) are removed, and announced as removed, before the node itself.</li>
 * </ul>
 *
 * <p>Notifications may repeat for the same entity (e.g. the POPP container is announced on every
 * node creation), so implementations must be idempotent.</p>
 */
public interface POPPBigraphObserver {

    /** {@code node} was added under {@code parent} (the root for the POPP container). Called after the add. */
    default void onNodeAdded(BigraphEntity.NodeEntity<DynamicControl> node, BigraphEntity<?> parent) {}

    /** {@code node} was removed from the bigraph. Called AFTER removal, so the entity is already detached. */
    default void onNodeRemoved(BigraphEntity.NodeEntity<DynamicControl> node) {}

    /** {@code child} now sits under {@code newParent} in the place graph. Called after the move. */
    default void onNodeReparented(BigraphEntity.NodeEntity<DynamicControl> child,
                                  BigraphEntity.NodeEntity<DynamicControl> newParent) {}

    default void onPositionChanged(BigraphEntity.NodeEntity<DynamicControl> node, double x, double y) {}

    /** A relation edge and its stub nodes were created. Called after creation. */
    default void onRelationAdded(BigraphEntity.Edge edge, List<? extends BigraphEntity<?>> stubs) {}

    /** A relation edge and its stubs are about to be removed. Called BEFORE removal, while they are still resolvable. */
    default void onRelationRemoving(BigraphEntity.Edge edge, List<? extends BigraphEntity<?>> stubs) {}
}