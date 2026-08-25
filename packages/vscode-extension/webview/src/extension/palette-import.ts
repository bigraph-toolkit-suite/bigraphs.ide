/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import type { PaletteHostApi } from './palette-types';

/**
 * A single import action contributed by a diagram extension and rendered
 * inside the owning variant's palette Import section.
 */
export interface PaletteImportAction {
    readonly id: string;
    readonly label: string;
    readonly title?: string;
    run(host: PaletteHostApi): void | Promise<void>;
}

/**
 * Mounts an Import section with action buttons at the end of {@code parent}.
 */
export function appendPaletteImportSection(
    parent: HTMLElement,
    actions: ReadonlyArray<PaletteImportAction>,
    host: PaletteHostApi
): void {
    if (actions.length === 0) {
        return;
    }

    const section = document.createElement('div');
    section.dataset.section = 'palette-import';
    section.innerHTML = `
        <div class="bp-section-hdr">
            <span class="bp-section-title">Import</span>
        </div>
        <div class="bp-section-content">
            <div class="bp-section-inner">
                <div class="bp-action-row" data-slot="import-actions"></div>
            </div>
        </div>
    `;

    const row = section.querySelector<HTMLElement>('[data-slot="import-actions"]');
    if (!row) {
        return;
    }

    for (const action of actions) {
        const btn = document.createElement('button');
        btn.className = 'bp-action-btn';
        btn.type = 'button';
        btn.dataset.importId = action.id;
        btn.textContent = action.label;
        btn.title = action.title ?? action.label;
        btn.onclick = () => void action.run(host);
        row.appendChild(btn);
    }

    parent.appendChild(section);
}
