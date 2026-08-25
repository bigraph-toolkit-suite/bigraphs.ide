/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { injectable } from 'inversify';

export interface BigraphDisplaySettingsChangedDetail {
    nodeNamesChanged?: boolean;
}

/**
 * Client-only bigraph diagram display toggles (palette checkboxes).
 */
@injectable()
export class BigraphDisplaySettings {
    showRoots = true;
    showNodeNames = true;
    showPlaceGraphArrows = true;

    applyToDocument(): void {
        document.body.classList.toggle('bigraph-hide-roots', !this.showRoots);
        document.body.classList.toggle('bigraph-hide-node-names', !this.showNodeNames);
        document.body.classList.toggle('bigraph-hide-place-arrows', !this.showPlaceGraphArrows);
    }
}
