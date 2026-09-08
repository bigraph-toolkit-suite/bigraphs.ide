/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

/**
 * Static description of the POPP node types a user may place on the
 * canvas via the palette.
 *
 * <p>Mirrors the control identifiers declared server-side in
 * {@code POPPBigraphSignature} (see
 * {@code packages/server/.../extension/popp/bigraph/POPPBigraphSignature.java}).
 * The Trace* relation controls and the {@code Covered}/{@code Satisfied}
 * state markers are intentionally omitted here — they are derived/managed
 * automatically rather than placed directly by the user. Add an entry
 * here (and keep the id in sync with the server signature) to expose a
 * new POPP element in the palette.</p>
 *
 * <p>{@code AndDecomp}/{@code OrDecomp} are also omitted as separate
 * cards: decomposition type is a per-instance property of a concept
 * node (mirrors the domain model's {@code TreeNode.decompositionType})
 * rather than its own control, so every concept card below exposes an
 * inline None/AND/OR picker instead (see {@link supportsDecomposition}).</p>
 */
export interface PoppControlDescriptor {
    /** Must match a control identifier in {@code POPPBigraphSignature}. */
    readonly controlName: string;
    readonly label: string;
    readonly icon: string;
    readonly description: string;
    /** Whether this node offers the inline AND/OR decomposition picker. */
    readonly supportsDecomposition?: boolean;
}

export type PoppDecompositionType = 'NONE' | 'AND' | 'OR';

export const POPP_CONTROLS: ReadonlyArray<PoppControlDescriptor> = [
    { controlName: 'Problem', label: 'Problem', icon: 'codicon-warning', description: 'A problem to be addressed', supportsDecomposition: true },
    { controlName: 'Goal', label: 'Goal', icon: 'codicon-target', description: 'A goal that resolves a problem', supportsDecomposition: true },
    { controlName: 'Consequence', label: 'Consequence', icon: 'codicon-arrow-right', description: 'A consequence of a goal or solution', supportsDecomposition: true },
    { controlName: 'Solution', label: 'Solution', icon: 'codicon-lightbulb', description: 'A solution to a goal', supportsDecomposition: true },
    { controlName: 'SuccessCriteria', label: 'Success Criteria', icon: 'codicon-checklist', description: 'Criteria a solution must satisfy', supportsDecomposition: true },
    { controlName: 'SuccessProof', label: 'Success Proof', icon: 'codicon-verified', description: 'Evidence that success criteria are met', supportsDecomposition: true },
];
