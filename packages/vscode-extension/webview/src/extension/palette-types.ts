/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { IActionDispatcher, PaletteItem } from '@eclipse-glsp/client';

/**
 * Lifecycle contract implemented by every variant-specific palette
 * module.
 *
 * <p>The {@code BigraphCustomPalette} host owns the surrounding shell
 * (mode bar, hero, collapse strip) and embeds exactly one
 * {@link IVariantPalette} in its variant slot. Export actions are
 * contributed per extension and mounted inside the variant palette.
 * Whenever the active variant changes, the host {@link #dispose disposes}
 * the previous variant palette and instantiates the next one via its factory.</p>
 */
export interface IVariantPalette {
    /**
     * Renders this variant's palette content into the supplied host
     * container. Called exactly once after construction. Implementors
     * own the DOM <em>inside</em> {@code container}; the host clears
     * the slot before mounting and after {@link #dispose}.
     */
    mount(container: HTMLElement): void;

    /**
     * Re-renders the palette content. The host calls this whenever
     * the underlying server-driven palette item list changes (e.g.
     * after a new control is added to the signature). Should be cheap
     * and idempotent.
     */
    refresh(): void;

    /**
     * Releases any DOM nodes, event listeners, or timers held by this
     * palette. Must be called by the host before unmounting or
     * swapping to another variant.
     */
    dispose(): void;
}

/**
 * Read-only facade the host exposes to its currently mounted variant
 * palette. Lets the variant dispatch GLSP actions, read the latest
 * server palette items, and inform the host that a creation tool has
 * been activated — without leaking the host's internal state.
 */
export interface PaletteHostApi {
    /** GLSP action dispatcher for the active diagram session. */
    readonly dispatcher: IActionDispatcher;

    /** Latest server-fetched palette items, sorted by {@code sortString}. */
    readonly paletteItems: ReadonlyArray<PaletteItem>;

    /**
     * Notifies the host that the user activated a creation tool inside
     * the variant palette. The host uses this to deselect the mode
     * bar's currently active "Select"/"Delete" button.
     */
    notifyCreationToolActivated(): void;

    /**
     * Asks the host to re-fetch palette items from the server. The
     * host triggers {@link IVariantPalette#refresh} once the new list
     * has arrived.
     */
    requestRefresh(): void;
}

/**
 * Factory contract used by {@code IDiagramExtension.createPalette}.
 * Variant entries return a fresh palette instance per diagram session,
 * each wired up against the supplied host API.
 */
export type VariantPaletteFactory = (host: PaletteHostApi) => IVariantPalette;
