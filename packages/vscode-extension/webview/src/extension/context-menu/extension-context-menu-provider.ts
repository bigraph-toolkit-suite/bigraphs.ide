/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { inject, injectable } from 'inversify';
import { isSelected } from '@eclipse-glsp/client';
import type {
    GModelElement,
    GModelRoot,
    IContextMenuItemProvider,
    LabeledAction,
    Point,
} from '@eclipse-glsp/client';

import { findExtensionByVariant } from '../extensions';
import { ActiveVariantTracker } from './active-variant-tracker';
import type { ContextMenuContext } from './context-menu-types';
import { diagramLocalPoint, findElementAt } from './context-menu-hit-test';

/**
 * Aggregates context-menu entries from the extension that owns the
 * active diagram variant.
 */
@injectable()
export class ExtensionContextMenuProvider implements IContextMenuItemProvider {
    @inject(ActiveVariantTracker)
    protected readonly variantTracker!: ActiveVariantTracker;

    getItems(root: Readonly<GModelRoot>, lastMousePosition?: Point): Promise<LabeledAction[]> {
        if (!lastMousePosition) {
            return Promise.resolve([]);
        }

        const variantId = this.variantTracker.getActiveVariantId() ?? 'bigraph';
        const extension = findExtensionByVariant(variantId);
        if (!extension?.getContextMenuItems || !extension.contextMenuElementTypes) {
            return Promise.resolve([]);
        }

        const point = diagramLocalPoint(root, lastMousePosition);
        const eligibleTypes = new Set(extension.contextMenuElementTypes);
        const element = selectedTarget(root, eligibleTypes) ?? findElementAt(root, point, eligibleTypes);

        const ctx: ContextMenuContext = {
            root,
            element,
            point,
            variantId,
        };

        return Promise.resolve(extension.getContextMenuItems(ctx));
    }
}

/**
 * The element the menu was opened on. GLSP selects the right-clicked element before it asks for the items (keeping
 * a multi-selection that contains it, and clearing the selection on empty canvas), so a single selected element of an
 * eligible type is that target. Unlike the bounds hit test this also finds edges, which have no bounds, and an edge
 * crossing a node. For a multi-selection the hit test decides.
 */
function selectedTarget(root: Readonly<GModelRoot>, eligibleTypes: ReadonlySet<string>): GModelElement | undefined {
    const selected = Array.from(root.index.all().filter(isSelected));
    return selected.length === 1 && eligibleTypes.has(selected[0].type) ? selected[0] : undefined;
}
