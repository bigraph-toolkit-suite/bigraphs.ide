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

package org.eclipse.glsp.example.bigraph.actions;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.glsp.server.actions.ResponseAction;

/**
 * Server → client notification carrying the currently active variant id
 * for this diagram session, together with the full set of variants the
 * client can switch between for this file.
 *
 * <p>Dispatched at least once after a fresh model load, and again on
 * every explicit variant switch (custom tab bar toggling between BT
 * and the underlying bigraph view).</p>
 *
 * <p>The webview's palette host and variant tab-bar listen for this
 * action — the host swaps in the matching variant palette, the tab-bar
 * highlights the active tab and decides whether to render at all
 * (only when {@code availableVariantIds.size() > 1}).</p>
 *
 * <p>Extends {@link ResponseAction} so the {@code ClientActionForwarder}
 * forwards it automatically without a server-side handler registration.</p>
 */
public class SetActiveVariantAction extends ResponseAction {

    public static final String KIND = "bigraph.setActiveVariant";

    private String variantId;
    private List<String> availableVariantIds = new ArrayList<>();

    public SetActiveVariantAction() {
        super(KIND);
    }

    public SetActiveVariantAction(final String variantId) {
        super(KIND);
        this.variantId = variantId;
    }

    public SetActiveVariantAction(final String variantId, final List<String> availableVariantIds) {
        super(KIND);
        this.variantId = variantId;
        if (availableVariantIds != null) {
            this.availableVariantIds = new ArrayList<>(availableVariantIds);
        }
    }

    public String getVariantId() {
        return variantId;
    }

    public void setVariantId(final String variantId) {
        this.variantId = variantId;
    }

    public List<String> getAvailableVariantIds() {
        return availableVariantIds;
    }

    public void setAvailableVariantIds(final List<String> availableVariantIds) {
        this.availableVariantIds = availableVariantIds == null
                ? new ArrayList<>()
                : new ArrayList<>(availableVariantIds);
    }
}
