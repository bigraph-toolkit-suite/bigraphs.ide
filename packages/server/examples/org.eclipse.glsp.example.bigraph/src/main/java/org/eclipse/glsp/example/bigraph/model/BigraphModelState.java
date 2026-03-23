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
import org.eclipse.glsp.example.bigraph.views.BigraphView;
import org.eclipse.glsp.example.bigraph.views.graph.GraphBigraphView;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.example.bigraph.model.IBigraphModelState;
import java.util.HashMap;
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
     * Holds a merged signature produced by a composition operation.
     * Null when no composition has been performed since the last save.
     * Written to disk in {@code BigraphXMIModelStorage.saveSourceModel} on Ctrl+S.
     */
    private DynamicSignature pendingSignature = null;


    public GModelRoot initializeBigraphModel(PureBigraphMutable bigraph, BigraphMetaInformation meta) {
        this.mutableBigraph = bigraph;
        this.metaInformation = meta;
        GGraph gGraph = GraphFactory.eINSTANCE.createGGraph();
        gGraph.setId("bigraph-root");
        gGraph.setType("graph");
        this.updateRoot(gGraph);
        this.currentView.reset();
        this.traverseAndCreateGModelFromBigraph();
        return this.getRoot();
    }

    public void changeView(BigraphView view) {
        this.currentView = view;
        this.updateRoot(this.initializeBigraphModel(this.mutableBigraph, this.metaInformation));
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


}

