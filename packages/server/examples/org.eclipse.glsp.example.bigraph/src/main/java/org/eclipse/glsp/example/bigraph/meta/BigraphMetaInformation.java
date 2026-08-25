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

package org.eclipse.glsp.example.bigraph.meta;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.glsp.graph.GPoint;

public class BigraphMetaInformation {
    /**
     * Absolute positions keyed by a property-based node position key.
     *
     * Legacy files might still contain old keys (previously derived from the volatile glspNodeId).
     */
    private Map<String, GPoint> nodePositions = new HashMap<>();
    /**
     * Relative positions keyed by the same property-based node position key for nodes nested inside a site.
     */
    private Map<String, GPoint> nodeRelativePositions = new HashMap<>();
    private Map<String, GPoint> innerNamePositions = new HashMap<>();
    private Map<String, GPoint> outerNamePositions = new HashMap<>();
    private Map<String, GPoint> edgePositions = new HashMap<>();
    private Map<String, GPoint> sitePositions = new HashMap<>();
    /**
     * Root positions keyed by the root index (stringified int).
     */
    private Map<String, GPoint> rootPositions = new HashMap<>();
    private Map<String, ControlProperty> controlMeta = new HashMap<>();

    /**
     * Extension-owned meta blobs keyed by {@link org.eclipse.glsp.example.bigraph.extensions.IdeExtension#getId()}.
     * Each value is opaque to the core (typically JSON serialized by the owning extension).
     */
    private Map<String, String> extensionSections = new HashMap<>();

    /**
     * Optional diagram-variant discriminator. Stores the {@code id} of a
     * {@link org.eclipse.glsp.example.bigraph.model.ModelVariant} declared by
     * some registered {@link org.eclipse.glsp.example.bigraph.extensions.IdeExtension}.
     * {@code null} or absent in the file means the diagram falls back to the
     * built-in {@code "bigraph"} variant.
     */
    private String modelType;

    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }

    public Map<String, GPoint> getNodePositions() { return nodePositions; }
    public void setNodePositions(Map<String, GPoint> nodePositions) { this.nodePositions = nodePositions; }

    public Map<String, GPoint> getNodeRelativePositions() { return nodeRelativePositions; }
    public void setNodeRelativePositions(Map<String, GPoint> nodeRelativePositions) { this.nodeRelativePositions = nodeRelativePositions; }

    public Map<String, GPoint> getInnerNamePositions() { return innerNamePositions; }
    public void setInnerNamePositions(Map<String, GPoint> innerNamePositions) { this.innerNamePositions = innerNamePositions; }

    public Map<String, GPoint> getOuterNamePositions() { return outerNamePositions; }
    public void setOuterNamePositions(Map<String, GPoint> outerNamePositions) { this.outerNamePositions = outerNamePositions; }

    public Map<String, GPoint> getEdgePositions() { return edgePositions; }
    public void setEdgePositions(Map<String, GPoint> edgePositions) { this.edgePositions = edgePositions; }

    public Map<String, GPoint> getSitePositions() { return sitePositions; }
    public void setSitePositions(Map<String, GPoint> sitePositions) { this.sitePositions = sitePositions; }

    public Map<String, GPoint> getRootPositions() { return rootPositions; }
    public void setRootPositions(Map<String, GPoint> rootPositions) { this.rootPositions = rootPositions; }

    public Map<String, ControlProperty> getControlMeta() { return controlMeta; }
    public void setControlMeta(Map<String, ControlProperty> controlMeta) { this.controlMeta = controlMeta; }

    public Map<String, String> getExtensionSections() { return extensionSections; }
    public void setExtensionSections(Map<String, String> extensionSections) {
        this.extensionSections = extensionSections;
    }

  /** Returns the extension section payload for {@code extensionId}, or {@code null}. */
    public String getExtensionSection(final String extensionId) {
        return extensionSections.get(extensionId);
    }

    public void setExtensionSection(final String extensionId, final String payload) {
        if (extensionId == null || extensionId.isBlank()) {
            return;
        }
        if (payload == null || payload.isBlank()) {
            extensionSections.remove(extensionId);
        } else {
            extensionSections.put(extensionId, payload);
        }
    }
}
