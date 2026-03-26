/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.glsp.example.bigraph.handler;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.eclipse.elk.alg.force.options.ForceMetaDataProvider;
import org.eclipse.elk.alg.force.options.StressMetaDataProvider;
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider;
import org.eclipse.elk.alg.mrtree.options.MrTreeMetaDataProvider;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.glsp.example.bigraph.actions.AutoLayoutAction;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.BigraphModelState;
import org.eclipse.glsp.example.bigraph.model.BigraphModelTypes;
import org.eclipse.glsp.example.bigraph.model.BigraphNodeIdentity;
import org.eclipse.glsp.graph.GBoundsAware;
import org.eclipse.glsp.graph.GDimension;
import org.eclipse.glsp.graph.GGraph;
import org.eclipse.glsp.graph.GModelElement;
import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GPoint;
import org.eclipse.glsp.graph.GraphFactory;
import org.eclipse.glsp.layout.ElkLayoutEngine;
import org.eclipse.glsp.layout.GLSPLayoutConfigurator;
import org.eclipse.glsp.server.actions.AbstractActionHandler;
import org.eclipse.glsp.server.actions.Action;
import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.actions.SetDirtyStateAction;
import org.eclipse.glsp.server.features.core.model.UpdateModelAction;

import com.google.inject.Inject;

/**
 * Handles {@link AutoLayoutAction} by running the ELK layered layout algorithm
 * on the current GModel and returning an animated {@link UpdateModelAction}.
 *
 * ELK directly updates the {@code position} and {@code size} fields on the
 * existing {@link GModelRoot} elements, so no model rebuild is needed.
 * Setting {@code animate=true} on the returned action makes Sprotty animate
 * the node transitions on the client.
 */
public class AutoLayoutActionHandler extends AbstractActionHandler<AutoLayoutAction> {

    private static final Logger LOGGER = LogManager.getLogger(AutoLayoutActionHandler.class);

    static {
        // Register all supported ELK algorithm metadata once at class load time.
        ElkLayoutEngine.initialize(
            new LayeredMetaDataProvider(),
            new MrTreeMetaDataProvider(),
            new ForceMetaDataProvider(),
            new StressMetaDataProvider()
        );
    }

    @Inject
    protected BigraphModelState modelState;

    @Inject
    protected ActionDispatcher actionDispatcher;

    // Injected by Guice — ElkLayoutEngine uses @Inject for its own GModelState
    @Inject
    protected ElkLayoutEngine layoutEngine;

    @Override
    public List<Action> executeAction(final AutoLayoutAction action) {
        final String algorithm = action.getAlgorithm() != null ? action.getAlgorithm() : "layered";
        LOGGER.info("AutoLayoutAction — running ELK '{}' layout", algorithm);

        final GModelRoot root = modelState.getRoot();
        if (!(root instanceof GGraph)) {
            LOGGER.warn("AutoLayout: root is not a GGraph, skipping.");
            return List.of();
        }

        final GLSPLayoutConfigurator configurator = new GLSPLayoutConfigurator();
        configurator.configureByType("graph")
            .setProperty(CoreOptions.ALGORITHM, algorithm)
            .setProperty(CoreOptions.DIRECTION, Direction.DOWN)
            .setProperty(CoreOptions.SPACING_NODE_NODE, 40.0)
            .setProperty(CoreOptions.PADDING, new ElkPadding(30.0));

        layoutEngine.layout((GGraph) root, configurator);

        if (action.isBigraphStandard()) {
            applyBigraphStandardPositions(root);
        }

        // Sync ELK-computed positions back into BigraphMetaInformation so
        // saveSourceModel() persists them to the .bigraph-meta file on Ctrl+S.
        syncPositionsToMeta(root, modelState.getMetaInformation());

        // Mark dirty so VSCode enables Ctrl+S.
        actionDispatcher.dispatch(new SetDirtyStateAction(true, "operation"));

        LOGGER.info("AutoLayout complete — dispatching animated UpdateModelAction");
        return List.of(new UpdateModelAction(root, true));
    }

    /**
     * After ELK has laid out the main bigraph structure, repositions outer names
     * along the top edge and inner names along the bottom edge — following the
     * standard bigraph visual convention (outer names = interface out = top,
     * inner names = interface in = bottom).
     *
     * Inner/outer names are removed from ELK's influence by moving them after the
     * layout run, so they don't distort the node arrangement.
     */
    private void applyBigraphStandardPositions(final GModelRoot root) {
        final double padding = 30.0;
        final double nameSpacing = 60.0;
        final double nameNodeWidth = 40.0;
        final double nameNodeHeight = 30.0;

        // Collect outer names, inner names, and compute the bounding box of everything else.
        final java.util.List<GModelElement> outerNames = new java.util.ArrayList<>();
        final java.util.List<GModelElement> innerNames = new java.util.ArrayList<>();
        double maxY = 0;
        double totalWidth = 0;

        for (GModelElement element : root.getChildren()) {
            final String type = element.getType();
            if (BigraphModelTypes.OUTER_NAME.equals(type)) {
                outerNames.add(element);
            } else if (BigraphModelTypes.INNER_NAME.equals(type)) {
                innerNames.add(element);
            } else if (element instanceof GBoundsAware) {
                final GPoint pos = ((GBoundsAware) element).getPosition();
                final GDimension size = ((GBoundsAware) element).getSize();
                if (pos != null && size != null) {
                    maxY = Math.max(maxY, pos.getY() + size.getHeight());
                    totalWidth = Math.max(totalWidth, pos.getX() + size.getWidth());
                }
            }
        }

        // Centre the name rows over the node area.
        final double rowWidth = Math.max(totalWidth, 100);

        // Place outer names at the very top (above the ELK-laid-out nodes).
        // Shift all non-name nodes down to make room.
        final double outerRowHeight = outerNames.isEmpty() ? 0 : nameNodeHeight + padding;
        if (!outerNames.isEmpty()) {
            // Push all laid-out nodes down
            for (GModelElement element : root.getChildren()) {
                if (element.getType().equals(BigraphModelTypes.OUTER_NAME)
                        || element.getType().equals(BigraphModelTypes.INNER_NAME)) {
                    continue;
                }
                if (element instanceof GBoundsAware) {
                    final GPoint pos = ((GBoundsAware) element).getPosition();
                    if (pos != null) {
                        pos.setY(pos.getY() + outerRowHeight);
                    }
                }
            }
            maxY += outerRowHeight;
        }

        // Distribute outer names evenly along the top.
        positionNameRow(outerNames, padding, rowWidth, nameSpacing, nameNodeWidth, 0);

        // Distribute inner names evenly along the bottom.
        positionNameRow(innerNames, padding, rowWidth, nameSpacing, nameNodeWidth, maxY + padding);
    }

    private void positionNameRow(final java.util.List<GModelElement> names,
                                  final double padding,
                                  final double rowWidth,
                                  final double spacing,
                                  final double nodeWidth,
                                  final double y) {
        if (names.isEmpty()) {
            return;
        }
        final int count = names.size();
        final double totalNamesWidth = count * nodeWidth + (count - 1) * spacing;
        double startX = padding + (rowWidth - totalNamesWidth) / 2.0;
        if (startX < padding) {
            startX = padding;
        }
        for (int i = 0; i < count; i++) {
            final GModelElement el = names.get(i);
            if (el instanceof GBoundsAware) {
                final GPoint pos = ((GBoundsAware) el).getPosition();
                if (pos == null) {
                    final GPoint newPos = GraphFactory.eINSTANCE.createGPoint();
                    newPos.setX(startX + i * (nodeWidth + spacing));
                    newPos.setY(y);
                    ((GBoundsAware) el).setPosition(newPos);
                } else {
                    pos.setX(startX + i * (nodeWidth + spacing));
                    pos.setY(y);
                }
            }
        }
    }

    /**
     * Iterates all GModel elements and writes their ELK-computed positions back
     * into {@link BigraphMetaInformation}.
     *
     * Node positions use property-based keys (node name + control type) so they
     * can be loaded correctly even if glspNodeId is regenerated between editor
     * opens. Other element types keep their existing keys.
     */
    private void syncPositionsToMeta(final GModelRoot root, final BigraphMetaInformation meta) {
        if (meta == null) {
            return;
        }
        for (GModelElement element : root.getChildren()) {
            if (!(element instanceof GBoundsAware)) {
                continue;
            }
            final GPoint pos = ((GBoundsAware) element).getPosition();
            if (pos == null) {
                continue;
            }
            final GPoint copy = GraphFactory.eINSTANCE.createGPoint();
            copy.setX(pos.getX());
            copy.setY(pos.getY());
            final String gModelId = element.getId();

            final BigraphEntity<?> entity = modelState.getActiveView().getBigraphEntityForGModelId(gModelId)
                    .orElse(null);
            if (entity == null) {
                continue;
            }

            if (entity instanceof BigraphEntity.NodeEntity) {
                if (modelState.getActiveView().isNodeNestedInSite(gModelId)) {
                    final BigraphEntity.NodeEntity<?> node = (BigraphEntity.NodeEntity<?>) entity;
                    final var control = node.getControl();
                    final String controlName = control != null && control.getNamedType() != null
                        ? control.getNamedType().stringValue()
                        : "";
                    final String nodePositionKey = BigraphNodeIdentity.toNodePositionKey(
                        node.getName(), controlName);
                    meta.getNodeRelativePositions().put(nodePositionKey, copy);
                } else {
                    final BigraphEntity.NodeEntity<?> node = (BigraphEntity.NodeEntity<?>) entity;
                    final var control = node.getControl();
                    final String controlName = control != null && control.getNamedType() != null
                        ? control.getNamedType().stringValue()
                        : "";
                    final String nodePositionKey = BigraphNodeIdentity.toNodePositionKey(
                        node.getName(), controlName);
                    meta.getNodePositions().put(nodePositionKey, copy);
                }
            } else if (entity instanceof BigraphEntity.SiteEntity) {
                final String key = String.valueOf(((BigraphEntity.SiteEntity) entity).getIndex());
                meta.getSitePositions().put(key, copy);
            } else if (entity instanceof BigraphEntity.RootEntity) {
                final String key = String.valueOf(((BigraphEntity.RootEntity) entity).getIndex());
                meta.getRootPositions().put(key, copy);
            } else if (entity instanceof BigraphEntity.Edge) {
                meta.getEdgePositions().put(((BigraphEntity.Edge) entity).getName(), copy);
            } else if (entity instanceof BigraphEntity.OuterName) {
                meta.getOuterNamePositions().put(((BigraphEntity.OuterName) entity).getName(), copy);
            } else if (entity instanceof BigraphEntity.InnerName) {
                meta.getInnerNamePositions().put(((BigraphEntity.InnerName) entity).getName(), copy);
            }
        }
    }
}
