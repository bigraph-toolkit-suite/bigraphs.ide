import type { Action, Operation, RequestAction, ResponseAction } from '@eclipse-glsp/client';

/** Virtual edge type backing the palette's universal "Connect" tool (decomposition + relations). */
export const POPP_CONNECT_ELEMENT_TYPE_ID = 'popp:connect';

export type PoppDecompositionType = 'AND' | 'OR';
/** Names of the server's {@code CoverageMode} presets; keep in sync with {@code CoverageMode.presets()}. */
export type PoppCoverageMode = 'PLANNING' | 'VERIFY' | 'GOALS_BY_CRITERIA' | 'PROBLEMS_BY_GOALS' | 'PROBLEMS_BY_PROOFS';

export interface PoppCoverageModeInfo {
    id: PoppCoverageMode;
    /** Short name for the dropdowns. */
    label: string;
    /** What counts as covered by itself, for the tooltip. */
    description: string;
}

/** The coverage modes in display order: one per question of the task, then the two about the whole model. */
export const POPP_COVERAGE_MODES: readonly PoppCoverageModeInfo[] = [
    {
        id: 'PROBLEMS_BY_GOALS',
        label: 'Problems by goals',
        description: 'Is the problem tree covered by the goal tree? Every goal counts.'
    },
    {
        id: 'PROBLEMS_BY_PROOFS',
        label: 'Problems by proofs',
        description: 'Is the problem tree covered by the success proof tree? Every proof counts, through criteria and goals.'
    },
    {
        id: 'GOALS_BY_CRITERIA',
        label: 'Goals by criteria',
        description: 'Is the goal tree covered by the success criteria tree? Every criterion counts.'
    },
    {
        id: 'PLANNING',
        label: 'Planning',
        description: 'Whole model; every success criterion counts, even if no proof validates it yet.'
    },
    {
        id: 'VERIFY',
        label: 'Verify',
        description: 'Whole model; a success criterion only counts once a proof that a solution produces validates it.'
    }
];

export function coverageModeLabel(mode: string): string {
    return POPP_COVERAGE_MODES.find(m => m.id === mode)?.label ?? mode;
}
export type PoppCoverage = 'COVERED' | 'PARTIAL' | 'UNCOVERED';

// ---------------------------------------------------------------------------
// Operations (mutate the model)
// ---------------------------------------------------------------------------
const SWITCH_DECOMPOSITION_TYPE_KIND = 'popp.switchDecompositionType';

/** Mirrors the server's {@code SwitchDecompositionTypeOperation}, no generated client-side action class exists for it. */
export interface SwitchDecompositionTypeOperation extends Operation {
    kind: typeof SWITCH_DECOMPOSITION_TYPE_KIND;
    nodeId: string;
    newType: PoppDecompositionType;
}

export function createSwitchDecompositionTypeOperation(
    nodeId: string,
    newType: PoppDecompositionType
): SwitchDecompositionTypeOperation {
    return { kind: SWITCH_DECOMPOSITION_TYPE_KIND, isOperation: true, nodeId, newType };
}

// ---------------------------------------------------------------------------
// Inspection (read-only requests + client-only toggle)
// ---------------------------------------------------------------------------
export interface InspectionResult {
    nodeId: string;
    label: string;
    coverage: PoppCoverage | 'UNKNOWN';
    mode: PoppCoverageMode;
    pathNodeIds: string[];
    pathEdgeIds: string[];
    nodeCoverage: Record<string, PoppCoverage>;
    gaps: { nodeId: string; message: string }[];
}

export interface RequestInspectionAction extends RequestAction<InspectionResultAction> {
    kind: 'popp.requestInspection';
    nodeId: string;
    mode: PoppCoverageMode;
}
export const RequestInspectionAction = {
    KIND: 'popp.requestInspection' as const,
    create(nodeId: string, mode: PoppCoverageMode): RequestInspectionAction {
        return { kind: 'popp.requestInspection', requestId: '', nodeId, mode };
    }
};

export interface InspectionResultAction extends ResponseAction {
    kind: 'popp.inspectionResult';
    result: InspectionResult;
}

/** Mirrors the server's {@code CoverageReportBuilder.ModeSummary}: how well a mode's trees are covered in it. */
export interface CoverageModeSummary {
    mode: PoppCoverageMode;
    /** The kind of tree the mode asks about; absent for the modes about the whole model. */
    kind?: string | null;
    /** Coverage of those trees' roots (the lowest); absent without any such tree. */
    coverage?: PoppCoverage | null;
    counts: Record<PoppCoverage, number>;
}

/** Mirrors the server's {@code CoverageReportBuilder.CoverageReport}. */
export interface CoverageReport {
    mode: PoppCoverageMode;
    /** One per mode, in {@link POPP_COVERAGE_MODES} order, independent of {@link mode}. */
    modes: CoverageModeSummary[];
    /** The roots in {@link mode}. */
    roots: { id: string; kind: string; description: string; coverage: PoppCoverage }[];
    trees: Record<string, { roots: number; nodes: number; height: number; width: number }>;
}

export interface RequestCoverageReportAction extends RequestAction<CoverageReportAction> {
    kind: 'popp.requestCoverageReport';
    mode: PoppCoverageMode;
}
export const RequestCoverageReportAction = {
    KIND: 'popp.requestCoverageReport' as const,
    create(mode: PoppCoverageMode): RequestCoverageReportAction {
        return { kind: 'popp.requestCoverageReport', requestId: '', mode };
    }
};

export interface CoverageReportAction extends ResponseAction {
    kind: 'popp.coverageReport';
    report: CoverageReport;
}

/** Client-only: palette -> inspection controller. */
export interface SetInspectionAction extends Action {
    kind: 'popp.setInspection';
    active: boolean;
    mode: PoppCoverageMode;
}
export const SetInspectionAction = {
    KIND: 'popp.setInspection' as const,
    create(active: boolean, mode: PoppCoverageMode): SetInspectionAction {
        return { kind: 'popp.setInspection', active, mode };
    }
};
// ---------------------------------------------------------------------------
// Coverage gaps (read-only request)
// ---------------------------------------------------------------------------
export interface CoverageGap {
    nodeId: string;
    label: string;
    coverage: Exclude<PoppCoverage, 'COVERED'>;
    /** Nothing beneath or behind the node could still contribute: this is where to act. */
    rootCause: boolean;
    /** What is missing, root causes only, e.g. "needs a success criteria (causes)". */
    message: string;
}

export interface RequestCoverageGapsAction extends RequestAction<CoverageGapsAction> {
    kind: 'popp.requestCoverageGaps';
    mode: PoppCoverageMode;
}
export const RequestCoverageGapsAction = {
    KIND: 'popp.requestCoverageGaps' as const,
    create(mode: PoppCoverageMode): RequestCoverageGapsAction {
        return { kind: 'popp.requestCoverageGaps', requestId: '', mode };
    }
};

export interface CoverageGapsAction extends ResponseAction {
    kind: 'popp.coverageGaps';
    mode: PoppCoverageMode;
    gaps: CoverageGap[];
}

// ---------------------------------------------------------------------------
// Validation (asynchronous: the server pushes a result after every structural change)
// ---------------------------------------------------------------------------
export type PoppSeverity = 'ERROR' | 'WARNING';

export interface ValidationIssue {
    code: string;
    severity: PoppSeverity;
    message: string;
    /** POPP node ids (= GModel ids) to highlight; may be empty. */
    nodeIds: string[];
    /** Names of the offending bigraph nodes. */
    elements: string[];
    pattern: string;
}

export interface ValidationReport {
    modelVersion: number;
    valid: boolean;
    errors: number;
    warnings: number;
    issues: ValidationIssue[];
    durationMs: number;
    patternsMatched: number;
    patternsSkipped: number;
}

/** Asks the server for a validation run; the result arrives later as a {@link ValidationResultAction}. */
export interface ValidateModelAction extends Action {
    kind: 'popp.validateModel';
}
export const ValidateModelAction = {
    KIND: 'popp.validateModel' as const,
    create(): ValidateModelAction {
        return { kind: 'popp.validateModel' };
    }
};

/** Pushed by the server; registering a handler for this kind is what makes the server forward it. */
export interface ValidationResultAction extends Action {
    kind: 'popp.validationResult';
    report: ValidationReport;
}
export const ValidationResultAction = {
    KIND: 'popp.validationResult' as const
};

// ---------------------------------------------------------------------------
// Description editing right after creation (server -> client)
// ---------------------------------------------------------------------------
/** Sent by the server after the update that contains a newly created node: open its description editor. */
export interface StartDescriptionEditAction extends Action {
    kind: 'popp.startDescriptionEdit';
    labelId: string;
}
export const StartDescriptionEditAction = {
    KIND: 'popp.startDescriptionEdit' as const
};
