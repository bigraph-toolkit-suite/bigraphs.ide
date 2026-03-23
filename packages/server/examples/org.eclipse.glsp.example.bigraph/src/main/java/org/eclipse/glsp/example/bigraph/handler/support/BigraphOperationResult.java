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

package org.eclipse.glsp.example.bigraph.handler.support;

/**
 * Represents the outcome of a bigraph model mutation attempt.
 *
 * <p>Handlers perform bigraph mutations and return a {@code BigraphOperationResult}.
 * A {@link #success()} result permits the subsequent GModel update to proceed.
 * A {@link #failure(String)} result carries a user-facing message; the handler must
 * abort immediately without touching the GModel, keeping both models consistent.</p>
 *
 * <p>Usage pattern in a handler:</p>
 * <pre>
 *   BigraphOperationResult result = mutateBigraph(...);
 *   if (result.isFailure()) {
 *       notifyError(result.getErrorMessage());
 *       return;
 *   }
 *   // only reached when bigraph mutation succeeded
 *   updateGModel(...);
 * </pre>
 */
public final class BigraphOperationResult {

    private final boolean success;
    private final String errorMessage;

    private BigraphOperationResult(final boolean success, final String errorMessage) {
        this.success = success;
        this.errorMessage = errorMessage;
    }

    /** Returns a result indicating the bigraph mutation succeeded. */
    public static BigraphOperationResult success() {
        return new BigraphOperationResult(true, null);
    }

    /**
     * Returns a result indicating the bigraph mutation failed with the given user-facing message.
     *
     * @param userMessage a short, human-readable description shown in the VS Code notification
     */
    public static BigraphOperationResult failure(final String userMessage) {
        return new BigraphOperationResult(false, userMessage != null ? userMessage : "Unknown error");
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    /**
     * Returns the user-facing error message; only meaningful when {@link #isFailure()} is {@code true}.
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
