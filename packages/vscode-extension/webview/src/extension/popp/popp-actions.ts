import type { Action, Operation, RequestAction, ResponseAction } from '@eclipse-glsp/client';

/** Virtual edge type backing the palette's universal "Connect" tool (decomposition + relations). */
export const POPP_CONNECT_ELEMENT_TYPE_ID = 'popp:connect';

export type PoppDecompositionType = 'AND' | 'OR';
export type PoppCoverageMode = 'PLANNING' | 'VERIFY';
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

export interface TraceabilityReport {
    mode: PoppCoverageMode;
    roots: { id: string; kind: string; description: string; coverage: PoppCoverage }[];
    coverageByKind: Record<string, Partial<Record<PoppCoverage, number>>>;
    layers: {
        label: string;
        relationType: string;
        fromKind: string;
        toKind: string;
        fromTotal: number;
        fromLinked: number;
        toTotal: number;
        toLinked: number;
        fromUnlinked: string[];
        toUnlinked: string[];
    }[];
    trees: Record<string, { roots: number; nodes: number; height: number; width: number; maxBranching: number }>;
}

export interface RequestTraceabilityReportAction extends RequestAction<TraceabilityReportAction> {
    kind: 'popp.requestTraceabilityReport';
    mode: PoppCoverageMode;
}
export const RequestTraceabilityReportAction = {
    KIND: 'popp.requestTraceabilityReport' as const,
    create(mode: PoppCoverageMode): RequestTraceabilityReportAction {
        return { kind: 'popp.requestTraceabilityReport', requestId: '', mode };
    }
};

export interface TraceabilityReportAction extends ResponseAction {
    kind: 'popp.traceabilityReport';
    report: TraceabilityReport;
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