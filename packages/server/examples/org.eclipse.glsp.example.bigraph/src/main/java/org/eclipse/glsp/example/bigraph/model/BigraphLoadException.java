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

/**
 * Raised when a bigraph or its companion signature files cannot be loaded
 * without modifying the source files.
 */
public class BigraphLoadException extends RuntimeException {

    public BigraphLoadException(final String message) {
        super(message);
    }

    public BigraphLoadException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
