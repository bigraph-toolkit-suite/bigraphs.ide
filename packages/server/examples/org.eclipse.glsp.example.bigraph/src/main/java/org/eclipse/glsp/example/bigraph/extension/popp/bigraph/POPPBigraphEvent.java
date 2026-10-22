package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;

import java.util.List;

/**
 * A structural change of the bigraph, announced by {@link POPPBigraph} to its {@link POPPBigraphListener}s after the
 * change (or, where the name says so, just before it). The counterpart of
 * {@link org.eclipse.glsp.example.bigraph.extension.popp.types.POPPEvent} one level down: the domain model announces
 * domain changes, {@link POPPBigraph} the bigraph changes they result in.
 *
 * <p>Every bigraph node is announced: the container, domain nodes, gate nodes and stubs, so a listener's picture
 * matches the stored bigraph. Stubs are announced with their relation: added through {@link NodeAdded} and
 * {@link RelationAdded}, removed only through {@link RelationRemoving}.</p>
 *
 * <p>Ordering guarantees:</p>
 * <ul>
 *   <li>A node is announced after its parent, so {@code parent} is always already known.</li>
 *   <li>A node's gate nodes and relations are removed, and announced, before the node itself.</li>
 * </ul>
 *
 * <p>Events may repeat for the same entity (e.g. the POPP container is announced on every node creation), so
 * listeners must be idempotent.</p>
 */
public sealed interface POPPBigraphEvent {

    /** {@code node} was added under {@code parent} (the root for the POPP container). */
    record NodeAdded(BigraphEntity.NodeEntity<DynamicControl> node, BigraphEntity<?> parent) implements POPPBigraphEvent {
    }

    /** {@code node} was removed from the bigraph, so the entity is already detached. */
    record NodeRemoved(BigraphEntity.NodeEntity<DynamicControl> node) implements POPPBigraphEvent {
    }

    /** {@code child} now sits under {@code newParent} in the place graph. */
    record NodeReparented(BigraphEntity.NodeEntity<DynamicControl> child,
                          BigraphEntity.NodeEntity<DynamicControl> newParent) implements POPPBigraphEvent {
    }

    /** {@code node} was moved in the diagram view. */
    record PositionChanged(BigraphEntity.NodeEntity<DynamicControl> node, double x, double y) implements POPPBigraphEvent {
    }

    /** A relation edge and its stub nodes were created. */
    record RelationAdded(BigraphEntity.Edge edge, List<? extends BigraphEntity<?>> stubs) implements POPPBigraphEvent {
    }

    /** A relation edge and its stubs are about to be removed: announced BEFORE removal, while they are still resolvable. */
    record RelationRemoving(BigraphEntity.Edge edge, List<? extends BigraphEntity<?>> stubs) implements POPPBigraphEvent {
    }
}
