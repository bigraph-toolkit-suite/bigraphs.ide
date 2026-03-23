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

import org.eclipse.glsp.server.actions.ResponseAction;

/**
 * Sent back to the client with the result of a {@link VerifyBigraphAction}.
 * Extends {@link ResponseAction} so GLSP routes it to the client instead of
 * trying to dispatch it server-side.
 */
public class VerifyBigraphResultAction extends ResponseAction {
    public static final String KIND = "bigraph.verifyBigraphResult";

    /** Mirrors the id from the request so the client can correlate. */
    private String verificationId;
    /** True if the verification bigraph matched the current / checkpoint bigraph. */
    private boolean matched;
    /** Human-readable status message for logging / debugging. */
    private String message;

    public VerifyBigraphResultAction() { super(KIND); }

    public VerifyBigraphResultAction(final String verificationId, final boolean matched, final String message) {
        super(KIND);
        this.verificationId = verificationId;
        this.matched = matched;
        this.message = message;
    }

    public String  getVerificationId() { return verificationId; }
    public boolean isMatched()         { return matched; }
    public String  getMessage()        { return message; }
}
