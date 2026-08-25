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

import org.eclipse.glsp.server.actions.Action;

/**
 * Client → server request to switch the active diagram variant for the
 * current file (e.g. from {@code "behavior-tree"} to {@code "bigraph"}).
 * Fired by the in-canvas variant tab bar.
 *
 * <p>The persisted {@code modelType} in the {@code .bigraph-meta} file
 * is intentionally not touched — only the in-memory view changes.
 * Re-opening the file restores the original view.</p>
 */
public class RequestVariantSwitchAction extends Action {

    public static final String KIND = "bigraph.requestVariantSwitch";

    private String variantId;

    public RequestVariantSwitchAction() {
        super(KIND);
    }

    public RequestVariantSwitchAction(final String variantId) {
        super(KIND);
        this.variantId = variantId;
    }

    public String getVariantId() {
        return variantId;
    }

    public void setVariantId(final String variantId) {
        this.variantId = variantId;
    }
}
