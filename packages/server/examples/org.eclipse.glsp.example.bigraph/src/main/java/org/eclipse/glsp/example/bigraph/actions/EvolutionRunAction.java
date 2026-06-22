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

import java.util.List;
import org.eclipse.glsp.server.actions.Action;

public class EvolutionRunAction extends Action {
    public static final String KIND = "bigraph.evolutionRun";

    public static class RuleRef {
        private String id;
        private String redex;
        private String reactum;

        public String getId()      { return id; }
        public String getRedex()   { return redex; }
        public String getReactum() { return reactum; }
    }

    /** A stop-condition verification bigraph: evolution halts when current state matches it. */
    public static class VerificationRef {
        private String id;
        private String path;

        public String getId()   { return id; }
        public String getPath() { return path; }
    }

    /** "play" or "pause" */
    private String actionType;
    /** For pause: the id of the operation to cancel. Null for play. */
    private String operationId;
    /** Active rewrite rules (each with absolute redex + reactum paths). */
    private List<RuleRef> rules;
    /** Stop-condition verification bigraphs (stop-tagged only, absolute paths). */
    private List<VerificationRef> verificationBigraphs;
    /** Absolute path to the checkpoints/ folder where result bigraphs are written. */
    private String checkpointsDir;
    /** Absolute path to evolution.json – updated after each rule application. */
    private String evolutionJsonPath;
    /** When false, the evolution runs until no rule matches – maxOperations is ignored. */
    private boolean maxOperationsEnabled;
    private int maxOperations;
    private boolean checkpointFileGeneration;
    private boolean visualizeIntermediateSteps;
    /**
     * How rewrite rules are scheduled — wire values match
     * {@link org.eclipse.glsp.example.bigraph.evolution.RuleApplicationStrategy#getWireValue()}.
     */
    private String ruleApplicationStrategy;

    public EvolutionRunAction() { super(KIND); }

    public String getActionType()                          { return actionType; }
    public String getOperationId()                         { return operationId; }
    public List<RuleRef> getRules()                        { return rules; }
    public List<VerificationRef> getVerificationBigraphs() { return verificationBigraphs; }
    public String getCheckpointsDir()                      { return checkpointsDir; }
    public String getEvolutionJsonPath()                   { return evolutionJsonPath; }
    public boolean isMaxOperationsEnabled()                { return maxOperationsEnabled; }
    public int getMaxOperations()                          { return maxOperations; }
    public boolean isCheckpointFileGeneration()            { return checkpointFileGeneration; }
    public boolean isVisualizeIntermediateSteps()          { return visualizeIntermediateSteps; }
    /** @return Wire value for {@link org.eclipse.glsp.example.bigraph.evolution.RuleApplicationStrategy}, or null. */
    public String getRuleApplicationStrategy()             { return ruleApplicationStrategy; }
}
