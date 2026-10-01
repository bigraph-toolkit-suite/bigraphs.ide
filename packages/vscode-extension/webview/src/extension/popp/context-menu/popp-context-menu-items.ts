import type { ContextMenuContext } from '../../context-menu/context-menu-types';
import { LabeledAction, DeleteElementOperation } from '@eclipse-glsp/client';
import { createSwitchDecompositionTypeOperation } from '../popp-actions';

export const POPP_CONTEXT_MENU_ELEMENT_TYPES = [
    'popp:problem',
    'popp:goal',
    'popp:consequence',
    'popp:solution',
    'popp:success_criteria',
    'popp:success_proof'
] as const;

const POPP_NODE_TYPES = new Set<string>(POPP_CONTEXT_MENU_ELEMENT_TYPES);

export function getPoppContextMenuItems(ctx: ContextMenuContext): LabeledAction[] {
    if (!ctx.element || !POPP_NODE_TYPES.has(ctx.element.type)) {
        return [];
    }

    const nodeId = ctx.element.id;
    const args = (ctx.element as unknown as { args?: Record<string, unknown> }).args;
    const currentDecompositionType = args?.decomposition_type;

    const items: LabeledAction[] = [];

    if (currentDecompositionType !== 'AND') {
        items.push({
            label: 'Decompose with AND',
            actions: [createSwitchDecompositionTypeOperation(nodeId, 'AND')]
        });
    }
    if (currentDecompositionType !== 'OR') {
        items.push({
            label: 'Decompose with OR',
            actions: [createSwitchDecompositionTypeOperation(nodeId, 'OR')]
        });
    }

    items.push({
        label: 'Delete',
        actions: [DeleteElementOperation.create([nodeId])]
    });

    return items;
}
