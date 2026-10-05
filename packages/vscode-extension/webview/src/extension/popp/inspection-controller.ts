import {
    Action,
    GModelElement,
    GNode,
    IActionDispatcher,
    IActionHandler,
    IFeedbackActionDispatcher,
    ModifyCSSFeedbackAction,
    MouseListener,
    SetModelAction,
    StatusAction,
    TYPES,
    UpdateModelAction,
    findParent
} from '@eclipse-glsp/client';
import { inject, injectable } from 'inversify';
import {
    InspectionResult,
    InspectionResultAction,
    PoppCoverageMode,
    RequestInspectionAction,
    SetInspectionAction
} from './popp-actions';

const NODE_TYPES = new Set([
    'popp:problem', 'popp:goal', 'popp:consequence', 'popp:solution', 'popp:success_criteria', 'popp:success_proof'
]);

const CLS_INSPECTING = 'popp-inspecting';
const CLS_PATH = 'popp-inspect-path';
const CLS_ROOT = 'popp-inspect-root';
const CLS_BY_COVERAGE = {
    COVERED: 'popp-inspect-covered',
    PARTIAL: 'popp-inspect-partial',
    UNCOVERED: 'popp-inspect-uncovered'
} as const;
const ALL_CLASSES = [CLS_INSPECTING, CLS_PATH, CLS_ROOT, ...Object.values(CLS_BY_COVERAGE)];

/**
 * Click-to-inspect: while active, clicking a POPP node asks the server why it is (not) covered and highlights
 * the evidence via CSS-class feedback (no model change). Highlights follow model updates by re-requesting.
 */
@injectable()
export class InspectionController extends MouseListener implements IActionHandler {
    @inject(TYPES.IActionDispatcher) protected readonly dispatcher!: IActionDispatcher;
    @inject(TYPES.IFeedbackActionDispatcher) protected readonly feedback!: IFeedbackActionDispatcher;

    protected active = false;
    protected mode: PoppCoverageMode = 'PLANNING';
    protected inspectedNodeId?: string;
    protected rootId?: string;
    protected highlighted: string[] = [];

    handle(action: Action): void {
        if (action.kind === SetInspectionAction.KIND) {
            const a = action as SetInspectionAction;
            this.active = a.active;
            this.mode = a.mode;
            if (!this.active) {
                this.clear();
            } else if (this.inspectedNodeId) {
                void this.inspect(this.inspectedNodeId);
            }
        } else if (action.kind === UpdateModelAction.KIND || action.kind === SetModelAction.KIND) {
            if (this.active && this.inspectedNodeId) {
                void this.inspect(this.inspectedNodeId);
            }
        }
    }

    override mouseDown(target: GModelElement, event: MouseEvent): Action[] {
        if (!this.active || event.button !== 0) {
            return [];
        }
        this.rootId = target.root.id;
        const node = findParent(target, e => e instanceof GNode && NODE_TYPES.has(e.type));
        if (node) {
            void this.inspect(node.id);
        } else {
            this.clear();
        }
        return [];
    }

    protected async inspect(nodeId: string): Promise<void> {
        this.inspectedNodeId = nodeId;
        const response = await this.dispatcher.request<InspectionResultAction>(RequestInspectionAction.create(nodeId, this.mode));
        if (this.inspectedNodeId !== nodeId || !this.active) {
            return; // superseded while the request was in flight
        }
        if (response.result.coverage === 'UNKNOWN') {
            this.clear();
            return;
        }
        this.show(response.result);
    }

    protected show(result: InspectionResult): void {
        this.removeHighlights();
        const rootId = this.rootId;
        if (!rootId) {
            return;
        }

        const byCoverage: Record<string, string[]> = { COVERED: [], PARTIAL: [], UNCOVERED: [] };
        Object.entries(result.nodeCoverage).forEach(([id, cov]) => byCoverage[cov]?.push(id));

        this.highlighted = [rootId, ...result.pathNodeIds, ...result.pathEdgeIds];
        const actions: Action[] = [
            ModifyCSSFeedbackAction.create({ elements: [rootId], add: [CLS_INSPECTING] }),
            ModifyCSSFeedbackAction.create({ elements: [...result.pathNodeIds, ...result.pathEdgeIds], add: [CLS_PATH] }),
            ModifyCSSFeedbackAction.create({ elements: [result.nodeId], add: [CLS_ROOT] })
        ];
        (Object.keys(CLS_BY_COVERAGE) as (keyof typeof CLS_BY_COVERAGE)[]).forEach(cov => {
            if (byCoverage[cov].length > 0) {
                actions.push(ModifyCSSFeedbackAction.create({ elements: byCoverage[cov], add: [CLS_BY_COVERAGE[cov]] }));
            }
        });
        this.feedback.registerFeedback(this, actions);

        const gap = result.gaps[0];
        const more = result.gaps.length > 1 ? ` (+${result.gaps.length - 1} more)` : '';
        const text = `${result.label || result.nodeId}: ${result.coverage} (${result.mode.toLowerCase()})`
            + (gap ? ` \u2014 ${gap.message}${more}` : '');
        this.dispatcher.dispatch(StatusAction.create(text, { severity: result.coverage === 'COVERED' ? 'INFO' : 'WARNING' }));
    }

    protected removeHighlights(): void {
        if (this.highlighted.length > 0) {
            this.feedback.deregisterFeedback(this, [
                ModifyCSSFeedbackAction.create({ elements: this.highlighted, remove: ALL_CLASSES })
            ]);
            this.highlighted = [];
        }
    }

    protected clear(): void {
        this.inspectedNodeId = undefined;
        this.removeHighlights();
        this.dispatcher.dispatch(StatusAction.create('', { severity: 'NONE' }));
    }
}