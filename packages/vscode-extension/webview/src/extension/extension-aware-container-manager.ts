/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { inject, injectable } from 'inversify';
import type { GModelElement, Point } from '@eclipse-glsp/sprotty';
import type { ContainerElement } from '@eclipse-glsp/client/lib/features/hints/model';
import { ContainerManager } from '@eclipse-glsp/client/lib/features/tools/node-creation/container-manager';

import { ActiveVariantTracker } from './context-menu/active-variant-tracker';
import { findExtensionByVariant } from './extensions';

/**
 * The single {@code TYPES.IContainerManager} bound in the diagram
 * container (see {@code bigraph-diagram-module.ts}).
 *
 * <p>Routes drop-container resolution during node creation to the
 * extension that owns the diagram's currently active variant (via
 * {@link IDiagramExtension.findDropContainer}) and falls back to the
 * stock GLSP behaviour otherwise. This replaces the previous pattern
 * of extensions {@code rebind}ing the container manager globally,
 * which leaked variant-specific drop preferences into all variants
 * and could only ever work for one extension at a time.</p>
 */
@injectable()
export class ExtensionAwareContainerManager extends ContainerManager {
    @inject(ActiveVariantTracker)
    protected readonly variantTracker!: ActiveVariantTracker;

    override findContainer(
        location: Point,
        ctx: GModelElement,
        evt?: MouseEvent
    ): ContainerElement | undefined {
        const variantId = this.variantTracker.getActiveVariantId() ?? 'bigraph';
        const extension = findExtensionByVariant(variantId);
        const fromExtension = extension?.findDropContainer?.(location, ctx, evt);
        return fromExtension ?? super.findContainer(location, ctx, evt);
    }
}
