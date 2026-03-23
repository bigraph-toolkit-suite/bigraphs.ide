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

public class AutoLayoutAction extends Action {

    public static final String KIND = "bigraph.autoLayout";

    /** ELK algorithm identifier, e.g. {@code "layered"}, {@code "mrtree"}, {@code "force"}, {@code "stress"}. */
    private String algorithm = "layered";

    /**
     * When {@code true}, skip ELK for inner/outer names and pin them to the
     * top (outer names) and bottom (inner names) of the diagram after layout,
     * following the standard bigraph visual convention.
     */
    private boolean bigraphStandard = false;

    public AutoLayoutAction() {
        super(KIND);
    }

    public AutoLayoutAction(final String algorithm) {
        super(KIND);
        this.algorithm = algorithm;
    }

    public AutoLayoutAction(final String algorithm, final boolean bigraphStandard) {
        super(KIND);
        this.algorithm = algorithm;
        this.bigraphStandard = bigraphStandard;
    }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(final String algorithm) { this.algorithm = algorithm; }

    public boolean isBigraphStandard() { return bigraphStandard; }
    public void setBigraphStandard(final boolean bigraphStandard) { this.bigraphStandard = bigraphStandard; }
}
