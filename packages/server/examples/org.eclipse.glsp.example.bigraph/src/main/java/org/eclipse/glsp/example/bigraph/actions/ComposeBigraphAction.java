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
 * Action dispatched from the VS Code extension host when the user selects
 * "Compose with..." on a bigraph file in the Bigraph Explorer.
 *
 * The handler performs the composition in-memory and updates the model state.
 * Nothing is written to disk until the user explicitly saves (Ctrl+S).
 */
public class ComposeBigraphAction extends Action {

    public static final String KIND = "bigraph.compose";

    /** "parallel" (juxtapose / tensor product) or "sequential" (outer ∘ inner). */
    private String operator;

    /** Absolute filesystem path of the bigraph to compose with the current canvas bigraph. */
    private String sourcePath;

    public ComposeBigraphAction() {
        super(KIND);
    }

    public ComposeBigraphAction(final String operator, final String sourcePath) {
        super(KIND);
        this.operator = operator;
        this.sourcePath = sourcePath;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(final String operator) {
        this.operator = operator;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(final String sourcePath) {
        this.sourcePath = sourcePath;
    }
}
