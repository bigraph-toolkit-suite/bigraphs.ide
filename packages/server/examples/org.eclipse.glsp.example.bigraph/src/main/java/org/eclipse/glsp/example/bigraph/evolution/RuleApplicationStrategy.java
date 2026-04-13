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

package org.eclipse.glsp.example.bigraph.evolution;

/**
 * How rewrite rules are scheduled during evolution (JSON field {@code ruleApplicationStrategy}
 * and {@link org.eclipse.glsp.example.bigraph.actions.EvolutionRunAction}).
 */
public enum RuleApplicationStrategy {

    /** Each step scans from rule 0; first match wins. */
    FIRST_FIRST("first-first"),
    /** Each step scans from a rotating start index. */
    ROUND_ROBIN("round-robin");

    private final String wireValue;

    RuleApplicationStrategy(final String wireValue) {
        this.wireValue = wireValue;
    }

    public String getWireValue() {
        return wireValue;
    }

    /**
     * Parses the string sent from the client / stored in evolution.json.
     * Unknown or blank values default to {@link #FIRST_FIRST}.
     */
    public static RuleApplicationStrategy fromWire(final String s) {
        if (s == null || s.isBlank()) {
            return FIRST_FIRST;
        }
        final String t = s.trim();
        for (final RuleApplicationStrategy st : values()) {
            if (st.wireValue.equalsIgnoreCase(t)) {
                return st;
            }
        }
        return FIRST_FIRST;
    }
}
