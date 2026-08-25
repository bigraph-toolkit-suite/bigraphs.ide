/*
 * Copyright (c) 2026 - Manuel Krombholz
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

/**
 * Typed pub-sub for variant-related events flowing inside the webview.
 *
 * <p>Why this exists: the GLSP action handler runs in the diagram
 * module's DI scope while the palette host renders independently as a
 * GLSP UI extension. Wiring those two worlds together via raw
 * {@code window.dispatchEvent('bigraph-variant-changed', ...)} works but
 * scatters magic strings and erases the payload type at the boundary.
 * This module centralises both concerns: one string literal, one
 * payload type, and a tiny {@code emit}/{@code on} surface.</p>
 */

/** Payload carried by every variant-change event. */
export interface VariantChangedPayload {
    /** Canonical {@code ModelVariant.id} of the now-active variant. */
    readonly variantId: string;
    /**
     * Variants this diagram session can switch between. Subscribers
     * that render a variant chooser (e.g. the in-canvas tab bar) read
     * this to decide whether to show themselves at all. Always
     * contains at least the active variant.
     */
    readonly availableVariantIds: readonly string[];
}

/** Subscriber callback signature. */
export type VariantChangedListener = (payload: VariantChangedPayload) => void;

/**
 * Internal channel name. Not exported — callers must go through
 * {@link VariantChangeEvent.emit} / {@link VariantChangeEvent.on} so the
 * payload type is preserved end-to-end.
 */
const EVENT_NAME = 'bigraph-variant-changed';

export namespace VariantChangeEvent {
    /**
     * Emits a variant-change event on the window event bus. The
     * payload is forwarded as-is on the {@code CustomEvent.detail}
     * field.
     */
    export function emit(payload: VariantChangedPayload): void {
        window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: payload }));
    }

    /**
     * Subscribes {@code listener} to variant-change events and returns
     * an unsubscribe function. The wrapper unpacks {@code detail} so
     * subscribers receive {@link VariantChangedPayload} directly
     * without poking at the underlying {@code CustomEvent}.
     */
    export function on(listener: VariantChangedListener): () => void {
        const handler = (ev: Event) => {
            const detail = (ev as CustomEvent<VariantChangedPayload>).detail;
            if (detail) {
                listener(detail);
            }
        };
        window.addEventListener(EVENT_NAME, handler);
        return () => window.removeEventListener(EVENT_NAME, handler);
    }
}
