/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import type { Action, LabeledAction } from '@eclipse-glsp/client';

import type { ContextMenuContext } from '../../context-menu/context-menu-types';
import { LocalRenameAction } from '../../context-menu/local-rename-action';
import { RenameNodeAction } from '../../../rename-action';

export const BIGRAPH_CONTEXT_MENU_ELEMENT_TYPES: readonly string[] = [
    'bigraph:node',
    'node',
    'bigraph:inner-name',
    'bigraph:outer-name',
    'bigraph:hyperedge',
];

/**
 * Context-menu entries for the core bigraph variant (rename, name-role conversion).
 */
export function getBigraphContextMenuItems(ctx: ContextMenuContext): LabeledAction[] {
    const element = ctx.element;
    if (!element) {
        return [];
    }

    const actions: LabeledAction[] = [];

    if (BIGRAPH_CONTEXT_MENU_ELEMENT_TYPES.includes(element.type)) {
        actions.push({
            label: 'Rename',
            actions: [{
                kind: LocalRenameAction.KIND,
                elementId: element.id,
                submitActionKind: RenameNodeAction.KIND,
                submitPayload: { elementId: element.id },
            } as LocalRenameAction],
        });
    }

    if (element.type === 'bigraph:inner-name') {
        actions.push({
            label: 'Make Outer',
            actions: [{
                kind: 'bigraphConvertNameRole',
                elementId: element.id,
                targetRole: 'outer',
            } as Action],
        });
    } else if (element.type === 'bigraph:outer-name') {
        actions.push({
            label: 'Make Inner',
            actions: [{
                kind: 'bigraphConvertNameRole',
                elementId: element.id,
                targetRole: 'inner',
            } as Action],
        });
    }

    return actions;
}
