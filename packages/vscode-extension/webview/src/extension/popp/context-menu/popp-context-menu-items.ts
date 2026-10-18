import type { ContextMenuContext } from '../../context-menu/context-menu-types';
import { LabeledAction, DeleteElementOperation } from '@eclipse-glsp/client';
import { createSwitchDecompositionTypeOperation } from '../popp-actions';

const POPP_NODE_TYPES = [
    'popp:problem',
    'popp:goal',
    'popp:consequence',
    'popp:solution',
    'popp:success_criteria',
    'popp:success_proof'
] as const;

const POPP_DECOMPOSITION_EDGE_TYPE = 'popp:decomposition_edge';

const POPP_RELATION_EDGE_TYPES = [
    'popp:produces',
    'popp:validates',
    'popp:causes',
    'popp:inverts',
    'popp:realizes'
] as const;

export const POPP_CONTEXT_MENU_ELEMENT_TYPES = [
    ...POPP_NODE_TYPES,
    POPP_DECOMPOSITION_EDGE_TYPE,
    ...POPP_RELATION_EDGE_TYPES
] as const;

const NODE_TYPES = new Set<string>(POPP_NODE_TYPES);
const RELATION_TYPES = new Set<string>(POPP_RELATION_EDGE_TYPES);

export function getPoppContextMenuItems(ctx: ContextMenuContext): LabeledAction[] {
    if (!ctx.element) {
        return [];
    }
    const { id, type } = ctx.element;
    if (type === POPP_DECOMPOSITION_EDGE_TYPE) {
        return [{ label: 'Delete decomposition', actions: [DeleteElementOperation.create([id])] }];
    }
    if (RELATION_TYPES.has(type)) {
        const name = type.slice('popp:'.length);
        return [{ label: `Delete '${name}' relation`, actions: [DeleteElementOperation.create([id])] }];
    }
    if (!NODE_TYPES.has(type)) {
        return [];
    }

    const nodeId = ctx.element.id;
    const args = (ctx.element as unknown as { args?: Record<string, unknown> }).args;
    const currentDecompositionType = args?.decomposition_type;

    const items: LabeledAction[] = [];

    // Only a node with children has a decomposition (the server refuses AND/OR on a leaf, whose type is NONE),
    // so offer the other type of the current one.
    if (currentDecompositionType === 'OR') {
        items.push({
            label: 'Decompose with AND',
            actions: [createSwitchDecompositionTypeOperation(nodeId, 'AND')]
        });
    }
    if (currentDecompositionType === 'AND') {
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
