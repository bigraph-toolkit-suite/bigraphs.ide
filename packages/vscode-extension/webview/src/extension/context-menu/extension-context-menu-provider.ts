/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { inject, injectable } from 'inversify';
import type {
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
        const element = findElementAt(root, point, eligibleTypes);

        const ctx: ContextMenuContext = {
            root,
            element,
            point,
            variantId,
        };

        return Promise.resolve(extension.getContextMenuItems(ctx));
    }
}
