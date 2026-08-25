/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { Action } from '@eclipse-glsp/client';

/**
 * KIND constant for the server-to-client action that announces the
 * currently active model variant for this diagram session (e.g.
 * {@code "bigraph"} or {@code "behavior-tree"}).
 *
 * Kept in lockstep with {@code SetActiveVariantAction.KIND} on the
 * server. Centralising the literal here prevents drift between
 * dispatcher, handler, and any feature module that filters on it.
 */
export const SET_ACTIVE_VARIANT_KIND = 'bigraph.setActiveVariant';

/**
 * Wire-level shape of the action emitted by
 * {@code BigraphXMIModelStorage} after a fresh model load and on any
 * later variant switch.
 *
 * <ul>
 *   <li>{@code variantId} — currently active variant
 *       (canonical {@code ModelVariant.id} from the owning extension).</li>
 *   <li>{@code availableVariantIds} — variants this file can be viewed
 *       as. Always contains the canonical bigraph variant; UI bits like
 *       the in-canvas tab bar use {@code length > 1} to decide whether
 *       to render at all.</li>
 * </ul>
 */
export interface SetActiveVariantAction extends Action {
    kind: typeof SET_ACTIVE_VARIANT_KIND;
    variantId: string;
    availableVariantIds?: readonly string[];
}

export namespace SetActiveVariantAction {
    export const KIND = SET_ACTIVE_VARIANT_KIND;

    export function is(action: Action): action is SetActiveVariantAction {
        return action.kind === SET_ACTIVE_VARIANT_KIND;
    }
}
