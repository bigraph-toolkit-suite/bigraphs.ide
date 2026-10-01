/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import type { Operation } from '@eclipse-glsp/client';

/** Virtual edge type backing the palette's universal "Connect" tool (decomposition + relations). */
export const POPP_CONNECT_ELEMENT_TYPE_ID = 'popp:connect';

const SWITCH_DECOMPOSITION_TYPE_KIND = 'popp.switchDecompositionType';

export type PoppDecompositionType = 'AND' | 'OR';

/** Mirrors the server's {@code SwitchDecompositionTypeOperation} — no generated client-side action class exists for it. */
export interface SwitchDecompositionTypeOperation extends Operation {
    kind: typeof SWITCH_DECOMPOSITION_TYPE_KIND;
    nodeId: string;
    newType: PoppDecompositionType;
}

export function createSwitchDecompositionTypeOperation(
    nodeId: string,
    newType: PoppDecompositionType
): SwitchDecompositionTypeOperation {
    return {
        kind: SWITCH_DECOMPOSITION_TYPE_KIND,
        isOperation: true,
        nodeId,
        newType
    };
}
