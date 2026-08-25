/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import type { IActionDispatcher } from '@eclipse-glsp/client';

import { RequestVariantSwitchAction } from '../request-variant-switch-action';
import { VariantChangeEvent, VariantChangedPayload } from '../palette-events';
import { findExtensionByVariant } from '../extensions';
import './variant-switcher.css';

const ROOT_CLASS = 'bp-variant-switcher';
const TAB_CLASS = 'bp-variant-tab';
const ACTIVE_CLASS = 'is-active';
const FALLBACK_ICON = 'circle-large-outline';

/**
 * Floating bottom-center tab bar that lets the user switch between
 * the variants this file can be viewed as (e.g. Bigraph ↔ Behavior
 * Tree). Renders only when more than one variant is available, so
 * pure bigraph files keep the canvas chrome-free.
 *
 * <h2>Lifecycle</h2>
 * <p>One {@link VariantSwitcherTabs} instance lives for the lifetime
 * of the webview. It eagerly subscribes to {@link VariantChangeEvent}
 * in the constructor, so payloads emitted before the GLSP DI container
 * is wired (race conditions on first load) are not lost.</p>
 *
 * <h2>Data flow</h2>
 * <ol>
 *   <li>Server emits {@code SetActiveVariantAction} with the active
 *       and available variants.</li>
 *   <li>{@code SetActiveVariantHandler} forwards both fields onto the
 *       in-webview {@link VariantChangeEvent} bus.</li>
 *   <li>This component re-renders the tab bar; the active tab gets
 *       the {@link #ACTIVE_CLASS} highlight.</li>
 *   <li>Clicking the inactive tab dispatches a
 *       {@link RequestVariantSwitchAction} to the server, which
 *       rebuilds the GModel and broadcasts a fresh
 *       {@code SetActiveVariantAction}.</li>
 * </ol>
 *
 * <p>The mount point is {@code document.body} with
 * {@code position: fixed}, keeping the bar pinned to the viewport
 * regardless of how the diagram is scrolled or zoomed.</p>
 */
export class VariantSwitcherTabs {

    private dispatcher: IActionDispatcher | null = null;
    private readonly rootEl: HTMLElement;
    private currentVariantId: string | null = null;
    private currentAvailable: readonly string[] = [];
    private readonly unsubscribe: () => void;
    /** Guards against re-entrant dispatches while a switch is in flight. */
    private inFlight = false;

    constructor() {
        this.rootEl = document.createElement('div');
        this.rootEl.className = ROOT_CLASS;
        this.rootEl.setAttribute('role', 'tablist');
        this.rootEl.setAttribute('aria-label', 'Diagram variant');
        this.rootEl.hidden = true;
        document.body.appendChild(this.rootEl);

        this.unsubscribe = VariantChangeEvent.on(payload => this.onVariantChanged(payload));
    }

    /**
     * Hands in the GLSP action dispatcher. Called once from
     * {@code app.ts} when the DI container becomes available.
     * Until then, the tab bar still renders — click handlers
     * simply no-op so the user sees the visual state but can't
     * dispatch yet.
     */
    setDispatcher(dispatcher: IActionDispatcher): void {
        this.dispatcher = dispatcher;
    }

    /**
     * Detaches the bar from the DOM and removes the variant-change
     * subscription. Intended for tests / hot reload — not normally
     * called in production webview sessions.
     */
    dispose(): void {
        this.unsubscribe();
        this.rootEl.remove();
    }

    // ── Event handling ──────────────────────────────────────────────

    private onVariantChanged(payload: VariantChangedPayload): void {
        this.currentVariantId = payload.variantId;
        this.currentAvailable = payload.availableVariantIds ?? [payload.variantId];
        this.inFlight = false;
        this.render();
    }

    private onTabClick(variantId: string): void {
        if (this.inFlight) return;
        if (variantId === this.currentVariantId) return;
        if (!this.dispatcher) {
            console.warn('[VariantSwitcher] dispatcher not ready — ignoring click');
            return;
        }
        this.inFlight = true;
        this.dispatcher.dispatch(RequestVariantSwitchAction.create(variantId));
    }

    // ── Rendering ───────────────────────────────────────────────────

    private render(): void {
        // Single-variant files have nothing to switch between —
        // collapse the bar entirely so the canvas stays clean.
        if (this.currentAvailable.length < 2) {
            this.rootEl.hidden = true;
            this.rootEl.innerHTML = '';
            return;
        }

        this.rootEl.innerHTML = '';
        for (const variantId of this.currentAvailable) {
            this.rootEl.appendChild(this.buildTab(variantId));
        }
        this.rootEl.hidden = false;
    }

    private buildTab(variantId: string): HTMLButtonElement {
        const ext = findExtensionByVariant(variantId);
        const label = ext?.name ?? variantId;
        const iconName = ext?.iconCodicon ?? FALLBACK_ICON;
        const isActive = variantId === this.currentVariantId;

        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = TAB_CLASS + (isActive ? ' ' + ACTIVE_CLASS : '');
        btn.setAttribute('role', 'tab');
        btn.setAttribute('aria-selected', String(isActive));
        btn.setAttribute('aria-label', label);
        btn.title = label;
        btn.dataset.variantId = variantId;
        btn.tabIndex = isActive ? -1 : 0;

        const icon = document.createElement('span');
        icon.className = `codicon codicon-${iconName}`;
        icon.setAttribute('aria-hidden', 'true');
        btn.appendChild(icon);

        btn.addEventListener('click', () => this.onTabClick(variantId));
        return btn;
    }
}
