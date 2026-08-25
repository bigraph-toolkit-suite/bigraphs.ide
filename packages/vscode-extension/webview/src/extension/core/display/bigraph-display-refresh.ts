/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { inject, injectable } from 'inversify';

import { BigraphDisplaySettings } from './bigraph-display-settings';

export const BIGRAPH_DISPLAY_SETTINGS_SET = 'bigraph-display-settings-set';

export interface BigraphDisplaySettingsSetDetail {
    showRoots: boolean;
    showNodeNames: boolean;
    showPlaceGraphArrows: boolean;
}

/**
 * Applies palette display toggles (CSS classes on {@code document.body}).
 */
@injectable()
export class BigraphDisplayRefreshBridge {
    constructor(@inject(BigraphDisplaySettings) private readonly settings: BigraphDisplaySettings) {
        window.addEventListener(BIGRAPH_DISPLAY_SETTINGS_SET, (event: Event) => {
            const detail = (event as CustomEvent<BigraphDisplaySettingsSetDetail>).detail;
            if (!detail) {
                return;
            }
            this.settings.showRoots = detail.showRoots;
            this.settings.showNodeNames = detail.showNodeNames;
            this.settings.showPlaceGraphArrows = detail.showPlaceGraphArrows;
            this.settings.applyToDocument();
        });
        this.settings.applyToDocument();
    }
}
