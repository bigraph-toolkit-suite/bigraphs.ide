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
    private Map<String, ControlProperty> controlMeta = new HashMap<>();

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

    public Map<String, ControlProperty> getControlMeta() { return controlMeta; }
    public void setControlMeta(Map<String, ControlProperty> controlMeta) { this.controlMeta = controlMeta; }
}
