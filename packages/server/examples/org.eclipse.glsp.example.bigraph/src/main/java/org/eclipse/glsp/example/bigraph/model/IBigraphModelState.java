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

import java.util.List;

import org.bigraphs.framework.core.impl.pure.PureBigraphMutable;
import org.eclipse.glsp.example.bigraph.meta.BigraphMetaInformation;
import org.eclipse.glsp.graph.GModelRoot;

public interface IBigraphModelState {
    GModelRoot getRoot();
    PureBigraphMutable getMutableBigraph();
    BigraphMetaInformation getMetaInformation();

    /**
     * Currently active diagram variant id (e.g. {@code "bigraph"} or
     * {@code "behavior-tree"}). May differ from
     * {@link BigraphMetaInformation#getModelType()} once the user switches
     * the view via the diagram's tab-bar.
     */
    String getActiveVariantId();

    /**
     * Variant ids this diagram session can switch between for the
     * currently loaded file. Always contains the canonical bigraph
     * variant; for extension-typed files the persisted variant
     * follows. Frontend reads this to decide whether to render the
     * tab-bar at all.
     */
    List<String> getAvailableVariantIds();
}
