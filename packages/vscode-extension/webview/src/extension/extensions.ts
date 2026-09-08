/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { Container, ContainerModule } from 'inversify';
import type { Action, Anchor, IActionDispatcher } from '@eclipse-glsp/client';
import type { GModelElement, Point } from '@eclipse-glsp/sprotty';
import type { ContainerElement } from '@eclipse-glsp/client/lib/features/hints/model';
import { createBigraphPalette } from './core/palette';
import { createSvgExportAction } from './core/palette/svgExport';
import { createPOPPPalette } from './popp';
import {
    BIGRAPH_CONTEXT_MENU_ELEMENT_TYPES,
    getBigraphContextMenuItems,
} from './core/context-menu/bigraph-context-menu-items';
import type { PaletteExportAction } from './palette-export';
import type { PaletteImportAction } from './palette-import';
import type { PaletteHostApi, VariantPaletteFactory } from './palette-types';
import type { ContextMenuItemContributor } from './context-menu/context-menu-types';

/**
 * Manifest contract implemented by every webview-side diagram
 * extension. Mirrors the server's {@code IdeExtension} so both worlds
 * agree on what a "diagram variant" is.
 *
 * <p>Three optional contributions, each independently activatable:</p>
 * <ul>
 *   <li>{@code module} — extra GLSP/Sprotty view bindings (e.g. BT
 *       node views). The core bigraph views are owned by
 *       {@code bigraph-diagram-module.ts} and need no entry here.</li>
 *   <li>{@code variantId} + {@code createPalette} — variant-specific
 *       palette content. Looked up by {@link findPaletteFactory}
 *       whenever the server emits {@code SetActiveVariantAction} so
 *       {@code BigraphCustomPalette} can swap in the matching content
 *       block.</li>
 *   <li>{@code getPaletteExports} — optional extra export actions for the
 *       variant palette (e.g. MADROS). SVG export is always included
 *       automatically — extensions do not need to register it.</li>
 * </ul>
 */
export interface IDiagramExtension {
    readonly id: string;
    readonly name: string;
    /**
     * Canonical {@code ModelVariant.id} this entry implements
     * (matches {@code BehaviorTreeExtension.BEHAVIOR_TREE_VARIANT_ID} server-side
     * etc.). Omit for entries that contribute only views without
     * being tied to a specific variant.
     */
    readonly variantId?: string;
    /**
     * Optional codicon name (without the {@code codicon-} prefix)
     * used by variant-aware UI affordances — e.g. the in-canvas
     * variant tab bar — to represent this variant. Should be set
     * whenever {@link variantId} is set so the user has a visual
     * cue to recognise the variant by.
     */
    readonly iconCodicon?: string;
    /** Optional Inversify module with extra view/service bindings. */
    readonly module?: ContainerModule;
    /** Optional factory producing this variant's palette content. */
    readonly createPalette?: VariantPaletteFactory;
    /**
     * Optional extra export actions for this extension's variant palette.
     * SVG diagram export is always included by {@link getPaletteExportsForVariant}.
     */
    readonly getPaletteExports?: (host: PaletteHostApi) => ReadonlyArray<PaletteExportAction>;
    /**
     * Optional import actions for this extension's variant palette.
     */
    readonly getPaletteImports?: (host: PaletteHostApi) => ReadonlyArray<PaletteImportAction>;
    /**
     * GModel element types this extension handles in the context menu
     * (hit-test filter). Required when {@link getContextMenuItems} is set.
     */
    readonly contextMenuElementTypes?: readonly string[];
    /** Variant-specific right-click menu entries. */
    readonly getContextMenuItems?: ContextMenuItemContributor;
    /**
     * Variant-specific drop-container resolution for node creation.
     * Consulted by the core's {@code ExtensionAwareContainerManager}
     * only while this extension's variant is active. Return
     * {@code undefined} to fall back to the GLSP default resolution.
     *
     * <p>Extensions must NOT {@code rebind} Sprotty's
     * {@code TYPES.IContainerManager} themselves — that binding is
     * global to the shared DI container and would leak into other
     * variants.</p>
     */
    readonly findDropContainer?: (
        location: Point,
        ctx: GModelElement,
        evt?: MouseEvent
    ) => ContainerElement | undefined;
    /**
     * Handler for client-local context-menu actions (no server
     * round-trip), e.g. opening an in-canvas dialog. Consulted by the
     * core {@code BigraphContextMenuService} before an action is
     * dispatched to the server. Return {@code true} when the action
     * was consumed.
     */
    readonly handleLocalContextMenuAction?: (
        action: Action,
        anchor: Anchor,
        dispatcher: IActionDispatcher
    ) => boolean;
}

/**
 * Registry of all webview-side diagram extensions. Order is significant
 * only to humans — lookups are by id/variantId.
 */
const extensions: IDiagramExtension[] = [
    {
        id: 'core',
        name: 'Bigraph',
        variantId: 'bigraph',
        iconCodicon: 'symbol-structure',
        createPalette: createBigraphPalette,
        contextMenuElementTypes: BIGRAPH_CONTEXT_MENU_ELEMENT_TYPES,
        getContextMenuItems: getBigraphContextMenuItems,
    },
    {
        id: 'popp',
        name: 'Problem-Oriented Project Planning',
        variantId: 'popp',
        iconCodicon: 'tasklist',
        createPalette: createPOPPPalette,
    },
];

/** Returns all registered diagram extensions (read-only view). */
export function getExtensions(): ReadonlyArray<IDiagramExtension> {
    return extensions;
}

/**
 * Looks up the palette factory contributed for the given variant id.
 * Returns {@code undefined} when no extension claims that variant —
 * callers should render an "unknown variant" fallback rather than
 * crash.
 */
export function findPaletteFactory(variantId: string): VariantPaletteFactory | undefined {
    return extensions.find(e => e.variantId === variantId)?.createPalette;
}

/**
 * Looks up the extension entry that owns the given variant id.
 * Useful for variant-aware UI affordances (e.g. the in-canvas tab
 * bar) that need the variant's display name and icon.
 */
export function findExtensionByVariant(variantId: string): IDiagramExtension | undefined {
    return extensions.find(e => e.variantId === variantId);
}

/**
 * Resolves export actions for a variant palette: universal SVG export
 * plus any extras contributed by the owning extension.
 */
export function getPaletteExportsForVariant(
    variantId: string,
    host: PaletteHostApi
): ReadonlyArray<PaletteExportAction> {
    const ext = findExtensionByVariant(variantId);
    const extensionExports = ext?.getPaletteExports?.(host) ?? [];
    return [createSvgExportAction(), ...extensionExports];
}

export function getPaletteImportsForVariant(
    variantId: string,
    host: PaletteHostApi
): ReadonlyArray<PaletteImportAction> {
    const ext = findExtensionByVariant(variantId);
    return ext?.getPaletteImports?.(host) ?? [];
}

/**
 * Loads every extension's {@link IDiagramExtension.module} into the
 * GLSP diagram container. Entries without a module are skipped.
 *
 * Call this from {@code createBigraphDiagramContainer()} to activate
 * all extension views alongside the core bigraph views.
 */
export function loadExtensions(container: Container): void {
    for (const ext of extensions) {
        if (ext.module) {
            container.load(ext.module);
        }
    }
}
