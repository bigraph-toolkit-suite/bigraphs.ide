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

package org.eclipse.glsp.example.bigraph.views;

import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;
import org.eclipse.glsp.graph.GModelRoot;

import java.util.Collection;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.Edge;
import org.bigraphs.framework.core.impl.BigraphEntity.InnerName;
import org.bigraphs.framework.core.impl.BigraphEntity.Link;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.OuterName;
import org.bigraphs.framework.core.impl.BigraphEntity.Port;
import org.bigraphs.framework.core.impl.BigraphEntity.SiteEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.RootEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.eclipse.glsp.graph.GNode;
import org.eclipse.glsp.graph.GPoint;

import java.util.Collections;
import java.util.Optional;


public abstract class BigraphView {
    protected final IBigraphModelState modelState;

    /**
     * The GModel container this view owns. Bound up-front by
     * {@code BigraphModelState.initializeBigraphModel} to the bigraph
     * variant's persistent root, so callbacks like {@link #onAddNode}
     * always write into the bigraph tree — regardless of which
     * variant is currently active and GLSP-visible. The "hot standby"
     * design relies on this invariant: the bigraph GModel keeps
     * itself consistent in the background even when the user is
     * editing in another variant's view.
     */
    protected GModelRoot ownerRoot;

    public BigraphView(IBigraphModelState modelState) {
        this.modelState = modelState;
    }

    /**
     * Binds the GModel container that all {@code on*} callbacks of
     * this view operate on. Called once by the model state during
     * initialization. Subsequent calls are allowed but rare — the
     * usual lifecycle is "set once, kept for the diagram session".
     */
    public void setOwnerRoot(GModelRoot ownerRoot) {
        this.ownerRoot = ownerRoot;
    }

    /** The GModel container this view writes into. */
    public GModelRoot getOwnerRoot() {
        return this.ownerRoot;
    }

    /**
     * Resets the view's internal state. Called before re-initializing.
     * Subclasses should override to reset counters, registries, etc.
     */
    public void reset() {
        // Default: do nothing. Subclasses override as needed.
    }

    /**
     * Finds the deepest (most nested) bigraph node at the given position.
     * Used to determine the parent for newly created nodes.
     * 
     * @param x The x coordinate of the click position
     * @param y The y coordinate of the click position
     * @return The deepest NodeEntity at the position, or null if clicking on empty canvas
     */
    public abstract NodeEntity<DynamicControl> findDeepestNodeOnPosition(double x, double y);
    
    /**
     * Finds a root at the given position.
     * Used to determine if the user clicked on an existing root (circle shape).
     * 
     * @param x The x coordinate of the click position
     * @param y The y coordinate of the click position
     * @return The RootEntity at the position, or null if not clicking on a root
     */
    public abstract RootEntity findRootOnPosition(double x, double y);

    /**
     * Finds a site at the given position.
     * Used to determine if the user dropped a node onto a site.
     *
     * @param x The x coordinate of the click position
     * @param y The y coordinate of the click position
     * @return The SiteEntity at the position, or null if not clicking on a site
     */
    public abstract SiteEntity findSiteOnPosition(double x, double y);

    
    public abstract void onAddRoot(RootEntity root);
    public abstract void onDeleteRoot(String rootId);
    
    /**
     * Adds a node to the view and returns the created GNode.
     * 
     * @param node The bigraph node entity
     * @param parent The parent entity (node or root)
     * @param control The control type
     * @return The created GNode
     */
    public abstract GNode onAddNode(NodeEntity<DynamicControl> node, BigraphEntity<?> parent, DynamicControl control);
    public abstract void onDeleteNode(String nodeId);
    public abstract void onMoveNode(String nodeId, String newParentId);
    
    /**
     * Adds a site (place-graph placeholder) to the view and returns the created GNode.
     *
     * @param site The bigraph site entity
     * @param parent The parent entity (root or node)
     * @param indexUnderParent Zero-based index of this site among its siblings under the same parent (for layout)
     * @return The created GNode representing the site
     */
    public abstract GNode onAddSite(SiteEntity site, BigraphEntity<?> parent, int indexUnderParent);
    public abstract void onDeleteSite(String siteId);
    public abstract void onRename(String elementId, String newName);

    // === LINK GRAPH CALLBACKS ===
    
    /**
     * Adds an edge to the view with automatic positioning.
     * Used during model initialization from bigraph.
     */
    public abstract void onAddEdge(Edge edge, Collection<BigraphEntity<?>> connectedPoints);
    
    /**
     * Adds an edge to the view at the specified position.
     * Used during interactive creation.
     */
    public GNode onAddEdge(Edge edge, Optional<GPoint> position) {
        return onAddEdge(edge, Collections.emptyList(), position);
    }
    
    /**
     * Adds an edge to the view with connected points and optional position.
     */
    public abstract GNode onAddEdge(Edge edge, Collection<BigraphEntity<?>> connectedPoints, Optional<GPoint> position);
    
    public abstract void onDeleteEdge(String edgeId);
    
    /**
     * Adds an outer name to the view with automatic positioning.
     * Used during model initialization from bigraph.
     */
    public abstract GNode onAddOuterName(OuterName outerName, Collection<BigraphEntity<?>> connectedPoints);
    
    /**
     * Adds an outer name to the view at the specified position.
     * Used during interactive creation.
     */
    public GNode onAddOuterName(OuterName outerName, Optional<GPoint> position) {
        return onAddOuterName(outerName, Collections.emptyList(), position);
    }
    
    /**
     * Adds an outer name to the view with connected points and optional position.
     */
    public abstract GNode onAddOuterName(OuterName outerName, Collection<BigraphEntity<?>> connectedPoints, Optional<GPoint> position);
    
    public abstract void onDeleteOuterName(String outerNameId);
    
    /**
     * Adds an inner name to the view with automatic positioning.
     * Used during model initialization from bigraph.
     */
    public abstract GNode onAddInnerName(InnerName innerName, Link connectedLink);
    
    /**
     * Adds an inner name to the view at the specified position.
     * Used during interactive creation.
     */
    public GNode onAddInnerName(InnerName innerName, Optional<GPoint> position) {
        return onAddInnerName(innerName, null, position);
    }
    
    /**
     * Adds an inner name to the view with connected link and optional position.
     */
    public abstract GNode onAddInnerName(InnerName innerName, Link connectedLink, Optional<GPoint> position);
    
    public abstract void onDeleteInnerName(String innerNameId);
    
    public abstract void onAddPort(NodeEntity<DynamicControl> node, Port port, Link connectedLink);
    public abstract void onConnectPort(String nodeId, int portIndex, String linkId);
    public abstract void onDisconnectPort(String nodeId, int portIndex);

    // === LOOKUP METHODS ===
    
    /**
     * Looks up a bigraph entity by its GModel ID.
     *
     * @param gModelId The GModel element ID
     * @return An Optional containing the corresponding BigraphEntity, or empty if not found
     */
    public abstract Optional<BigraphEntity<?>> getBigraphEntityForGModelId(String gModelId);

    /**
     * Looks up the GModel ID for a given bigraph entity.
     *
     * @param entity The BigraphEntity
     * @return The GModel ID, or empty if not found
     */
    public abstract Optional<String> getGModelIdForEntity(BigraphEntity<?> entity);

    /**
     * Returns true if the node with the given GModel ID is nested inside a site
     * (either directly or transitively via parent nodes).
     */
    public abstract boolean isNodeNestedInSite(String gModelId);

    /**
     * Re-computes the bounding box of the site that contains the given node and resizes it.
     * Called after a node inside a site is moved.
     */
    public abstract void refreshSiteContainingNode(String nodeGModelId);

}
