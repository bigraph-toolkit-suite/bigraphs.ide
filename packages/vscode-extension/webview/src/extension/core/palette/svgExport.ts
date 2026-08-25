/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

import { PaletteExportAction } from '../../palette-export';

export function createSvgExportAction(): PaletteExportAction {
    return {
        id: 'svg',
        label: 'SVG',
        title: 'Export diagram as SVG',
        run: () => {
            window.dispatchEvent(
                new CustomEvent('bigraph-action', { detail: { kind: 'requestExportSvg' } })
            );
        },
    };
}
