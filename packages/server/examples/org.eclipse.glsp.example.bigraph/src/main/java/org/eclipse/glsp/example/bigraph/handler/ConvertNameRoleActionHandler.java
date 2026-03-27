package org.eclipse.glsp.example.bigraph.handler;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.Edge;
import org.bigraphs.framework.core.impl.BigraphEntity.InnerName;
import org.bigraphs.framework.core.impl.BigraphEntity.Link;
import org.bigraphs.framework.core.impl.BigraphEntity.OuterName;
import org.bigraphs.framework.core.impl.BigraphEntity.Port;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.eclipse.glsp.example.bigraph.actions.ConvertNameRoleAction;
import org.eclipse.glsp.example.bigraph.handler.support.BigraphNotifications;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.views.BigraphView;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.actions.SetDirtyStateAction;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;
import org.eclipse.glsp.server.model.GModelState;

import com.google.inject.Inject;

public class ConvertNameRoleActionHandler extends AbstractActionHandler<ConvertNameRoleAction> {
    private static final Logger LOGGER = LogManager.getLogger(ConvertNameRoleActionHandler.class);

    @Inject
    protected GModelState modelState;

    @Inject
    protected ActionDispatcher actionDispatcher;

    @Override
    public List<Action> executeAction(final ConvertNameRoleAction action) {
        if (!(modelState instanceof BigraphModelState)) {
            return List.of();
        }
        final BigraphModelState state = (BigraphModelState) modelState;
        final BigraphView view = state.getActiveView();
        final PureBigraphMutable bigraph = state.getMutableBigraph();
        if (bigraph == null) {
            return List.of();
        }

        final String targetRole = action.getTargetRole();
        final BigraphEntity<?> entity = view.getBigraphEntityForGModelId(action.getElementId()).orElse(null);
        if (entity == null || targetRole == null) {
            return List.of();
        }

        try {
            if ("outer".equalsIgnoreCase(targetRole) && entity instanceof InnerName) {
                makeOuter((InnerName) entity, action.getElementId(), bigraph, state.getMetaInformation(), view);
            } else if ("inner".equalsIgnoreCase(targetRole) && entity instanceof OuterName) {
                makeInner((OuterName) entity, action.getElementId(), bigraph, state.getMetaInformation(), view);
            } else {
                return List.of();
            }
        } catch (final Exception e) {
            LOGGER.error("Failed to convert name role for {}", action.getElementId(), e);
            BigraphNotifications.notifyError(actionDispatcher, "Could not convert name role: " + e.getMessage());
            return List.of();
        }

        final var root = state.getRoot();
        root.setRevision(root.getRevision() + 1);
        actionDispatcher.dispatch(new SetDirtyStateAction(true, "operation"));
        return List.of(new UpdateModelAction(root, false));
    }

    private void makeOuter(final InnerName inner, final String innerId, final PureBigraphMutable bigraph,
            final BigraphMetaInformation meta, final BigraphView view) {
        final String name = inner.getName();
        if (bigraph.getOuterNames().stream().anyMatch(o -> name.equals(o.getName()))) {
            throw new IllegalStateException("An outer name '" + name + "' already exists.");
        }

        final Link previousLink = bigraph.getLinkOfPoint(inner);
        final String previousLinkId = previousLink != null ? view.getGModelIdForEntity(previousLink).orElse(null) : null;
        final OuterName newOuter = bigraph.addOuterName(name);

        // If the inner was attached to an edge, transfer all edge points to the new outer.
        if (previousLink instanceof Edge) {
            final List<BigraphEntity<?>> points = new ArrayList<>(bigraph.getPointsFromLink(previousLink));
            for (final BigraphEntity<?> p : points) {
                if (p == inner) {
                    continue;
                }
                if (p instanceof Port) {
                    bigraph.disconnectPort((Port) p);
                    bigraph.connectPortToLink((Port) p, newOuter);
                } else if (p instanceof InnerName) {
                    bigraph.disconnectInnerName((InnerName) p);
                    bigraph.connectInnerNameToLink((InnerName) p, newOuter);
                }
            }
            bigraph.removeEdge((Edge) previousLink);
        } else if (previousLink instanceof OuterName) {
            // Keep existing outer links untouched; only convert this interface endpoint role.
            bigraph.disconnectInnerName(inner);
        } else {
            bigraph.disconnectInnerName(inner);
        }

        bigraph.removeInnerName(inner);
        migrateInnerToOuterPosition(meta, name);

        view.onDeleteInnerName(innerId);
        if (previousLink instanceof Edge && previousLinkId != null) {
            view.onDeleteEdge(previousLinkId);
        }
        view.onAddOuterName(newOuter, new ArrayList<>(bigraph.getPointsFromLink(newOuter)));
    }

    private void makeInner(final OuterName outer, final String outerId, final PureBigraphMutable bigraph,
            final BigraphMetaInformation meta, final BigraphView view) {
        final String name = outer.getName();
        if (bigraph.getInnerNames().stream().anyMatch(i -> name.equals(i.getName()))) {
            throw new IllegalStateException("An inner name '" + name + "' already exists.");
        }
        final InnerName newInner = bigraph.addInnerName(name);
        final List<BigraphEntity<?>> points = new ArrayList<>(bigraph.getPointsFromLink(outer));
        Edge replacementEdge = null;

        // An outer name can be a link itself; an inner name cannot. If the outer
        // currently carries points, replace it with an edge and rewire everything.
        if (!points.isEmpty()) {
            replacementEdge = bigraph.addEdge(createUniqueEdgeName(name + "_edge", bigraph));
            for (final BigraphEntity<?> p : points) {
                if (p instanceof Port) {
                    bigraph.disconnectPort((Port) p);
                    bigraph.connectPortToLink((Port) p, replacementEdge);
                } else if (p instanceof InnerName) {
                    bigraph.disconnectInnerName((InnerName) p);
                    bigraph.connectInnerNameToLink((InnerName) p, replacementEdge);
                }
            }
            bigraph.connectInnerNameToLink(newInner, replacementEdge);
        }
        bigraph.removeOuterName(outer);
        migrateOuterToInnerPosition(meta, name);

        view.onDeleteOuterName(outerId);
        view.onAddInnerName(newInner, replacementEdge);
        if (replacementEdge != null) {
            view.onAddEdge(replacementEdge, new ArrayList<>(bigraph.getPointsFromLink(replacementEdge)));
        }
    }

    private String createUniqueEdgeName(final String baseName, final PureBigraphMutable bigraph) {
        String candidate = baseName;
        int i = 1;
        while (true) {
            final String current = candidate;
            final boolean exists = bigraph.getEdges().stream().anyMatch(e -> current.equals(e.getName()));
            if (!exists) {
                break;
            }
            candidate = baseName + "_" + i++;
        }
        return candidate;
    }

    private void migrateInnerToOuterPosition(final BigraphMetaInformation meta, final String name) {
        if (meta == null || name == null) {
            return;
        }
        final GPoint old = meta.getInnerNamePositions().remove(name);
        if (old != null) {
            meta.getOuterNamePositions().put(name, old);
        }
    }

    private void migrateOuterToInnerPosition(final BigraphMetaInformation meta, final String name) {
        if (meta == null || name == null) {
            return;
        }
        final GPoint old = meta.getOuterNamePositions().remove(name);
        if (old != null) {
            meta.getInnerNamePositions().put(name, old);
        }
    }
}
