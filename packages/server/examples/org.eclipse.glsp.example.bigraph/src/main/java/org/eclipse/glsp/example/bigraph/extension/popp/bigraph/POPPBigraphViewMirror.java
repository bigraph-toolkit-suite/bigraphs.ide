package org.eclipse.glsp.example.bigraph.extension.popp.bigraph;

import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.example.bigraph.views.BigraphView;
import org.eclipse.glsp.graph.GEdge;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.graph.GraphFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Keeps the core "bigraph" variant's {@link BigraphView} mirrored. That view only updates through
 * its public {@code on*} callbacks and has no idea POPP exists, so every structural change
 * {@link POPPBigraph} reports is translated into the matching callback here.
 *
 * <p>The view is looked up lazily on every event since it can come and go (variant switches).</p>
 */
public class POPPBigraphViewMirror implements POPPBigraphObserver {
    private final Supplier<Optional<BigraphView>> viewSupplier;

    public POPPBigraphViewMirror(final Supplier<Optional<BigraphView>> viewSupplier) {
        this.viewSupplier = viewSupplier;
    }

    @Override
    public void onNodeAdded(final BigraphEntity.NodeEntity<DynamicControl> node, final BigraphEntity<?> parent) {
        viewSupplier.get().ifPresent(view -> {
            if (view.getGModelIdForEntity(node).isEmpty()) {
                view.onAddNode(node, parent, node.getControl());
            }
        });
    }

    @Override
    public void onNodeRemoved(final BigraphEntity.NodeEntity<DynamicControl> node) {
        viewSupplier.get().ifPresent(view ->
                view.getGModelIdForEntity(node).ifPresent(view::onDeleteNode));
    }

    /** Re-targets an existing place-edge to the new parent, or creates one if the child never had one. */
    @Override
    public void onNodeReparented(final BigraphEntity.NodeEntity<DynamicControl> child,
                                 final BigraphEntity.NodeEntity<DynamicControl> newParent) {
        viewSupplier.get().ifPresent(view -> view.getGModelIdForEntity(child).ifPresent(childId ->
                view.getGModelIdForEntity(newParent).ifPresent(parentId ->
                        view.onMoveNode(childId, parentId))));
    }

    @Override
    public void onPositionChanged(final BigraphEntity.NodeEntity<DynamicControl> node, final double x, final double y) {
        viewSupplier.get().ifPresent(view -> view.getGModelIdForEntity(node).ifPresent(viewId ->
                findGNode(view, viewId).ifPresent(gNode -> {
                    GPoint point = GraphFactory.eINSTANCE.createGPoint();
                    point.setX(x);
                    point.setY(y);
                    gNode.setPosition(point);
                })));
    }

    @Override
    public void onRelationAdded(final BigraphEntity.Edge edge, final List<? extends BigraphEntity<?>> stubs) {
        viewSupplier.get().ifPresent(view -> {
            // Core's LinkRenderer only wires Port/InnerName/OuterName points, not plain stub nodes
            // (see LinkRenderer#createLinkConnections), so the hyperedge-to-stub links are built here instead.
            GNode hyperEdgeNode = view.onAddEdge(edge, List.of(), Optional.empty());
            stubs.forEach(stub -> view.getGModelIdForEntity(stub).ifPresent(targetId -> {
                GEdge linkConnection = GraphFactory.eINSTANCE.createGEdge();
                linkConnection.setId("popp_link_conn_" + UUID.randomUUID());
                linkConnection.setType("bigraph:link-connection");
                linkConnection.setSourceId(hyperEdgeNode.getId());
                linkConnection.setTargetId(targetId);
                linkConnection.getCssClasses().add("bigraph-link-connection");
                view.getOwnerRoot().getChildren().add(linkConnection);
            }));
        });
    }

    @Override
    public void onRelationRemoving(final BigraphEntity.Edge edge, final List<? extends BigraphEntity<?>> stubs) {
        viewSupplier.get().ifPresent(view -> {
            view.getGModelIdForEntity(edge).ifPresent(view::onDeleteEdge);
            stubs.forEach(stub -> view.getGModelIdForEntity(stub).ifPresent(view::onDeleteNode));
        });
    }

    private Optional<GNode> findGNode(final BigraphView view, final String gModelId) {
        return view.getOwnerRoot().getChildren().stream()
                .filter(GNode.class::isInstance)
                .map(GNode.class::cast)
                .filter(n -> n.getId().equals(gModelId))
                .findFirst();
    }
}

