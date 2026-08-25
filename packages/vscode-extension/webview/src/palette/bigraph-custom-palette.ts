/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

/**
 * BigraphCustomPalette — variant-agnostic SHELL for the bigraph editor
 * tool palette.
 *
 * <p>Despite the class name, this is not a "bigraph-only" palette
 * anymore. It owns only the invariant parts of the UI (collapse strip,
 * hero, mode bar) and delegates the modeling content to a per-variant
 * {@link IVariantPalette} implementation. Export actions are contributed
 * per extension and rendered inside each variant palette — not in this
 * shell. The name was kept to avoid churn across {@code app.ts} and the
 * webview HTML CSS scope — see the class doc on
 * {@code IVariantPalette} for the swap contract.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@code app.ts} instantiates this class. The constructor starts
 *       polling for the default GLSP tool palette element so it can
 *       hide it and mount our shell next to it.</li>
 *   <li>{@code setDispatcher(dispatcher)} is called once the GLSP DI
 *       container is up. The shell stores the dispatcher and starts
 *       fetching server palette items so any variant palette can read
 *       them via {@link PaletteHostApi}.</li>
 *   <li>The shell subscribes to {@link VariantChangeEvent}. Each time
 *       the server announces a new active variant, the shell looks up
 *       the matching factory via {@link findPaletteFactory}, disposes
 *       the previous variant palette, and mounts the new one.</li>
 * </ol>
 *
 * <h2>Why a "host" rather than a container split</h2>
 * The variant palette needs the same dispatcher + server-fetched
 * palette items the shell already maintains. Rather than duplicating
 * the fetching logic per variant, the shell exposes a small
 * {@link PaletteHostApi} that the variant can read from. This keeps
 * one source of truth and one network round-trip.
 */

import {
    IActionDispatcher,
    PaletteItem,
    EnableDefaultToolsAction,
    EnableToolsAction,
    RequestContextActions,
    SetContextActions,
    OriginViewportAction,
    FitToScreenAction,
} from '@eclipse-glsp/client';
import { findPaletteFactory } from '../extension/extensions';
import { VariantChangeEvent } from '../extension/palette-events';
import type { IVariantPalette, PaletteHostApi } from '../extension/palette-types';
import paletteHtml from './bigraph-palette.html';
import orbitToolbarHeroSvg from './assets/orbit-toolbar-hero.svg';
import './bigraph-palette.css';

// ── Constants ──────────────────────────────────────────────────────────

const GLSP_PALETTE_SELECTORS = ['.tool-palette', '.sprotty-palette', '.glsp-palette'];
const PALETTE_ID = 'bigraph-custom-palette';
const DELETE_TOOL_ID = 'glsp.delete-mouse';
const DEBOUNCE_MS = 400;
const VARIANT_SLOT_SELECTOR = '[data-slot="variant-content"]';
const POLL_INTERVAL_MS = 120;

function q<T extends HTMLElement>(scope: HTMLElement, sel: string): T {
    return scope.querySelector(sel) as T;
}

function cmpPalette(a: PaletteItem, b: PaletteItem): number {
    return (a.sortString ?? a.label).localeCompare(b.sortString ?? b.label);
}

// ── Palette shell ──────────────────────────────────────────────────────

export class BigraphCustomPalette {
    private dispatcher: IActionDispatcher | null = null;
    private root: HTMLDivElement | null = null;
    private variantSlot: HTMLElement | null = null;
    private activeVariantId: string | null = null;
    private activeVariantPalette: IVariantPalette | null = null;
    private paletteItems: PaletteItem[] = [];
    private defaultModeBtn: HTMLElement | null = null;
    private activeModeBtn: HTMLElement | null = null;
    private collapsed = false;
    private refreshTimer: ReturnType<typeof setTimeout> | undefined;
    private initialized = false;
    private currentMode: 'select' | 'delete' | 'creation' = 'select';
    private unsubscribeVariantEvents: (() => void) | null = null;

    constructor() {
        this.waitForGlspPalette();
        window.addEventListener('bigraph-palette-refresh', () => this.scheduleRefresh());
        this.unsubscribeVariantEvents = VariantChangeEvent.on(({ variantId }) => {
            this.switchVariant(variantId);
        });
    }

    setDispatcher(d: IActionDispatcher): void {
        this.dispatcher = d;
        if (this.root && !this.initialized) {
            this.fetchAndRender();
        }
    }

    async refresh(): Promise<void> {
        if (!this.dispatcher || !this.root) return;
        await this.fetchPaletteItems();
        this.activeVariantPalette?.refresh();
    }

    // ── Bootstrap ──────────────────────────────────────────────────────

    private waitForGlspPalette(): void {
        const poll = setInterval(() => {
            const el = this.findGlspPalette();
            if (el) {
                clearInterval(poll);
                this.hideGlspPalette(el);
                this.mount(el);
                if (this.dispatcher) this.fetchAndRender();
            }
        }, POLL_INTERVAL_MS);
    }

    private findGlspPalette(): Element | null {
        for (const s of GLSP_PALETTE_SELECTORS) {
            const el = document.querySelector(s);
            if (el) return el;
        }
        return null;
    }

    private hideGlspPalette(el: Element): void {
        (el as HTMLElement).style.display = 'none';
        const btn = el.parentElement?.querySelector('.minimize-palette-button');
        if (btn) (btn as HTMLElement).style.display = 'none';
    }

    private mount(glspPalette: Element): void {
        const parent = glspPalette.parentElement;
        if (!parent) return;
        const root = document.createElement('div');
        root.id = PALETTE_ID;
        root.classList.add('bigraph-palette');
        root.innerHTML = paletteHtml;
        parent.insertBefore(root, glspPalette);
        this.root = root;
        this.variantSlot = root.querySelector<HTMLElement>(VARIANT_SLOT_SELECTOR);
        this.attachHeroSvg();
        this.bindShellEvents();

        // If SetActiveVariantAction was already delivered before the GLSP
        // tool-palette element existed in the DOM (race: server is fast,
        // sprotty's first render is delayed), switchVariant() above will
        // have stashed the id but couldn't mount yet because variantSlot
        // was null. Catch up here now that the slot is live.
        if (this.activeVariantId) {
            console.log('[BigraphPalette] post-mount catch-up for variant', this.activeVariantId);
            this.mountVariantPalette(this.activeVariantId);
        }
    }

    private attachHeroSvg(): void {
        if (!this.root) return;
        const heroContainer = this.root.querySelector<HTMLElement>('.bp-toolbar-hero-svg');
        if (!heroContainer) return;
        // Imported SVG markup is inlined directly — relative img/src would
        // not resolve inside a webview HTML string.
        heroContainer.innerHTML = orbitToolbarHeroSvg;
    }

    private async fetchAndRender(): Promise<void> {
        this.initialized = true;
        await this.fetchPaletteItems();
        // The variant palette is mounted lazily on SetActiveVariantAction.
        // If we already know the variant (e.g. delayed dispatcher wiring),
        // re-render now.
        this.activeVariantPalette?.refresh();
    }

    // ── Server communication ───────────────────────────────────────────

    private async fetchPaletteItems(): Promise<void> {
        if (!this.dispatcher) return;
        try {
            const req = RequestContextActions.create({
                contextId: 'tool-palette',
                editorContext: { selectedElementIds: [] },
            });
            const res = (await this.dispatcher.request(req)) as SetContextActions;
            this.paletteItems = (res.actions ?? [])
                .filter((a): a is PaletteItem => 'id' in a && 'sortString' in a)
                .slice()
                .sort(cmpPalette);
        } catch (err) {
            console.warn('[BigraphPalette] fetch failed:', err);
        }
    }

    // ── Variant lifecycle ──────────────────────────────────────────────

    private switchVariant(variantId: string): void {
        if (this.activeVariantId === variantId && this.activeVariantPalette) {
            // Same variant — just refresh.
            this.activeVariantPalette.refresh();
            return;
        }
        this.activeVariantId = variantId;
        this.mountVariantPalette(variantId);
    }

    private mountVariantPalette(variantId: string): void {
        if (!this.variantSlot) return;
        // Tear down whatever was in the slot.
        this.activeVariantPalette?.dispose();
        this.activeVariantPalette = null;
        this.variantSlot.innerHTML = '';

        const factory = findPaletteFactory(variantId);
        if (!factory) {
            this.renderUnknownVariantFallback(variantId);
            return;
        }
        const host = this.buildHostApi();
        const palette = factory(host);
        palette.mount(this.variantSlot);
        // After the first mount, push any data we already have.
        if (this.paletteItems.length > 0) {
            palette.refresh();
        }
        this.activeVariantPalette = palette;
    }

    private renderUnknownVariantFallback(variantId: string): void {
        if (!this.variantSlot) return;
        const msg = document.createElement('div');
        msg.className = 'bp-empty';
        msg.textContent = `No palette registered for variant "${variantId}".`;
        this.variantSlot.appendChild(msg);
    }

    private buildHostApi(): PaletteHostApi {
        return {
            // `get`-style getters so the variant always sees the freshest
            // dispatcher/items snapshot rather than a captured stale copy.
            get dispatcher(): IActionDispatcher {
                // The shell never mounts a variant before setDispatcher().
                return this._self.dispatcher as IActionDispatcher;
            },
            get paletteItems(): ReadonlyArray<PaletteItem> {
                return this._self.paletteItems;
            },
            notifyCreationToolActivated: () => this.handleCreationToolActivated(),
            requestRefresh: () => this.scheduleRefresh(),
            // Hidden back-reference used by the getters above. Marked
            // non-enumerable so JSON.stringify doesn't choke on it.
            _self: this,
        } as PaletteHostApi & { _self: BigraphCustomPalette };
    }

    private handleCreationToolActivated(): void {
        this.currentMode = 'creation';
        this.activeModeBtn?.classList.remove('bp-active');
    }

    // ── Static event wiring (runs once after mount) ────────────────────

    private bindShellEvents(): void {
        const r = this.root!;

        // Collapse strip buttons
        Array.from(r.querySelectorAll<HTMLElement>('.bp-collapse-strip .bp-strip-btn')).forEach(btn => {
            const action = btn.dataset.action;
            btn.onclick = () => this.handleStripAction(action);
        });

        // Collapse toggle
        q(r, '.bp-collapse-toggle').onclick = () => this.toggleCollapse();

        // Mode bar buttons
        this.defaultModeBtn = q(r, '.bp-mode-bar .bp-mode-btn[data-action="select"]');
        this.activeModeBtn = this.defaultModeBtn;

        Array.from(r.querySelectorAll<HTMLElement>('.bp-mode-bar .bp-mode-btn')).forEach(btn => {
            btn.onclick = () => {
                const action = btn.dataset.action!;
                const momentary = btn.dataset.momentary === 'true';
                this.handleModeAction(action);
                if (!momentary) this.setActiveMode(btn);
            };
        });

        // Keep delete mode active until the user explicitly switches back
        // to select. GLSP may auto-fall back to default tools after a
        // deletion — without this re-arm the delete button would visually
        // stay active but lose its tool behaviour.
        document.addEventListener('pointerup', (ev) => {
            if (this.currentMode !== 'delete') return;
            if (this.root?.contains(ev.target as Node)) return;
            setTimeout(() => this.reapplyDeleteMode(), 0);
        });
    }

    // ── Action handlers ────────────────────────────────────────────────

    private handleStripAction(action: string | undefined): void {
        switch (action) {
            case 'expand': this.toggleCollapse(); break;
            case 'select':
                this.currentMode = 'select';
                this.dispatch(EnableDefaultToolsAction.create());
                break;
            case 'delete':
                this.currentMode = 'delete';
                this.dispatch(EnableToolsAction.create([DELETE_TOOL_ID]));
                break;
            case 'reset-view': this.dispatch(OriginViewportAction.create()); break;
        }
    }

    private handleModeAction(action: string): void {
        switch (action) {
            case 'select':
                this.currentMode = 'select';
                this.dispatch(EnableDefaultToolsAction.create());
                break;
            case 'delete':
                this.currentMode = 'delete';
                this.dispatch(EnableToolsAction.create([DELETE_TOOL_ID]));
                break;
            case 'reset': this.dispatch(OriginViewportAction.create()); break;
            case 'fit': this.dispatch(FitToScreenAction.create([])); break;
        }
    }

    private toggleCollapse(): void {
        this.collapsed = !this.collapsed;
        this.root?.classList.toggle('bp-collapsed', this.collapsed);
    }

    private setActiveMode(btn: HTMLElement): void {
        this.activeModeBtn?.classList.remove('bp-active');
        btn.classList.add('bp-active');
        this.activeModeBtn = btn;
    }

    // ── Dispatch helpers ───────────────────────────────────────────────

    private dispatch(action: unknown): void {
        this.dispatcher?.dispatch(action as Parameters<IActionDispatcher['dispatch']>[0]);
    }

    private scheduleRefresh(delay = DEBOUNCE_MS): void {
        clearTimeout(this.refreshTimer);
        this.refreshTimer = setTimeout(() => this.refresh(), delay);
    }

    private reapplyDeleteMode(): void {
        if (this.currentMode !== 'delete') return;
        this.dispatch(EnableToolsAction.create([DELETE_TOOL_ID]));
    }

    // ── Teardown (kept for symmetry; the palette lives for the page) ───

    dispose(): void {
        this.activeVariantPalette?.dispose();
        this.activeVariantPalette = null;
        this.unsubscribeVariantEvents?.();
        this.unsubscribeVariantEvents = null;
    }
}
