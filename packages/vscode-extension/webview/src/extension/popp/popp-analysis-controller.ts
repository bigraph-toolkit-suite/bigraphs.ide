import {
    Action,
    CenterAction,
    IActionDispatcher,
    IActionHandler,
    IFeedbackActionDispatcher,
    ModifyCSSFeedbackAction,
    SelectAction,
    SetModelAction,
    TYPES,
    UpdateModelAction
} from '@eclipse-glsp/client';
import { inject, injectable, postConstruct } from 'inversify';
import { acquireVsCodeApi } from '../palette-export';
import {
    CoverageGapsAction,
    CoverageReportAction,
    POPP_COVERAGE_MODES,
    PoppCoverageMode,
    RequestCoverageGapsAction,
    RequestCoverageReportAction,
    ValidateModelAction,
    ValidationResultAction
} from './popp-actions';
import { PoppViewState, poppStore } from './popp-store';

/** Model updates within this window are refreshed once. */
const REFRESH_DEBOUNCE_MS = 250;

const CLS_GAPS_ACTIVE = 'popp-gaps-active';
const CLS_GAP = 'popp-gap';
const CLS_GAP_ROOT = 'popp-gap-root';
const CLS_GAP_PARTIAL = 'popp-gap-partial';
const ALL_GAP_CLASSES = [CLS_GAPS_ACTIVE, CLS_GAP, CLS_GAP_ROOT, CLS_GAP_PARTIAL];

/** Messages from the extension host, sent on behalf of the POPP sidebar view. */
interface SidebarCommand {
    type: 'popp.sidebarCommand';
    command: 'refresh' | 'setMode' | 'setGaps' | 'focus' | 'requestState';
    mode?: PoppCoverageMode;
    active?: boolean;
    ids?: string[];
}

/**
 * Keeps the analysis results in {@link poppStore} current and mirrors them where they are shown:
 * <ul>
 *   <li>stores the validation results the server pushes;</li>
 *   <li>re-requests the coverage report and gaps after model changes and mode switches (debounced);</li>
 *   <li>highlights uncovered nodes via CSS-class feedback while "highlight uncovered" is on (no model change);</li>
 *   <li>relays the state to the extension host for the sidebar view and executes the sidebar's commands.</li>
 * </ul>
 */
@injectable()
export class PoppAnalysisController implements IActionHandler {
    @inject(TYPES.IActionDispatcher) protected readonly dispatcher!: IActionDispatcher;
    @inject(TYPES.IFeedbackActionDispatcher) protected readonly feedback!: IFeedbackActionDispatcher;

    protected readonly vscode = acquireVsCodeApi();
    protected rootId?: string;
    protected refreshTimer?: ReturnType<typeof setTimeout>;
    protected refreshRun = 0;
    protected highlighted: string[] = [];
    protected postScheduled = false;

    @postConstruct()
    protected init(): void {
        poppStore.subscribe((state, changed) => this.onStateChanged(state, changed));
        window.addEventListener('message', (event: MessageEvent) => {
            const msg = event.data as SidebarCommand | undefined;
            if (msg?.type === 'popp.sidebarCommand') {
                this.onSidebarCommand(msg);
            }
        });
    }

    handle(action: Action): void {
        if (action.kind === ValidationResultAction.KIND) {
            poppStore.update({ validation: (action as ValidationResultAction).report });
        } else if (action.kind === SetModelAction.KIND || action.kind === UpdateModelAction.KIND) {
            this.rootId = (action as SetModelAction | UpdateModelAction).newRoot?.id ?? this.rootId;
            this.scheduleRefresh();
        }
    }

    protected onStateChanged(state: Readonly<PoppViewState>, changed: ReadonlySet<keyof PoppViewState>): void {
        if (changed.has('active') && state.active) {
            this.dispatcher.dispatch(ValidateModelAction.create());
            this.scheduleRefresh(0);
        }
        if (changed.has('active') && !state.active) {
            this.removeHighlights();
        }
        if (changed.has('mode')) {
            this.scheduleRefresh(0);
        }
        if (changed.has('gapsActive') || changed.has('gaps')) {
            this.applyHighlights();
        }
        this.postToHost();
    }

    protected onSidebarCommand(msg: SidebarCommand): void {
        switch (msg.command) {
            case 'refresh':
                this.dispatcher.dispatch(ValidateModelAction.create());
                this.scheduleRefresh(0);
                break;
            case 'setMode':
                if (POPP_COVERAGE_MODES.some(m => m.id === msg.mode)) poppStore.update({ mode: msg.mode });
                break;
            case 'setGaps':
                poppStore.update({ gapsActive: msg.active ?? !poppStore.state.gapsActive });
                break;
            case 'focus':
                this.focus(msg.ids ?? []);
                break;
            case 'requestState':
                this.postToHost();
                break;
        }
    }

    focus(ids: string[]): void {
        if (ids.length === 0) {
            return;
        }
        this.dispatcher.dispatch(SelectAction.create({ selectedElementsIDs: ids, deselectedElementsIDs: true }));
        this.dispatcher.dispatch(CenterAction.create(ids, { animate: true, retainZoom: true }));
    }

    // -- report + gaps ------------------------------------------------------

    protected scheduleRefresh(delay = REFRESH_DEBOUNCE_MS): void {
        if (this.refreshTimer) {
            clearTimeout(this.refreshTimer);
        }
        this.refreshTimer = setTimeout(() => void this.refresh(), delay);
    }

    protected async refresh(): Promise<void> {
        if (!poppStore.state.active) {
            return;
        }
        const run = ++this.refreshRun;
        const mode = poppStore.state.mode;
        try {
            const [report, gaps] = await Promise.all([
                this.dispatcher.request<CoverageReportAction>(RequestCoverageReportAction.create(mode)),
                this.dispatcher.request<CoverageGapsAction>(RequestCoverageGapsAction.create(mode))
            ]);
            if (run !== this.refreshRun || mode !== poppStore.state.mode) {
                return; // superseded while in flight
            }
            poppStore.update({ report: report.report, gaps: gaps.gaps });
        } catch (err) {
            console.warn('[POPP] coverage refresh failed', err);
        }
    }

    // -- highlight uncovered -------------------------------------------------

    protected applyHighlights(): void {
        this.removeHighlights();
        const { gapsActive, gaps, active } = poppStore.state;
        if (!active || !gapsActive || !gaps) {
            return;
        }
        const all = gaps.map(g => g.nodeId);
        const roots = gaps.filter(g => g.rootCause).map(g => g.nodeId);
        const partial = gaps.filter(g => g.coverage === 'PARTIAL').map(g => g.nodeId);
        const actions: Action[] = [];
        if (this.rootId) actions.push(ModifyCSSFeedbackAction.create({ elements: [this.rootId], add: [CLS_GAPS_ACTIVE] }));
        if (all.length > 0) actions.push(ModifyCSSFeedbackAction.create({ elements: all, add: [CLS_GAP] }));
        if (roots.length > 0) actions.push(ModifyCSSFeedbackAction.create({ elements: roots, add: [CLS_GAP_ROOT] }));
        if (partial.length > 0) actions.push(ModifyCSSFeedbackAction.create({ elements: partial, add: [CLS_GAP_PARTIAL] }));
        this.highlighted = [...(this.rootId ? [this.rootId] : []), ...all];
        this.feedback.registerFeedback(this, actions);
    }

    protected removeHighlights(): void {
        if (this.highlighted.length > 0) {
            this.feedback.deregisterFeedback(this, [
                ModifyCSSFeedbackAction.create({ elements: this.highlighted, remove: ALL_GAP_CLASSES })
            ]);
            this.highlighted = [];
        }
    }

    // -- sidebar relay -------------------------------------------------------

    /** Coalesces the updates of one tick into one message. */
    protected postToHost(): void {
        if (!this.vscode || this.postScheduled) {
            return;
        }
        this.postScheduled = true;
        queueMicrotask(() => {
            this.postScheduled = false;
            const { active, mode, validation, report, gaps, gapsActive } = poppStore.state;
            this.vscode?.postMessage({ type: 'popp.sidebarState', state: { active, mode, validation, report, gaps, gapsActive } });
        });
    }
}
