import { Action } from '@eclipse-glsp/client';

export interface RequestRenameNodeAction extends Action {
    kind: typeof RequestRenameNodeAction.KIND;
    elementId: string;
}

export namespace RequestRenameNodeAction {
    export const KIND = 'bigraphRequestRenameNode';

    export function create(elementId: string): RequestRenameNodeAction {
        return {
            kind: KIND,
            elementId
        };
    }
}

export interface RenameNodeAction extends Action {
    kind: typeof RenameNodeAction.KIND;
    elementId: string;
    newName: string;
}

export namespace RenameNodeAction {
    export const KIND = 'bigraphRenameNode';

    export function create(elementId: string, newName: string): RenameNodeAction {
        return {
            kind: KIND,
            elementId,
            newName
        };
    }
}
