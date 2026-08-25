/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { PaletteItem } from '@eclipse-glsp/client';
import { BigraphControlCreatorTool } from './BigraphControlCreatorTool';
import { appendPaletteExportSection } from '../../palette-export';
import { getPaletteExportsForVariant } from '../../extensions';
import type {
    IVariantPalette,
    PaletteHostApi,
    VariantPaletteFactory,
} from '../../palette-types';
import variantHtml from './bigraph-variant.html';
import { BIGRAPH_DISPLAY_SETTINGS_SET } from '../display/bigraph-display-refresh';
import './bigraph-variant.css';

const BIGRAPH_VARIANT_ID = 'bigraph';

/**
 * Bigraph variant palette content — Place Graph, Link Graph, and
 * Layout sections.
 *
 * <p>The shell handles the surrounding chrome (collapse strip, hero,
 * mode bar). This class owns only the modeling content for the bigraph
 * variant, including extension-contributed export actions.</p>
 */

const PLACE_GROUP_ID = 'bigraph-nodes';
const LINK_GROUP_ID = 'bigraph-links';
const PLACEHOLDER_ITEM_ID = 'bigraph.node.placeholder';
const SITE_ITEM_ID = 'bigraph.node.site';
const CONNECT_TOOL_ID = 'bigraph.link.connect';

const LINK_ORDER = ['bigraph.link.edge', 'bigraph.link.outer', 'bigraph.link.inner'];

const LINK_META: Record<string, { icon: string; desc: string }> = {
    'bigraph.link.edge':  { icon: 'codicon-git-commit',      desc: 'Hyperedge connecting multiple ports' },
    'bigraph.link.inner': { icon: 'codicon-arrow-small-up',   desc: 'Interface name (inner boundary)' },
    'bigraph.link.outer': { icon: 'codicon-arrow-small-down', desc: 'Interface name (outer boundary)' },
};

const STATUS_ABBREV: Record<string, string> = { ATOMIC: 'AT', ACTIVE: 'AC', PASSIVE: 'PA' };
const STATUS_LABELS: Record<string, string> = { AT: 'Atomic', AC: 'Active', PA: 'Passive' };

function cmpPalette(a: PaletteItem, b: PaletteItem): number {
    return (a.sortString ?? a.label).localeCompare(b.sortString ?? b.label);
}

function fireBigraphAction(detail: Record<string, unknown>): void {
    window.dispatchEvent(new CustomEvent('bigraph-action', { detail }));
}

class BigraphVariantPalette implements IVariantPalette {
    private container: HTMLElement | null = null;
    private activeCreationEl: HTMLElement | null = null;
    private layoutSeg: HTMLElement | null = null;

    constructor(private readonly host: PaletteHostApi) {}

    mount(container: HTMLElement): void {
        this.container = container;
        container.innerHTML = variantHtml;
        this.bindStaticEvents();
        this.refresh();
        appendPaletteExportSection(
            container,
            getPaletteExportsForVariant(BIGRAPH_VARIANT_ID, this.host),
            this.host
        );
    }

    refresh(): void {
        if (!this.container) return;
        const sorted = [...this.host.paletteItems];
        const placeGroup = sorted.find(i => i.children && i.id === PLACE_GROUP_ID);
        const linkGroup = sorted.find(i => i.children && i.id === LINK_GROUP_ID);
        this.populatePlaceGraph(placeGroup);
        this.populateLinkGraph(linkGroup);
    }

    dispose(): void {
        this.container = null;
        this.activeCreationEl = null;
        this.layoutSeg = null;
    }

    // ── Static event wiring ────────────────────────────────────────

    private bindStaticEvents(): void {
        const r = this.container!;

        // Search
        const searchWrap = r.querySelector<HTMLElement>('.bp-search-wrap')!;
        const searchInput = r.querySelector<HTMLInputElement>('.bp-search-field')!;
        const searchClear = r.querySelector<HTMLElement>('.bp-search-clear')!;

        searchInput.oninput = () => {
            this.applyFilter(searchInput.value);
            searchWrap.classList.toggle('has-query', searchInput.value.length > 0);
        };
        searchInput.onkeydown = (ev) => {
            if (ev.key === 'Escape') {
                searchInput.value = '';
                this.applyFilter('');
                searchWrap.classList.remove('has-query');
            }
        };
        searchClear.onclick = () => {
            searchInput.value = '';
            this.applyFilter('');
            searchWrap.classList.remove('has-query');
            searchInput.focus();
        };

        // Add control dialog
        const addCtrlBtn = r.querySelector<HTMLElement>('[data-section="place-graph"] [data-action="add-control"]');
        if (addCtrlBtn) {
            addCtrlBtn.onclick = (e) => {
                e.stopPropagation();
                new BigraphControlCreatorTool().showAddControlDialog();
            };
        }

        // Link Graph connect tool
        const connectBtn = r.querySelector<HTMLElement>('[data-section="link-graph"] [data-action="connect"]');
        if (connectBtn) {
            connectBtn.onclick = (e) => {
                e.stopPropagation();
                const connectItem = this.findConnectTool();
                if (connectItem?.actions?.length) {
                    this.dispatchAll(connectItem.actions);
                    this.setActiveCreation(connectBtn);
                }
            };
        }

        this.bindDisplayToggles();

        // Layout segmented control
        Array.from(r.querySelectorAll<HTMLElement>('.bp-seg')).forEach(seg => {
            seg.onclick = () => {
                const algorithm = seg.dataset.algorithm!;
                const bigraphStandard = seg.dataset.standard === 'true';
                fireBigraphAction({ kind: 'bigraph.autoLayout', algorithm, bigraphStandard });
                this.layoutSeg?.classList.remove('bp-seg-active');
                seg.classList.add('bp-seg-active');
                this.layoutSeg = seg;
                setTimeout(() => seg.classList.remove('bp-seg-active'), 1200);
            };
        });
    }

    // ── Dynamic content ────────────────────────────────────────────

    private populatePlaceGraph(group: PaletteItem | undefined): void {
        if (!this.container) return;
        const section = this.container.querySelector<HTMLElement>('[data-content="place-graph"]')!;
        let grid = section.querySelector<HTMLElement>('.bp-card-grid');

        // If the previous render replaced the grid with an empty
        // placeholder, recreate it so we never bind to a stale node.
        if (!grid) {
            const emptyPlaceholder = section.querySelector('.bp-empty');
            grid = document.createElement('div');
            grid.className = 'bp-card-grid';
            emptyPlaceholder?.replaceWith(grid);
            if (!section.contains(grid)) {
                section.prepend(grid);
            }
        }
        grid.innerHTML = '';

        if (!group?.children?.length) {
            const empty = document.createElement('div');
            empty.className = 'bp-empty';
            empty.textContent = 'No controls defined';
            grid.replaceWith(empty);
            return;
        }

        const children = [...group.children].sort(cmpPalette);
        const siteItem = children.find(c => c.id === SITE_ITEM_ID);
        const nodeItems = children.filter(c => c.id !== SITE_ITEM_ID);

        if (siteItem) grid.appendChild(this.buildSiteCard(siteItem));
        for (const item of nodeItems) grid.appendChild(this.buildCard(item));
    }

    private populateLinkGraph(group: PaletteItem | undefined): void {
        if (!this.container) return;
        const list = this.container.querySelector<HTMLElement>('[data-content="link-graph"] .bp-link-list')!;
        list.innerHTML = '';

        if (!group?.children?.length) return;

        const children = [...group.children]
            .filter(c => c.id !== CONNECT_TOOL_ID)
            .sort((a, b) => {
                const ia = LINK_ORDER.indexOf(a.id);
                const ib = LINK_ORDER.indexOf(b.id);
                return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
            });

        for (const item of children) {
            const meta = LINK_META[item.id] ?? { icon: 'codicon-circle-outline', desc: '' };
            const row = document.createElement('div');
            row.className = 'bp-link-item';
            row.setAttribute('data-label', item.label);
            row.title = item.label;
            row.innerHTML = `
                <div class="bp-link-icon"><span class="codicon ${meta.icon}"></span></div>
                <div class="bp-link-text">
                    <div class="bp-link-name">${item.label}</div>
                    <div class="bp-link-desc">${meta.desc}</div>
                </div>
            `;
            row.onclick = () => {
                if (item.actions?.length) this.dispatchAll(item.actions);
                this.setActiveCreation(row);
            };
            list.appendChild(row);
        }
    }

    // ── Card builders ──────────────────────────────────────────────

    private buildPlaceholderHint(item: PaletteItem): HTMLElement {
        const el = document.createElement('div');
        el.className = 'bp-place-hint';
        el.setAttribute('data-label', item.label);
        el.textContent = item.label;
        return el;
    }

    private buildCard(item: PaletteItem): HTMLElement {
        if (item.id === PLACEHOLDER_ITEM_ID) {
            return this.buildPlaceholderHint(item);
        }

        const card = document.createElement('div');
        card.className = 'bp-card';
        card.setAttribute('data-label', item.label);

        // Server formats labels as "ControlName (arity: N)". Parse the
        // suffix once so we can render arity as a separate chip.
        const parts = item.label.match(/^(.+?)\s*\(arity:\s*(\d+)\)$/);
        const controlName = parts?.[1] ?? item.label;
        const arity = parts?.[2] ?? null;

        const rawStatus = (item.actions?.[0] as any)?.args?.status as string | undefined;
        const status = rawStatus ? (STATUS_ABBREV[rawStatus] ?? rawStatus) : null;
        const statusLong = status ? (STATUS_LABELS[status] ?? status) : '';

        card.title = controlName + (arity ? ` · arity ${arity}` : '') + (statusLong ? ` · ${statusLong}` : '');

        card.innerHTML = `
            <div class="bp-card-name">${controlName}</div>
            <div class="bp-card-meta">
                ${arity ? `<span class="bp-card-arity"><span class="bp-card-dot"></span>${arity}</span>` : ''}
                ${status ? `<span class="bp-card-status" data-status="${status}">${status}</span>` : ''}
            </div>
        `;

        card.onclick = () => {
            if (item.actions?.length) this.dispatchAll(item.actions);
            this.setActiveCreation(card);
        };

        return card;
    }

    private buildSiteCard(item: PaletteItem): HTMLElement {
        const card = document.createElement('div');
        card.className = 'bp-card bp-card-site';
        card.setAttribute('data-label', item.label);
        card.title = item.label;
        card.innerHTML = `
            <span class="codicon codicon-browser" style="font-size:14px;color:var(--vscode-descriptionForeground)"></span>
            <div class="bp-card-name">Site</div>
        `;
        card.onclick = () => {
            if (item.actions?.length) this.dispatchAll(item.actions);
            this.setActiveCreation(card);
        };
        return card;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private bindDisplayToggles(): void {
        const rootsInput = this.container!.querySelector<HTMLInputElement>(
            '[data-display-toggle="roots"]'
        );
        const namesInput = this.container!.querySelector<HTMLInputElement>(
            '[data-display-toggle="node-names"]'
        );
        const arrowsInput = this.container!.querySelector<HTMLInputElement>(
            '[data-display-toggle="place-arrows"]'
        );
        if (!rootsInput || !namesInput) {
            return;
        }

        const emit = (): void => {
            window.dispatchEvent(new CustomEvent(BIGRAPH_DISPLAY_SETTINGS_SET, {
                detail: {
                    showRoots: rootsInput.checked,
                    showNodeNames: namesInput.checked,
                    showPlaceGraphArrows: arrowsInput?.checked ?? true,
                },
            }));
        };

        rootsInput.onchange = () => emit();
        namesInput.onchange = () => emit();
        if (arrowsInput) {
            arrowsInput.onchange = () => emit();
        }
        emit();
    }

    private findConnectTool(): PaletteItem | undefined {
        const linkGroup = this.host.paletteItems.find(i => i.children && i.id === LINK_GROUP_ID);
        return linkGroup?.children?.find(item => item.id === CONNECT_TOOL_ID);
    }

    private applyFilter(query: string): void {
        if (!this.container) return;
        const lc = query.toLowerCase();
        this.container.querySelectorAll<HTMLElement>('[data-label]').forEach(el => {
            const match = !lc || (el.getAttribute('data-label') ?? '').toLowerCase().includes(lc);
            el.classList.toggle('bp-filtered-out', !match);
        });
    }

    private setActiveCreation(el: HTMLElement): void {
        this.clearActiveCreation();
        el.classList.add('bp-active');
        this.activeCreationEl = el;
        this.host.notifyCreationToolActivated();
    }

    private clearActiveCreation(): void {
        this.activeCreationEl?.classList.remove('bp-active');
        this.activeCreationEl = null;
    }

    private dispatchAll(actions: unknown[]): void {
        const dispatcher = this.host.dispatcher;
        if (!dispatcher) return;
        dispatcher.dispatchAll(actions as Parameters<typeof dispatcher.dispatchAll>[0]);
    }
}

/**
 * Factory exported to {@code extensions.ts} as the bigraph variant's
 * {@code createPalette} contribution.
 */
export const createBigraphPalette: VariantPaletteFactory = (host) => new BigraphVariantPalette(host);
