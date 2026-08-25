/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import type { GModelElement, GModelRoot, LabeledAction, Point } from '@eclipse-glsp/client';

/** Context passed to extension context-menu contributors. */
export interface ContextMenuContext {
    readonly root: Readonly<GModelRoot>;
    /** Deepest interactive element at the click position, if any. */
    readonly element: GModelElement | undefined;
    readonly point: Point;
    readonly variantId: string | null;
}

export type ContextMenuItemContributor = (ctx: ContextMenuContext) => LabeledAction[];
