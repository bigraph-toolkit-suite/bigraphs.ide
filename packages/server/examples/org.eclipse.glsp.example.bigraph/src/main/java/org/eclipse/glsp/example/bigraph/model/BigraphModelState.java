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

package org.eclipse.glsp.example.bigraph.model;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bigraphs.framework.core.impl.BigraphEntity;
import org.bigraphs.framework.core.impl.signature.DynamicControl;
import org.bigraphs.framework.core.impl.pure.PureBigraph;
import org.bigraphs.framework.core.impl.pure.PureBigraphBuilder;
import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.bigraphs.framework.core.impl.signature.DynamicSignature;
import org.eclipse.glsp.graph.GModelRoot;
import org.eclipse.glsp.server.model.DefaultGModelState;
import org.eclipse.glsp.example.bigraph.extensions.CoreIdeExtension;
import org.eclipse.glsp.example.bigraph.views.BigraphView;
import org.eclipse.glsp.example.bigraph.views.graph.GraphBigraphView;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.glsp.graph.GraphFactory;
import org.eclipse.glsp.graph.GGraph;
import org.bigraphs.framework.core.impl.BigraphEntity.*;
import org.bigraphs.framework.core.impl.BigraphEntity.Edge;
import org.bigraphs.framework.core.impl.BigraphEntity.InnerName;
import org.bigraphs.framework.core.impl.BigraphEntity.Link;
import org.bigraphs.framework.core.impl.BigraphEntity.NodeEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.OuterName;
import org.bigraphs.framework.core.impl.BigraphEntity.Port;
import org.bigraphs.framework.core.impl.BigraphEntity.RootEntity;
import org.bigraphs.framework.core.impl.BigraphEntity.SiteEntity;
import java.util.Collection;



/**
 * Extended model state that maintains both the GLSP GModel and the underlying
 * Bigraph Framework PureBigraph model. This serves as the single source of truth
 * for the runtime model state.
 */
public class BigraphModelState extends DefaultGModelState implements IBigraphModelState {

    // mappings
    private HashMap<String,String> gmodelToBigraphMap = new HashMap<>();

    // view
    private BigraphView currentView = new GraphBigraphView(this);

    // bigraph model
    private PureBigraphMutable mutableBigraph = null;

    private String sourceFilePath = null;
    private BigraphMetaInformation metaInformation = null;

    /**
     * Active variant id — what the user is currently looking at and editing.
     * On load this is initialised from {@code metaInformation.modelType} (the
     * "default" variant). When the user later switches via the per-diagram
     * tab-bar between e.g. the BT view and its bigraph view, this changes
     * while the meta value stays put. Gating (handlers, palette) keys off
     * this field, not the meta.
     */
    private String activeVariantId = null;

    /**
     * Holds a merged signature produced by a composition operation.
     * Null when no composition has been performed since the last save.
     * Written to disk in {@code BigraphXMIModelStorage.saveSourceModel} on Ctrl+S.
     */
    private DynamicSignature pendingSignature = null;

    /**
     * Variants this diagram session can switch between. Always
     * contains the canonical bigraph variant (every file is at least
     * a bigraph under the hood) plus the variant declared by the
     * {@code .bigraph-meta} file when it differs from "bigraph".
     */
    private List<String> availableVariantIds = List.of(CoreIdeExtension.BIGRAPH_VARIANT_ID);

    /**
     * One persistent {@link GModelRoot} per available variant — the
     * "hot standby" backbone of the two-GModel design.
     *
     * <p>Each variant gets its own container that is kept in sync with
     * the underlying domain model continuously. {@link #getRoot()}
     * (inherited from {@code DefaultGModelState}) returns whichever
     * one is currently bound by {@code updateRoot(...)} — i.e. the one
     * for {@link #activeVariantId}. The others are still populated and
     * up-to-date, just not visible to GLSP.</p>
     *
     * <p>Switching variants is therefore a cheap pointer swap (see
     * {@link #swapActiveVariantTo(String)}), not a rebuild.</p>
     */
    private final Map<String, GModelRoot> variantRoots = new HashMap<>();


    public GModelRoot initializeBigraphModel(PureBigraphMutable bigraph, BigraphMetaInformation meta) {
        this.mutableBigraph = bigraph;
        this.metaInformation = meta;
        // Active variant defaults to the persisted modelType, or the core
        // bigraph variant when the meta is absent / blank.
        String metaType = meta == null ? null : meta.getModelType();
        this.activeVariantId = (metaType == null || metaType.isBlank())
                ? CoreIdeExtension.BIGRAPH_VARIANT_ID
                : metaType;
        this.availableVariantIds = computeAvailableVariants(this.activeVariantId);

        // Eagerly create one empty container per available variant. The
        // bigraph view is wired to its own container up-front so any
        // later GraphBigraphView callbacks target the right tree
        // regardless of which variant is currently active.
        this.variantRoots.clear();
        for (String variantId : this.availableVariantIds) {
            this.variantRoots.put(variantId, createEmptyVariantRoot(variantId));
        }
        this.currentView.setOwnerRoot(this.variantRoots.get(CoreIdeExtension.BIGRAPH_VARIANT_ID));
        // The bigraph variant always starts populated (every file *is*
        // a bigraph under the hood). Extension-owned variants — e.g.
        // behavior-tree — are populated later via onModelLoaded on
        // their owning extension. Doing the bigraph fill eagerly here
        // (instead of only when bigraph is the active variant) is what
        // makes variant switches an instant pointer swap rather than
        // an on-demand rebuild.
        this.currentView.reset();
        this.traverseAndCreateGModelFromBigraph();

        // Hand the active container over to the GLSP framework. Always
        // the last step so the previous setup is already complete by
        // the time GLSP touches the root.
        this.updateRoot(this.variantRoots.get(this.activeVariantId));
        return this.getRoot();
    }

    /**
     * Creates a fresh empty {@link GGraph} suitable for use as a
     * variant-specific GModel root. The id pattern keeps each
     * container distinguishable in the wire log, even though only one
     * is ever GLSP-visible at a time.
     */
    private static GModelRoot createEmptyVariantRoot(String variantId) {
        GGraph gGraph = GraphFactory.eINSTANCE.createGGraph();
        gGraph.setId(variantId + "-root");
        gGraph.setType("graph");
        return gGraph;
    }

    /**
     * Returns the persistent GModel container associated with the
     * given variant, or {@code null} when this diagram doesn't carry
     * that variant. Callers that incrementally populate a specific
     * variant (e.g. {@code BTExtensionState.syncBTGModel} for BT,
     * {@code GraphBigraphView} for bigraph) read this instead of
     * {@code getRoot()} so they always target their own container —
     * not whichever one is currently active.
     */
    public GModelRoot getVariantRoot(String variantId) {
        return this.variantRoots.get(variantId);
    }

    /**
     * Swaps the GLSP-visible root to the container owned by
     * {@code targetVariantId}. Validates membership in
     * {@link #availableVariantIds} so handlers can blindly delegate
     * the request. Returns {@code true} when an actual switch happened
     * (caller bumps revision + dispatches UpdateModelAction).
     */
    public boolean swapActiveVariantTo(String targetVariantId) {
        if (targetVariantId == null
                || !this.availableVariantIds.contains(targetVariantId)
                || !this.variantRoots.containsKey(targetVariantId)) {
            return false;
        }
        if (targetVariantId.equals(this.activeVariantId)) {
            return false;
        }
        this.activeVariantId = targetVariantId;
        this.updateRoot(this.variantRoots.get(targetVariantId));
        return true;
    }


    private void traverseAndCreateGModelFromBigraph() {

        var bigraph = this.mutableBigraph;
                
        // === PLACE GRAPH ===
        // Traverse hierarchically to ensure parents are registered before children
        
        // Iterate roots and their children recursively
        for (RootEntity root : bigraph.getRoots()) {
            this.currentView.onAddRoot(root);
            // Recursively process children of this root
            traversePlaceGraphChildren(bigraph, root);
        }
        
        // === REGISTER PORTS ===
        // Must be done BEFORE link graph so port-to-node mappings exist
        // when creating link connections
        for (NodeEntity<DynamicControl> node : bigraph.getNodes()) {
            for (Port port : bigraph.getPorts(node)) {
                Link link = bigraph.getLinkOfPoint(port);
                this.currentView.onAddPort(node, port, link);
            }
        }
        
        // === REGISTER INNER NAMES ===
        // Must be done BEFORE edges so inner name mappings exist
        // when creating link connections (edges can connect to inner names)
        for (InnerName innerName : bigraph.getInnerNames()) {
            Link link = bigraph.getLinkOfPoint(innerName);
            this.currentView.onAddInnerName(innerName, link);
        }
        
        // === LINK GRAPH ===
        
        // Iterate edges (hyperedges)
        for (Edge edge : bigraph.getEdges()) {
            Collection<BigraphEntity<?>> connectedPoints = bigraph.getPointsFromLink(edge);
            this.currentView.onAddEdge(edge, connectedPoints);
        }
        
        // Iterate outer names
        for (OuterName outerName : bigraph.getOuterNames()) {
            Collection<BigraphEntity<?>> connectedPoints = bigraph.getPointsFromLink(outerName);
            this.currentView.onAddOuterName(outerName, connectedPoints);
        }
    }


    private void traversePlaceGraphChildren(PureBigraphMutable bigraph, BigraphEntity<?> parent) {
        int siteIndexUnderParent = 0;
        for (BigraphEntity<?> child : bigraph.getChildrenOf(parent)) {
            if (child instanceof NodeEntity) {
                NodeEntity<DynamicControl> node = (NodeEntity<DynamicControl>) child;
                DynamicControl control = node.getControl();
                this.currentView.onAddNode(node, parent, control);
                traversePlaceGraphChildren(bigraph, node);
            } else if (child instanceof SiteEntity) {
                SiteEntity site = (SiteEntity) child;
                this.currentView.onAddSite(site, parent, siteIndexUnderParent++);
                traversePlaceGraphChildren(bigraph, site);
            }
        }
    }

    public BigraphView getActiveView() {
        return currentView;
    }

    public DynamicSignature getPendingSignature() {
        return pendingSignature;
    }

    public void setPendingSignature(final DynamicSignature signature) {
        this.pendingSignature = signature;
    }

    public boolean hasPendingSignature() {
        return pendingSignature != null;
    }


    public String getSourceFilePath() {
        return sourceFilePath;
    }

    public void setSourceFilePath(String sourceFilePath) {
        this.sourceFilePath = sourceFilePath;
    }

    public PureBigraphMutable getMutableBigraph() {
        return mutableBigraph;
    }

    @Override
    public BigraphMetaInformation getMetaInformation() {
        return metaInformation;
    }

    /**
     * Variant id currently visible/editable in the diagram. Drives runtime
     * gating of action handlers and palette items.
     */
    public String getActiveVariantId() {
        return activeVariantId;
    }

    /**
     * Switches the active variant. Called by the variant-switch action
     * handler when the user toggles the in-canvas tab bar. The persisted
     * default ({@code metaInformation.modelType}) is intentionally left
     * alone so that re-opening the file restores the original view.
     */
    public void setActiveVariantId(String activeVariantId) {
        this.activeVariantId = activeVariantId;
    }

    /**
     * Variants this diagram can switch between for the currently
     * loaded file. The list is at least {@code ["bigraph"]} and grows
     * to also include the persisted {@code modelType} when that
     * differs from the canonical bigraph variant. UI bits like the
     * variant tab bar read from here to decide whether to render
     * themselves at all.
     */
    @Override
    public List<String> getAvailableVariantIds() {
        return availableVariantIds;
    }

    private static List<String> computeAvailableVariants(final String activeVariantId) {
        if (activeVariantId == null || activeVariantId.isBlank()
                || CoreIdeExtension.BIGRAPH_VARIANT_ID.equals(activeVariantId)) {
            return List.of(CoreIdeExtension.BIGRAPH_VARIANT_ID);
        }
        // Bigraph is always available — every file is a bigraph under
        // the hood. The active extension variant is appended after it.
        return List.of(CoreIdeExtension.BIGRAPH_VARIANT_ID, activeVariantId);
    }

}

