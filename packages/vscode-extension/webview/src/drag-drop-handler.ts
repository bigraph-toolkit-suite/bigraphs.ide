/**
 * Hybrid drag-and-drop handler for files from Bigraph Explorer into GLSP canvas.
 *
 * VS Code TreeView drag data is NOT reliably forwarded into webview iframes
 * via the HTML5 DataTransfer API (cross-origin isolation strips custom MIME types).
 *
 * Strategy:
 *  1. The extension sends the dragged file info via postMessage when a drag starts.
 *  2. This handler stores that info as "pending files".
 *  3. If an HTML5 `drop` event fires in the webview, we use its coordinates
 *     together with the pending file data to dispatch a FileDroppedAction.
 *  4. If no drop event fires (common when DnD doesn't cross the iframe boundary),
 *     we activate a "click-to-place" mode so the user can click on the canvas
 *     to place the bigraph at the desired position.
 */

import { IActionDispatcher } from '@eclipse-glsp/client';
import { Action } from '@eclipse-glsp/client';

export interface FileDroppedAction extends Action {
    kind: 'bigraph.fileDropped';
    filePath: string;
    position: { x: number; y: number };
}

export interface DraggedFile {
    label: string;
    uri: string;
    fsPath: string;
}

export interface DraggedRewriteRulePayload {
    evolutionLabel: string;
    rewriteRules: { setLabel: string; label: string }[];
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

let pendingFiles: DraggedFile[] = [];
let pendingRewriteRule: DraggedRewriteRulePayload | null = null;
let actionDispatcher: IActionDispatcher | null = null;
let containerElement: HTMLElement | null = null;
let clickToPlaceActive = false;
let clickToPlaceTimeout: ReturnType<typeof setTimeout> | null = null;
let pendingExpireTimeout: ReturnType<typeof setTimeout> | null = null;
let dragEnteredWebview = false;

function getVscodeApi(): { postMessage: (msg: unknown) => void } | null {
    if (typeof (globalThis as any).acquireVsCodeApi !== 'function') {
        return null;
    }
    return (globalThis as any).acquireVsCodeApi();
}

// ---------------------------------------------------------------------------
// Public API (called from app.ts)
// ---------------------------------------------------------------------------

/** Store files received from the extension via postMessage. */
export function setPendingDropFiles(files: DraggedFile[]): void {
    console.log('[DragDrop] Received pending files from extension:', files.length);
    pendingRewriteRule = null;
    pendingFiles = files;

    clearTimeouts();

    clickToPlaceTimeout = setTimeout(() => {
        if (pendingFiles.length > 0 && !dragEnteredWebview) {
            activateClickToPlace();
        }
    }, 600);

    pendingExpireTimeout = setTimeout(() => {
        clearPendingState();
    }, 30_000);
}

/** Store rewrite rule/set payload when user drags from Rewrite Rules tree (drop on canvas fills Evolution Manager). */
export function setPendingRewriteRule(payload: DraggedRewriteRulePayload | null): void {
    console.log('[DragDrop] Pending rewrite rule:', payload ? payload.evolutionLabel : 'cleared');
    pendingFiles = [];
    pendingRewriteRule = payload;

    clearTimeouts();

    clickToPlaceTimeout = setTimeout(() => {
        if (pendingRewriteRule && !dragEnteredWebview) {
            activateClickToPlace();
        }
    }, 600);

    pendingExpireTimeout = setTimeout(() => {
        clearPendingState();
    }, 30_000);
}

/** Provide the GLSP action dispatcher (set once the DI container is ready). */
export function setActionDispatcher(dispatcher: IActionDispatcher): void {
    actionDispatcher = dispatcher;
    console.log('[DragDrop] Action dispatcher set');
}

/** Provide the GLSP container element (for coordinate calculation). */
export function setContainerElement(el: HTMLElement): void {
    containerElement = el;
    console.log('[DragDrop] Container element set:', el.id);
}

/**
 * Attach all DOM event listeners.  Call once on page load -- does NOT depend
 * on the GLSP DI container being ready yet.
 */
export function initializeDragAndDrop(): void {
    // Use capture phase so we run before GLSP/Sprotty's handlers
    document.addEventListener('dragenter', onDragEnter, true);
    document.addEventListener('dragover', onDragOver, true);
    document.addEventListener('dragleave', onDragLeave, true);
    document.addEventListener('drop', onDrop, true);
    document.addEventListener('keydown', onKeyDown);

    console.log('[DragDrop] DOM listeners attached (document level, capture phase)');
}

// ---------------------------------------------------------------------------
// HTML5 Drag Event Handlers
// ---------------------------------------------------------------------------

let dragEnterCount = 0; // counter to handle nested enter/leave pairs

function onDragEnter(e: DragEvent): void {
    e.preventDefault();
    dragEnterCount++;
    if (dragEnterCount === 1) {
        dragEnteredWebview = true;
        console.log('[DragDrop] dragenter — drag cursor entered webview');
        if (e.dataTransfer) {
            console.log('[DragDrop]   types:', Array.from(e.dataTransfer.types));
        }
        showDropOverlay(true, !!pendingRewriteRule);

        // Cancel the click-to-place timeout since DnD IS reaching the webview
        if (clickToPlaceTimeout) {
            clearTimeout(clickToPlaceTimeout);
            clickToPlaceTimeout = null;
        }
        deactivateClickToPlace();
    }
}

function onDragOver(e: DragEvent): void {
    e.preventDefault();
    e.stopPropagation();
    if (e.dataTransfer) {
        e.dataTransfer.dropEffect = 'copy';
    }
}

function onDragLeave(e: DragEvent): void {
    dragEnterCount--;
    if (dragEnterCount <= 0) {
        dragEnterCount = 0;
        dragEnteredWebview = false;
        console.log('[DragDrop] dragleave — drag cursor left webview');
        showDropOverlay(false, false);
    }
}

function onDrop(e: DragEvent): void {
    e.preventDefault();
    e.stopPropagation();
    dragEnterCount = 0;
    dragEnteredWebview = false;
    showDropOverlay(false, false);

    // If we had a pending rewrite rule/set drop, fill Evolution Manager (extension will use this panel's document path)
    if (pendingRewriteRule) {
        const api = getVscodeApi();
        if (api) {
            api.postMessage({
                type: 'fillEvolutionFormFromCanvasDrop',
                evolutionLabel: pendingRewriteRule.evolutionLabel,
                rewriteRules: pendingRewriteRule.rewriteRules
            });
        }
        clearPendingState();
        return;
    }

    console.log('[DragDrop] drop event fired!');
    if (e.dataTransfer) {
        console.log('[DragDrop]   types:', Array.from(e.dataTransfer.types));
        for (const t of Array.from(e.dataTransfer.types)) {
            try {
                const d = e.dataTransfer.getData(t);
                console.log(`[DragDrop]   getData("${t}"):`, d ? d.substring(0, 120) : '(empty)');
            } catch { /* ignore */ }
        }
    }

    const files = extractFilesFromDataTransfer(e.dataTransfer) ?? pendingFiles;
    if (files.length === 0) {
        console.warn('[DragDrop] No file data available in drop event or pending state');
        return;
    }

    const { x, y } = canvasPosition(e.clientX, e.clientY);
    dispatchFileDrop(files, x, y);
    clearPendingState();
}

// ---------------------------------------------------------------------------
// Click-to-Place Fallback
// ---------------------------------------------------------------------------

function activateClickToPlace(): void {
    if (clickToPlaceActive) { return; }
    clickToPlaceActive = true;
    console.log('[DragDrop] Activating click-to-place mode');
    showClickToPlaceOverlay(true);
    document.addEventListener('click', onClickToPlace, true);
}

function deactivateClickToPlace(): void {
    if (!clickToPlaceActive) { return; }
    clickToPlaceActive = false;
    showClickToPlaceOverlay(false);
    document.removeEventListener('click', onClickToPlace, true);
}

function onClickToPlace(e: MouseEvent): void {
    if ((e.target as HTMLElement).id === 'bigraph-drop-cancel') {
        clearPendingState();
        return;
    }

    e.preventDefault();
    e.stopPropagation();

    if (pendingRewriteRule) {
        const api = getVscodeApi();
        if (api) {
            api.postMessage({
                type: 'fillEvolutionFormFromCanvasDrop',
                evolutionLabel: pendingRewriteRule.evolutionLabel,
                rewriteRules: pendingRewriteRule.rewriteRules
            });
        }
        clearPendingState();
        return;
    }

    if (pendingFiles.length === 0) {
        deactivateClickToPlace();
        return;
    }

    const { x, y } = canvasPosition(e.clientX, e.clientY);
    console.log('[DragDrop] Click-to-place at', x, y);
    dispatchFileDrop(pendingFiles, x, y);
    clearPendingState();
}

// ---------------------------------------------------------------------------
// Keyboard
// ---------------------------------------------------------------------------

function onKeyDown(e: KeyboardEvent): void {
    if (e.key === 'Escape' && (pendingFiles.length > 0 || pendingRewriteRule !== null || clickToPlaceActive)) {
        console.log('[DragDrop] Escape pressed — cancelling pending drop');
        clearPendingState();
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function extractFilesFromDataTransfer(dt: DataTransfer | null): DraggedFile[] | null {
    if (!dt) { return null; }

    const uriList = dt.getData('text/uri-list');
    if (uriList) {
        const files = parseUriList(uriList);
        if (files.length > 0) { return files; }
    }

    const text = dt.getData('text/plain');
    if (text && (text.startsWith('file://') || text.startsWith('/'))) {
        const files = parseUriList(text);
        if (files.length > 0) { return files; }
    }

    return null;
}

function parseUriList(raw: string): DraggedFile[] {
    return raw
        .split(/\r?\n/)
        .map(s => s.trim())
        .filter(s => s.length > 0 && !s.startsWith('#'))
        .map(uri => {
            const fsPath = uri.startsWith('file://')
                ? decodeURIComponent(uri.replace(/^file:\/\//, ''))
                : uri;
            return {
                label: fsPath.split('/').pop() ?? 'unknown',
                uri,
                fsPath
            };
        });
}

function canvasPosition(clientX: number, clientY: number): { x: number; y: number } {
    if (containerElement) {
        const rect = containerElement.getBoundingClientRect();
        return { x: clientX - rect.left, y: clientY - rect.top };
    }
    return { x: clientX, y: clientY };
}

function dispatchFileDrop(files: DraggedFile[], x: number, y: number): void {
    if (!actionDispatcher) {
        console.error('[DragDrop] Cannot dispatch — action dispatcher not available yet');
        return;
    }
    console.log(`[DragDrop] Dispatching ${files.length} file(s) at (${x}, ${y})`);
    for (const file of files) {
        const action: FileDroppedAction = {
            kind: 'bigraph.fileDropped',
            filePath: file.fsPath,
            position: { x, y }
        };
        console.log('[DragDrop] →', action);
        actionDispatcher.dispatch(action);
    }
}

function clearPendingState(): void {
    pendingFiles = [];
    pendingRewriteRule = null;
    dragEnteredWebview = false;
    dragEnterCount = 0;
    deactivateClickToPlace();
    showDropOverlay(false, false);
    clearTimeouts();
}

function clearTimeouts(): void {
    if (clickToPlaceTimeout) { clearTimeout(clickToPlaceTimeout); clickToPlaceTimeout = null; }
    if (pendingExpireTimeout) { clearTimeout(pendingExpireTimeout); pendingExpireTimeout = null; }
}

// ---------------------------------------------------------------------------
// Visual Overlays
// ---------------------------------------------------------------------------

function showDropOverlay(show: boolean, isRewriteRule: boolean): void {
    const id = 'bigraph-drop-overlay';
    let el = document.getElementById(id);
    if (show && !el) {
        el = document.createElement('div');
        el.id = id;
        el.style.cssText = [
            'position:fixed', 'inset:0', 'z-index:100000',
            'background:rgba(33,150,243,0.08)',
            'border:3px dashed rgba(33,150,243,0.5)',
            'pointer-events:none',
            'display:flex', 'align-items:center', 'justify-content:center'
        ].join(';');
        const label = isRewriteRule ? 'Drop to fill Evolution Manager' : 'Drop bigraph here';
        el.innerHTML = `<div style="
            background:rgba(33,150,243,0.85);color:#fff;
            padding:10px 24px;border-radius:8px;font-size:15px;
            font-family:system-ui,sans-serif;pointer-events:none;
        ">${label}</div>`;
        document.body.appendChild(el);
    } else if (!show && el) {
        el.remove();
    }
}

function showClickToPlaceOverlay(show: boolean): void {
    const id = 'bigraph-click-to-place';
    let el = document.getElementById(id);
    if (show && !el) {
        const isRule = pendingRewriteRule !== null;
        const message = isRule
            ? `Click on the canvas to fill Evolution Manager with <b>${pendingRewriteRule!.evolutionLabel}</b>`
            : `Click on the canvas to place <b>${pendingFiles.map(f => f.label).join(', ')}</b>`;
        el = document.createElement('div');
        el.id = id;
        el.style.cssText = [
            'position:fixed', 'top:12px', 'left:50%', 'transform:translateX(-50%)',
            'z-index:100000',
            'background:rgba(33,150,243,0.9)', 'color:#fff',
            'padding:10px 20px', 'border-radius:8px',
            'font-size:14px', 'font-family:system-ui,sans-serif',
            'display:flex', 'align-items:center', 'gap:12px',
            'box-shadow:0 2px 12px rgba(0,0,0,0.25)',
            'pointer-events:auto', 'cursor:default'
        ].join(';');
        el.innerHTML = `
            <span>${message}</span>
            <button id="bigraph-drop-cancel" style="
                background:rgba(255,255,255,0.25);border:none;color:#fff;
                padding:4px 10px;border-radius:4px;cursor:pointer;font-size:13px;
            ">Cancel</button>
        `;
        document.body.appendChild(el);
        document.getElementById('bigraph-drop-cancel')?.addEventListener('click', () => {
            clearPendingState();
        });
    } else if (!show && el) {
        el.remove();
    }
}
