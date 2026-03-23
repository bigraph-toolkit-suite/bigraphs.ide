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

import org.eclipse.glsp.server.actions.ActionDispatcher;
import org.eclipse.glsp.server.actions.MessageAction;
import org.eclipse.glsp.server.types.Severity;

/**
 * Static notification helpers for bigraph operation handlers.
 *
 * <p>Each method dispatches a standard {@link MessageAction} which the VS Code
 * integration layer translates into the corresponding toast notification.
 * The frontend decides how to react visually (e.g. shake the tool palette)
 * based on its own interaction context — no source metadata needed here.</p>
 */
public final class BigraphNotifications {

    private BigraphNotifications() {}

    public static void notifyError(final ActionDispatcher dispatcher, final String message) {
        dispatcher.dispatch(new MessageAction(Severity.ERROR, message));
    }

    public static void notifyWarning(final ActionDispatcher dispatcher, final String message) {
        dispatcher.dispatch(new MessageAction(Severity.WARNING, message));
    }

    public static void notifyInfo(final ActionDispatcher dispatcher, final String message) {
        dispatcher.dispatch(new MessageAction(Severity.INFO, message));
    }
}
