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
 * Sent by the client to check whether the current bigraph (workspace-bigraph)
 * matches a given verification bigraph (treated as a redex pattern).
 */
public class VerifyBigraphAction extends Action {
    public static final String KIND = "bigraph.verifyBigraph";

    /** The verification bigraph entry id (used to correlate the result). */
    private String verificationId;
    /** Absolute path to the verification bigraph .xmi file. */
    private String verificationPath;
    /** Absolute path to the operation's checkpoint .xmi file to check against, or null to use the current workspace bigraph. */
    private String checkpointPath;

    public VerifyBigraphAction() { super(KIND); }

    public String getVerificationId()   { return verificationId; }
    public String getVerificationPath() { return verificationPath; }
    public String getCheckpointPath()   { return checkpointPath; }
}
