/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import type { PaletteHostApi } from './palette-types';

/**
 * A single export action contributed by a diagram extension and rendered
 * inside the owning variant's palette (not the shared shell).
 */
export interface PaletteExportAction {
    readonly id: string;
    readonly label: string;
    readonly title?: string;
    run(host: PaletteHostApi): void | Promise<void>;
}

/**
 * Mounts an Export section with action buttons at the end of {@code parent}.
 * No-op when {@code actions} is empty.
 */
export function appendPaletteExportSection(
    parent: HTMLElement,
    actions: ReadonlyArray<PaletteExportAction>,
    host: PaletteHostApi
): void {
    if (actions.length === 0) {
        return;
    }

    const section = document.createElement('div');
    section.dataset.section = 'palette-export';
    section.innerHTML = `
        <div class="bp-section-hdr">
            <span class="bp-section-title">Export</span>
        </div>
        <div class="bp-section-content">
            <div class="bp-section-inner">
                <div class="bp-action-row" data-slot="export-actions"></div>
            </div>
        </div>
    `;

    const row = section.querySelector<HTMLElement>('[data-slot="export-actions"]');
    if (!row) {
        return;
    }

    for (const action of actions) {
        const btn = document.createElement('button');
        btn.className = 'bp-action-btn';
        btn.type = 'button';
        btn.dataset.exportId = action.id;
        btn.textContent = action.label;
        btn.title = action.title ?? action.label;
        btn.onclick = () => void action.run(host);
        row.appendChild(btn);
    }

    parent.appendChild(section);
}

export function acquireVsCodeApi(): { postMessage(msg: unknown): void } | null {
    const fn = (globalThis as { acquireVsCodeApi?: () => { postMessage(msg: unknown): void } })
        .acquireVsCodeApi;
    if (!fn) {
        return null;
    }
    try {
        return fn();
    } catch {
        return null;
    }
}
