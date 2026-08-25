/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

/**
 * Tracks the diagram's active variant id inside the webview DI scope.
 * Updated by {@link SetActiveVariantHandler} whenever the server pushes
 * a variant switch.
 */
export class ActiveVariantTracker {
    private activeVariantId: string | null = null;

    setActiveVariantId(variantId: string): void {
        this.activeVariantId = variantId;
    }

    getActiveVariantId(): string | null {
        return this.activeVariantId;
    }
}
