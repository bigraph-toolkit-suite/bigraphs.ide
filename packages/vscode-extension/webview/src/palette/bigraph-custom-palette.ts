/**
 * BigraphCustomPalette — Full replacement for the GLSP default ToolPalette.
 *
 * The static DOM skeleton lives in bigraph-palette.html; this module loads it,
 * wires up event handlers, and injects dynamic server content (cards / chips).
 *
 * Every colour uses var(--vscode-*) tokens — no hardcoded hex.
 * Exposes refresh() for hot-reloading after control creation.
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
import { BigraphControlCreatorTool } from './BigraphControlCreatorTool';
import paletteHtml from './bigraph-palette.html';
import orbitToolbarHeroSvg from './assets/orbit-toolbar-hero.svg';
import './bigraph-palette.css';

// ── Constants ──────────────────────────────────────────────────────────

const GLSP_PALETTE_SELECTORS = ['.tool-palette', '.sprotty-palette', '.glsp-palette'];
const PALETTE_ID = 'bigraph-custom-palette';
const DELETE_TOOL_ID = 'glsp.delete-mouse';
const DEBOUNCE_MS = 400;

const LINK_META: Record<string, { icon: string; desc: string }> = {
    'bigraph.link.edge':  { icon: 'codicon-git-commit',       desc: 'Hyperedge connecting multiple ports' },
    'bigraph.link.inner': { icon: 'codicon-arrow-small-up',    desc: 'Interface name (inner boundary)' },
    'bigraph.link.outer': { icon: 'codicon-arrow-small-down',  desc: 'Interface name (outer boundary)' },
};

function fireBigraphAction(detail: Record<string, unknown>): void {
    window.dispatchEvent(new CustomEvent('bigraph-action', { detail }));
}

function q<T extends HTMLElement>(scope: HTMLElement, sel: string): T {
    return scope.querySelector(sel) as T;
}

function cmpPalette(a: PaletteItem, b: PaletteItem): number {
    return (a.sortString ?? a.label).localeCompare(b.sortString ?? b.label);
}

// ── Palette class ──────────────────────────────────────────────────────

export class BigraphCustomPalette {
    private dispatcher: IActionDispatcher | null = null;
    private root: HTMLDivElement | null = null;
    private activeCreationEl: HTMLElement | null = null;
    private defaultModeBtn: HTMLElement | null = null;
    private activeModeBtn: HTMLElement | null = null;
    private paletteItems: PaletteItem[] = [];
    private collapsed = false;
    private refreshTimer: ReturnType<typeof setTimeout> | undefined;
    private initialized = false;
    private currentMode: 'select' | 'delete' | 'creation' = 'select';

    constructor() {
        this.waitForGlspPalette();
        window.addEventListener('bigraph-palette-refresh', () => this.scheduleRefresh());
    }

    setDispatcher(d: IActionDispatcher): void {
        this.dispatcher = d;
        if (this.root && !this.initialized) this.fetchAndRender();
    }

    async refresh(): Promise<void> {
        if (!this.dispatcher || !this.root) return;
        await this.fetchPaletteItems();
        this.populateDynamicContent();
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
        }, 120);
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
        this.attachHeroSvg();
        this.bindStaticEvents();
    }

    private attachHeroSvg(): void {
        if (!this.root) return;
        const heroContainer = this.root.querySelector<HTMLElement>('.bp-toolbar-hero-svg');
        if (!heroContainer) return;
        // Render imported SVG markup directly. This avoids failing relative img/src resolution in webview HTML strings.
        heroContainer.innerHTML = orbitToolbarHeroSvg;
    }

    private async fetchAndRender(): Promise<void> {
        this.initialized = true;
        await this.fetchPaletteItems();
        this.populateDynamicContent();
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
            this.paletteItems = (res.actions ?? []).filter(
                (a): a is PaletteItem => 'id' in a && 'sortString' in a,
            );
        } catch (err) {
            console.warn('[BigraphPalette] fetch failed:', err);
        }
    }

    // ── Static event wiring (runs once after mount) ────────────────────

    private bindStaticEvents(): void {
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

        // Keep delete mode active until the user explicitly switches back to select.
        // GLSP may auto-fall back to default tools after a deletion.
        document.addEventListener('pointerup', (ev) => {
            if (this.currentMode !== 'delete') { return; }
            if (this.root?.contains(ev.target as Node)) { return; }
            setTimeout(() => this.reapplyDeleteMode(), 0);
        });

        // Search
        const searchWrap = q(r, '.bp-search-wrap');
        const searchInput = q<HTMLInputElement>(r, '.bp-search-field');
        const searchClear = q(r, '.bp-search-clear');

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

        // Place Graph action buttons
        const addCtrlBtn = q(r, '[data-section="place-graph"] [data-action="add-control"]');
        addCtrlBtn.onclick = (e) => { e.stopPropagation(); new BigraphControlCreatorTool().showAddControlDialog(); };

        // Link Graph header action button (connect tool)
        const connectBtn = q(r, '[data-section="link-graph"] [data-action="connect"]');
        connectBtn.onclick = (e) => {
            e.stopPropagation();
            const connectItem = this.findConnectTool();
            if (connectItem?.actions?.length) {
                this.dispatchAll(connectItem.actions);
                this.setActiveCreation(connectBtn);
            }
        };

        // Layout segmented control
        let activeSeg: HTMLElement | null = null;
        Array.from(r.querySelectorAll<HTMLElement>('.bp-seg')).forEach(seg => {
            seg.onclick = () => {
                const algorithm = seg.dataset.algorithm!;
                const bigraphStandard = seg.dataset.standard === 'true';
                fireBigraphAction({ kind: 'bigraph.autoLayout', algorithm, bigraphStandard });
                activeSeg?.classList.remove('bp-seg-active');
                seg.classList.add('bp-seg-active');
                activeSeg = seg;
                setTimeout(() => seg.classList.remove('bp-seg-active'), 1200);
            };
        });

        // Action row buttons
        q(r, '[data-action="export-svg"]').onclick = () => fireBigraphAction({ kind: 'requestExportSvg' });
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
        this.clearActiveCreation();
    }

    // ── Dynamic content (server-driven) ────────────────────────────────

    private populateDynamicContent(): void {
        if (!this.root) return;
        const sorted = [...this.paletteItems].sort(cmpPalette);
        const placeGroup = sorted.find(i => i.children && i.id === 'bigraph-nodes');
        const linkGroup = sorted.find(i => i.children && i.id === 'bigraph-links');

        this.populatePlaceGraph(placeGroup);
        this.populateLinkGraph(linkGroup);
    }

    private populatePlaceGraph(group: PaletteItem | undefined): void {
        const grid = q(this.root!, '[data-content="place-graph"] .bp-card-grid');
        grid.innerHTML = '';

        if (!group?.children?.length) {
            const empty = document.createElement('div');
            empty.className = 'bp-empty';
            empty.textContent = 'No controls defined';
            grid.replaceWith(empty);
            return;
        }

        const children = [...group.children].sort(cmpPalette);
        const siteItem = children.find(c => c.id === 'bigraph.node.site');
        const nodeItems = children.filter(c => c.id !== 'bigraph.node.site');

        if (siteItem) grid.appendChild(this.buildSiteCard(siteItem));
        for (const item of nodeItems) grid.appendChild(this.buildCard(item));
    }

    private populateLinkGraph(group: PaletteItem | undefined): void {
        const list = q(this.root!, '[data-content="link-graph"] .bp-link-list');
        list.innerHTML = '';

        if (!group?.children?.length) return;

        const order = ['bigraph.link.edge', 'bigraph.link.outer', 'bigraph.link.inner'];
        const children = [...group.children]
            .filter(c => c.id !== 'bigraph.link.connect')
            .sort((a, b) => {
                const ia = order.indexOf(a.id);
                const ib = order.indexOf(b.id);
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

    // ── Card builders (Place Graph items) ──────────────────────────────

    private static readonly STATUS_ABBREV: Record<string, string> = {
        ATOMIC: 'AT', ACTIVE: 'AC', PASSIVE: 'PA',
    };
    private static readonly STATUS_LABELS: Record<string, string> = {
        AT: 'Atomic', AC: 'Active', PA: 'Passive',
    };

    private buildPlaceholderHint(item: PaletteItem): HTMLElement {
        const el = document.createElement('div');
        el.className = 'bp-place-hint';
        el.setAttribute('data-label', item.label);
        el.textContent = item.label;
        return el;
    }

    private buildCard(item: PaletteItem): HTMLElement {
        if (item.id === 'bigraph.node.placeholder') {
            return this.buildPlaceholderHint(item);
        }

        const card = document.createElement('div');
        card.className = 'bp-card';
        card.setAttribute('data-label', item.label);

        const parts = item.label.match(/^(.+?)\s*\(arity:\s*(\d+)\)$/);
        const controlName = parts?.[1] ?? item.label;
        const arity = parts?.[2] ?? null;

        // Read status from the action args the server sends
        const rawStatus = (item.actions?.[0] as any)?.args?.status as string | undefined;
        const status = rawStatus ? (BigraphCustomPalette.STATUS_ABBREV[rawStatus] ?? rawStatus) : null;
        const statusLong = status ? (BigraphCustomPalette.STATUS_LABELS[status] ?? status) : '';

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

    private findConnectTool(): PaletteItem | undefined {
        const sorted = [...this.paletteItems].sort(cmpPalette);
        const linkGroup = sorted.find(i => i.children && i.id === 'bigraph-links');
        return linkGroup?.children?.find(item => item.id === 'bigraph.link.connect');
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

    // ── Search filter ──────────────────────────────────────────────────

    private applyFilter(query: string): void {
        const mid = this.root?.querySelector('.bp-body');
        if (!mid) return;
        const lc = query.toLowerCase();
        mid.querySelectorAll<HTMLElement>('[data-label]').forEach(el => {
            const match = !lc || (el.getAttribute('data-label') ?? '').toLowerCase().includes(lc);
            el.classList.toggle('bp-filtered-out', !match);
        });
    }

    // ── Active creation tool tracking ──────────────────────────────────

    private setActiveCreation(el: HTMLElement): void {
        this.clearActiveCreation();
        el.classList.add('bp-active');
        this.activeCreationEl = el;
        this.currentMode = 'creation';
        this.activeModeBtn?.classList.remove('bp-active');
    }

    private clearActiveCreation(): void {
        this.activeCreationEl?.classList.remove('bp-active');
        this.activeCreationEl = null;
    }

    // ── Dispatch helpers ───────────────────────────────────────────────

    private dispatch(action: unknown): void {
        this.dispatcher?.dispatch(action as Parameters<IActionDispatcher['dispatch']>[0]);
    }

    private dispatchAll(actions: unknown[]): void {
        this.dispatcher?.dispatchAll(actions as Parameters<IActionDispatcher['dispatchAll']>[0]);
    }

    private scheduleRefresh(delay = DEBOUNCE_MS): void {
        clearTimeout(this.refreshTimer);
        this.refreshTimer = setTimeout(() => this.refresh(), delay);
    }

    private reapplyDeleteMode(): void {
        if (this.currentMode !== 'delete') return;
        this.dispatch(EnableToolsAction.create([DELETE_TOOL_ID]));
    }
}
