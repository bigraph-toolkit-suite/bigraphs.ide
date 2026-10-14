/**
 * "Color node types": a pure display preference. Toggles a class on <body>; popp-kind-colors.css does the rest.
 * Remembered in the webview's localStorage, which can be unavailable or cleared, so every access is guarded and the
 * default (on) applies whenever nothing is stored.
 */
const STORAGE_KEY = 'popp.kindColors';
const BODY_CLASS = 'popp-kind-colors';

export function isKindColoringEnabled(): boolean {
    try {
        return window.localStorage.getItem(STORAGE_KEY) !== 'off';
    } catch {
        return true;
    }
}

export function setKindColoring(enabled: boolean): void {
    document.body.classList.toggle(BODY_CLASS, enabled);
    try {
        window.localStorage.setItem(STORAGE_KEY, enabled ? 'on' : 'off');
    } catch {
        // not persisted; the toggle still works for this session
    }
}

/** Applies the stored preference, e.g. when the palette mounts. */
export function applyStoredKindColoring(): boolean {
    const enabled = isKindColoringEnabled();
    document.body.classList.toggle(BODY_CLASS, enabled);
    return enabled;
}
