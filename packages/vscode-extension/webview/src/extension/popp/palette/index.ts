import {
    CenterAction,
    EnableDefaultToolsAction,
    SelectAction,
    TriggerEdgeCreationAction,
    TriggerNodeCreationAction
} from '@eclipse-glsp/client';
import { POPP_VARIANT_ID } from '../diagramExtension';
import {
    POPP_CONNECT_ELEMENT_TYPE_ID,
    PoppCoverageMode,
    RequestCoverageReportAction,
    SetInspectionAction,
    CoverageReport,
    CoverageReportAction
} from '../popp-actions';
import { poppToolEvents } from './popp-tool-events';
import type { IVariantPalette, PaletteHostApi, VariantPaletteFactory } from '../../palette-types';
import variantHtml from './popp-variant.html';
import './popp-variant.css';

/** Escape within this window before the tools reset means the user cancelled, so connect must not re-arm. */
const ESCAPE_GRACE_MS = 300;
/** Safety net: two re-arms closer than this means something keeps resetting the tools, so stop. */
const MIN_REARM_INTERVAL_MS = 50;

class PoppVariantPalette implements IVariantPalette {
    private container: HTMLElement | null = null;
    private activeCardEl: HTMLElement | null = null;
    private connectCard: HTMLElement | null = null;
    private inspectCard: HTMLElement | null = null;
    private inspectActive = false;
    private mode: PoppCoverageMode = 'PLANNING';

    private lastEscape = Number.NEGATIVE_INFINITY;
    private lastRearm = Number.NEGATIVE_INFINITY;
    private unsubscribeTools?: () => void;
    private readonly onKeyDown = (e: KeyboardEvent): void => {
        if (e.key === 'Escape') {
            this.lastEscape = performance.now();
        }
    };

    constructor(private readonly host: PaletteHostApi) {}

    mount(container: HTMLElement): void {
        this.container = container;
        container.innerHTML = variantHtml;
        this.bindCardClicks();
        this.bindConnectTool();
        this.bindInspection();

        document.addEventListener('keydown', this.onKeyDown, true);
        this.unsubscribeTools = poppToolEvents.onDefaultToolsEnabled(() => this.onDefaultToolsEnabled());
    }

    refresh(): void { }

    dispose(): void {
        if (this.inspectActive) {
            this.setInspect(false);
        }
        document.removeEventListener('keydown', this.onKeyDown, true);
        this.unsubscribeTools?.();
        this.container = null;
        this.activeCardEl = null;
        this.connectCard = null;
        this.inspectCard = null;
    }

    // -- creation tools -----------------------------------------------------

    private bindCardClicks(): void {
        const root = this.container!;
        Array.from(root.querySelectorAll('.popp-card[data-popp-type]')).forEach(el => {
            const card = el as HTMLElement;
            const elementTypeId = card.dataset.poppType!;
            const nodeType = card.dataset.nodeType!;
            card.onclick = () => this.activateTool(card, () =>
                TriggerNodeCreationAction.create(elementTypeId, { args: { nodeType: nodeType } }));
        });
    }

    private bindConnectTool(): void {
        const card = this.container!.querySelector<HTMLElement>('.popp-card[data-popp-connect]');
        if (!card) {
            return;
        }
        this.connectCard = card;
        card.onclick = () => {
            if (this.activeCardEl === card) {
                // second click on the active connect tool turns it off
                this.clearActiveCard();
                this.host.dispatcher.dispatch(EnableDefaultToolsAction.create());
                return;
            }
            this.activateTool(card, () => TriggerEdgeCreationAction.create(POPP_CONNECT_ELEMENT_TYPE_ID));
        };
    }

    private activateTool(card: HTMLElement, createTrigger: () => TriggerNodeCreationAction | TriggerEdgeCreationAction): void {
        if (this.inspectActive) {
            this.setInspect(false); // creation tools and click-to-inspect would fight over clicks
        }
        this.host.dispatcher.dispatch(createTrigger());
        this.activeCardEl?.classList.remove('bp-active');
        card.classList.add('bp-active');
        this.activeCardEl = card;
        this.host.notifyCreationToolActivated();
    }

    private clearActiveCard(): void {
        this.activeCardEl?.classList.remove('bp-active');
        this.activeCardEl = null;
    }

    /**
     * GLSP falls back to the default tools after every use of a creation tool, and on Escape.
     * Node tools are one-shot, so just drop the highlight. Connect stays armed for chained connections
     * unless the user cancelled.
     */
    private onDefaultToolsEnabled(): void {
        const card = this.activeCardEl;
        if (!card) {
            return;
        }
        const cancelled = performance.now() - this.lastEscape < ESCAPE_GRACE_MS;
        const tooSoon = performance.now() - this.lastRearm < MIN_REARM_INTERVAL_MS;
        if (card === this.connectCard && !cancelled && !tooSoon) {
            this.lastRearm = performance.now();
            // Defer so GLSP has finished switching to the default tools before we enable ours again.
            setTimeout(() => {
                if (this.activeCardEl === this.connectCard) {
                    this.host.dispatcher.dispatch(TriggerEdgeCreationAction.create(POPP_CONNECT_ELEMENT_TYPE_ID));
                }
            }, 0);
            return;
        }
        this.clearActiveCard();
    }

    // -- inspection ---------------------------------------------------------

    private bindInspection(): void {
        const root = this.container!;
        this.inspectCard = root.querySelector<HTMLElement>('.popp-card[data-popp-inspect]');
        if (this.inspectCard) {
            this.inspectCard.onclick = () => this.setInspect(!this.inspectActive);
        }

        const toggle = root.querySelector<HTMLInputElement>('[data-popp-mode-toggle]');
        if (toggle) {
            toggle.onchange = () => {
                this.mode = toggle.checked ? 'VERIFY' : 'PLANNING';
                root.querySelectorAll<HTMLElement>('[data-popp-mode-label]').forEach(label =>
                    label.classList.toggle('active', label.dataset.poppModeLabel === this.mode));
                this.pushInspection();
                void this.loadReport();
            };
        }

        const refresh = root.querySelector<HTMLElement>('.popp-card[data-popp-report-refresh]');
        if (refresh) {
            refresh.onclick = () => void this.loadReport();
        }
    }

    private setInspect(active: boolean): void {
        this.inspectActive = active;
        this.inspectCard?.classList.toggle('bp-active', active);
        if (active) {
            // Drop any armed creation tool first (cleared before dispatching, so connect does not re-arm itself)
            this.clearActiveCard();
            this.host.dispatcher.dispatch(EnableDefaultToolsAction.create());
        }
        this.pushInspection();
    }

    private pushInspection(): void {
        this.host.dispatcher.dispatch(SetInspectionAction.create(this.inspectActive, this.mode));
    }

    private async loadReport(): Promise<void> {
        const response = await this.host.dispatcher.request<CoverageReportAction>(
            RequestCoverageReportAction.create(this.mode));
        this.renderReport(response.report);
    }

    private renderReport(report: CoverageReport): void {
        const target = this.container?.querySelector<HTMLElement>('[data-popp-report]');
        if (!target) {
            return;
        }
        target.replaceChildren();

        // User-written descriptions end up in here, so everything is built with textContent, never innerHTML.
        const heading = (text: string) => {
            const h = document.createElement('div');
            h.className = 'popp-report-heading';
            h.textContent = text;
            target.appendChild(h);
        };
        const row = (text: string, cls: string, onClick?: () => void) => {
            const r = document.createElement('div');
            r.className = `popp-report-row ${cls}`;
            r.textContent = text;
            if (onClick) {
                r.classList.add('popp-report-clickable');
                r.onclick = onClick;
            }
            target.appendChild(r);
        };
        const focus = (ids: string[]) => {
            if (ids.length === 0) {
                return;
            }
            this.host.dispatcher.dispatch(SelectAction.create({ selectedElementsIDs: ids }));
            this.host.dispatcher.dispatch(CenterAction.create(ids, { animate: true, retainZoom: true }));
        };

        heading('Tree coverage (' + report.mode.toLowerCase() + ')');
        if (report.roots.length === 0) {
            row('No coverable trees yet', 'popp-report-muted');
        }
        report.roots.forEach(r => row(
            `${r.kind.toLowerCase()}: ${r.description || r.id} \u2014 ${r.coverage}`,
            `popp-report-${r.coverage.toLowerCase()}`, () => focus([r.id])));

        heading('Links between trees');
        report.layers.forEach(l => {
            const gaps = [...l.fromUnlinked, ...l.toUnlinked];
            row(`${l.label}: ${l.fromLinked}/${l.fromTotal} \u00b7 ${l.toLinked}/${l.toTotal}`,
                gaps.length === 0 ? 'popp-report-covered' : 'popp-report-partial',
                gaps.length > 0 ? () => focus(gaps) : undefined);
        });

        heading('Size');
        Object.entries(report.trees).forEach(([kind, m]) => row(
            `${kind.toLowerCase()}: ${m.nodes} nodes \u00b7 height ${m.height} \u00b7 width ${m.width} \u00b7 max branching ${m.maxBranching}`,
            'popp-report-muted'));
    }
}

export const createPoppPalette: VariantPaletteFactory =
    (host) => new PoppVariantPalette(host);

export { POPP_VARIANT_ID };