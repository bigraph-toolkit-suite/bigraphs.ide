/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { Action } from '@eclipse-glsp/client';

/**
 * KIND constant for the client-to-server action that asks the server
 * to switch the active diagram variant (e.g. from {@code "behavior-tree"}
 * to {@code "bigraph"}) for the currently loaded file.
 *
 * Kept in lockstep with {@code RequestVariantSwitchAction.KIND} on the
 * server.
 */
export const REQUEST_VARIANT_SWITCH_KIND = 'bigraph.requestVariantSwitch';

/**
 * Wire-level shape of the action dispatched by the in-canvas variant
 * tab bar when the user clicks on the inactive tab. The server
 * validates the request, rebuilds the GModel for the target variant,
 * and broadcasts a {@code SetActiveVariantAction} so the palette and
 * tab-bar can stay in sync.
 */
export interface RequestVariantSwitchAction extends Action {
    kind: typeof REQUEST_VARIANT_SWITCH_KIND;
    variantId: string;
}

export namespace RequestVariantSwitchAction {
    export const KIND = REQUEST_VARIANT_SWITCH_KIND;

    export function create(variantId: string): RequestVariantSwitchAction {
        return { kind: REQUEST_VARIANT_SWITCH_KIND, variantId };
    }

    export function is(action: Action): action is RequestVariantSwitchAction {
        return action.kind === REQUEST_VARIANT_SWITCH_KIND;
    }
}
