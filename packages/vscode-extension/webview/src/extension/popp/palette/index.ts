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
    POPP_COVERAGE_MODES,
    PoppCoverageMode,
    SetInspectionAction,
    ValidationReport
} from '../popp-actions';
import { PoppViewState, poppStore } from '../popp-store';
import { poppToolEvents } from './popp-tool-events';
import { applyStoredKindColoring, setKindColoring } from './popp-kind-colors';
import { acquireVsCodeApi, appendPaletteExportSection } from '../../palette-export';
import { getPaletteExportsForVariant } from '../../extensions';
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
    private gapsCard: HTMLElement | null = null;
    private inspectActive = false;
    private unsubscribeStore?: () => void;

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
        this.bindValidity();
        this.bindKindColors();
        appendPaletteExportSection(container, getPaletteExportsForVariant(POPP_VARIANT_ID, this.host), this.host);

        document.addEventListener('keydown', this.onKeyDown, true);
        this.unsubscribeTools = poppToolEvents.onDefaultToolsEnabled(() => this.onDefaultToolsEnabled());
        this.unsubscribeStore = poppStore.subscribe((state, changed) => this.onStateChanged(state, changed));
        this.onStateChanged(poppStore.state, new Set(['mode', 'validation', 'gapsActive']));
        poppStore.update({ active: true }); // starts validation and the report/gap refresh
    }

    refresh(): void { }

    dispose(): void {
        if (this.inspectActive) {
            this.setInspect(false);
        }
        document.removeEventListener('keydown', this.onKeyDown, true);
        this.unsubscribeTools?.();
        this.unsubscribeStore?.();
        poppStore.update({ active: false, gapsActive: false });
        this.container = null;
        this.activeCardEl = null;
        this.connectCard = null;
        this.inspectCard = null;
        this.gapsCard = null;
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

    // -- inspection, uncovered, report ---------------------------------------

    private bindInspection(): void {
        const root = this.container!;
        this.inspectCard = root.querySelector<HTMLElement>('.popp-card[data-popp-inspect]');
        if (this.inspectCard) {
            this.inspectCard.onclick = () => this.setInspect(!this.inspectActive);
        }

        this.gapsCard = root.querySelector<HTMLElement>('.popp-card[data-popp-gaps]');
        if (this.gapsCard) {
            this.gapsCard.onclick = () => {
                const active = !poppStore.state.gapsActive;
                if (active && this.inspectActive) {
                    this.setInspect(false); // both recolour nodes; one overlay at a time
                }
                poppStore.update({ gapsActive: active });
            };
        }

        const select = root.querySelector<HTMLSelectElement>('[data-popp-mode-select]');
        if (select) {
            select.replaceChildren(...POPP_COVERAGE_MODES.map(m => {
                const option = new Option(m.label, m.id);
                option.title = m.description;
                return option;
            }));
            select.onchange = () => poppStore.update({ mode: select.value as PoppCoverageMode });
        }

        const report = root.querySelector<HTMLElement>('.popp-card[data-popp-report-open]');
        if (report) {
            report.onclick = () => this.openSidebar();
        }
    }

    private setInspect(active: boolean): void {
        this.inspectActive = active;
        this.inspectCard?.classList.toggle('bp-active', active);
        if (active) {
            // Drop any armed creation tool first (cleared before dispatching, so connect does not re-arm itself)
            this.clearActiveCard();
            this.host.dispatcher.dispatch(EnableDefaultToolsAction.create());
            poppStore.update({ gapsActive: false });
        }
        this.pushInspection();
    }

    private pushInspection(): void {
        this.host.dispatcher.dispatch(SetInspectionAction.create(this.inspectActive, poppStore.state.mode));
    }

    private openSidebar(): void {
        acquireVsCodeApi()?.postMessage({ type: 'popp.openSidebar' });
    }

    private onStateChanged(state: Readonly<PoppViewState>, changed: ReadonlySet<keyof PoppViewState>): void {
        const root = this.container;
        if (!root) {
            return;
        }
        if (changed.has('mode')) {
            const select = root.querySelector<HTMLSelectElement>('[data-popp-mode-select]');
            if (select) select.value = state.mode;
            // a native tooltip: the palette sections clip anything positioned outside them
            if (select) select.title = POPP_COVERAGE_MODES.find(m => m.id === state.mode)?.description ?? '';
            this.pushInspection();
        }
        if (changed.has('gapsActive')) {
            this.gapsCard?.classList.toggle('bp-active', state.gapsActive);
        }
        if (changed.has('validation')) {
            this.renderValidity(state.validation);
        }
    }

    // -- display ----------------------------------------------------------------

    private bindKindColors(): void {
        const toggle = this.container!.querySelector<HTMLInputElement>('[data-popp-kind-colors]');
        const enabled = applyStoredKindColoring();
        if (toggle) {
            toggle.checked = enabled;
            toggle.onchange = () => setKindColoring(toggle.checked);
        }
    }

    // -- validation status ----------------------------------------------------

    private bindValidity(): void {
        const button = this.container!.querySelector<HTMLElement>('[data-popp-validity]');
        if (!button) {
            return;
        }
        button.onclick = () => {
            const issue = poppStore.state.validation?.issues.find(i => i.nodeIds.length > 0);
            if (issue) {
                this.host.dispatcher.dispatch(SelectAction.create({ selectedElementsIDs: issue.nodeIds, deselectedElementsIDs: true }));
                this.host.dispatcher.dispatch(CenterAction.create(issue.nodeIds, { animate: true, retainZoom: true }));
            }
            this.openSidebar();
        };
    }

    private renderValidity(report: ValidationReport | undefined): void {
        const button = this.container?.querySelector<HTMLElement>('[data-popp-validity]');
        const icon = this.container?.querySelector<HTMLElement>('[data-popp-validity-icon]');
        const text = this.container?.querySelector<HTMLElement>('[data-popp-validity-text]');
        if (!button || !icon || !text) {
            return;
        }
        button.classList.remove('popp-validity-pending', 'popp-validity-valid', 'popp-validity-warning', 'popp-validity-invalid');
        if (!report) {
            button.classList.add('popp-validity-pending');
            icon.className = 'codicon codicon-loading codicon-modifier-spin';
            text.textContent = 'Validating\u2026';
            return;
        }
        const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;
        if (!report.valid) {
            button.classList.add('popp-validity-invalid');
            icon.className = 'codicon codicon-error';
            text.textContent = 'Invalid: ' + plural(report.errors, 'error')
                + (report.warnings > 0 ? ', ' + plural(report.warnings, 'warning') : '');
        } else if (report.warnings > 0) {
            button.classList.add('popp-validity-warning');
            icon.className = 'codicon codicon-warning';
            text.textContent = 'Valid, ' + plural(report.warnings, 'warning');
        } else {
            button.classList.add('popp-validity-valid');
            icon.className = 'codicon codicon-pass';
            text.textContent = 'Model is valid';
        }
        // Issue texts contain user-written descriptions: title is plain text, never HTML
        button.title = report.issues.length === 0
            ? `No structural problems (${report.patternsMatched} patterns checked, ${report.durationMs} ms)`
            : report.issues.slice(0, 6).map(i => `${i.severity === 'ERROR' ? '\u2716' : '\u26a0'} ${i.message}`).join('\n')
                + (report.issues.length > 6 ? `\n\u2026 ${report.issues.length - 6} more (open the report)` : '');
    }
}

export const createPoppPalette: VariantPaletteFactory =
    (host) => new PoppVariantPalette(host);

export { POPP_VARIANT_ID };