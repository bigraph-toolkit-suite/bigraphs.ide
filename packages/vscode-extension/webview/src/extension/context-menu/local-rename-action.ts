/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import type { Action } from '@eclipse-glsp/client';

/** Opens an inline rename prompt in the context-menu service. */
export interface LocalRenameAction extends Action {
    kind: typeof LocalRenameAction.KIND;
    elementId: string;
    initialName?: string;
    submitActionKind: string;
    submitPayload?: Record<string, unknown>;
}

export namespace LocalRenameAction {
    export const KIND = 'localRename';

    export function is(action: Action): action is LocalRenameAction {
        return action.kind === KIND;
    }
}
