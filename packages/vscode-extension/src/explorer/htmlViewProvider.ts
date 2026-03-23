import * as vscode from 'vscode';

interface RuleEditorContext {
    mode: 'new' | 'edit';
    setLabel: string;
    setIndex?: number;
    label?: string;
    leftPath?: string;
    rightPath?: string;
    originalLabel?: string;
}


/** Message from webview to extension */
export interface WebviewOutMessage {
    type: string;
    [key: string]: unknown;
}

let _instance: HtmlViewProvider | null = null;

/**
 * Get the HTML view provider so you can send messages to the webview from anywhere in the extension.
 * Returns null if the view has not been created yet.
 */
export function getHtmlViewProvider(): HtmlViewProvider | null {
    return _instance;
}

/**
 * Webview view provider that renders HTML in the Bigraph Explorer sidebar.
 * Supports two-way messaging so the extension can update content at runtime and the webview can send events back.
 */
export class HtmlViewProvider implements vscode.WebviewViewProvider {
    private _view: vscode.WebviewView | undefined;
    private currentContext: RuleEditorContext | null = null;

    constructor() {
        _instance = this;
    }

    resolveWebviewView(
        webviewView: vscode.WebviewView,
        _context: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken
    ): void {
        this._view = webviewView;
        webviewView.onDidDispose(() => {
            this._view = undefined;
        });

        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: []
        };

        webviewView.webview.html = getRuleEditorHtml(webviewView.webview.cspSource);

        webviewView.webview.onDidReceiveMessage((message: WebviewOutMessage) => {
            this.handleWebviewMessage(message);
        });
    }

    postMessage(message: Record<string, unknown>): void {
        this._view?.webview.postMessage(message);
    }

    queryPendingDrag(): void {
        const { consumePendingDropFiles } = require('../explorer/bigraphFileExplorerProvider') as typeof import('./bigraphFileExplorerProvider');
        const pending = consumePendingDropFiles();
        this.postMessage({ type: 'pendingDragFiles', files: pending });
    }

    startNewRuleEditor(draft: { setLabel: string; setIndex?: number }): void {
        this.currentContext = {
            mode: 'new',
            setLabel: draft.setLabel,
            setIndex: draft.setIndex
        };
        this.postMessage({
            type: 'showRuleEditor',
            context: this.currentContext
        });
    }

    startEditRuleEditor(draft: {
        setLabel: string;
        setIndex?: number;
        label: string;
        leftPath: string;
        rightPath: string;
    }): void {
        this.currentContext = {
            mode: 'edit',
            setLabel: draft.setLabel,
            setIndex: draft.setIndex,
            label: draft.label,
            leftPath: draft.leftPath,
            rightPath: draft.rightPath,
            originalLabel: draft.label
        };
        this.postMessage({
            type: 'showRuleEditor',
            context: this.currentContext
        });
    }

    private handleWebviewMessage(message: WebviewOutMessage): void {
        switch (message.type) {
            case 'ready':
                if (this.currentContext) {
                    this.postMessage({
                        type: 'showRuleEditor',
                        context: this.currentContext
                    });
                }
                break;
            case 'queryPendingDrag':
                this.queryPendingDrag();
                break;
            case 'saveRewriteRule': {
                const payload = message.payload as Record<string, unknown> | undefined;
                if (!payload) {
                    return;
                }
                vscode.commands.executeCommand('bigraph.saveRewriteRuleFromEditor', payload).then(
                    () => {
                        vscode.window.showInformationMessage('Rewrite rule saved.');
                    },
                    (error) => {
                        vscode.window.showErrorMessage(`Failed to save rewrite rule: ${error}`);
                    }
                );
                break;
            }
            case 'openRuleSide': {
                const payload = message.payload as Record<string, unknown> | undefined;
                const path = payload?.path;
                if (typeof path !== 'string' || path.trim().length === 0) {
                    return;
                }
                vscode.commands.executeCommand('bigraph.openRewriteRuleEditorSide', path);
                break;
            }
            default:
                break;
        }
    }
}

function getRuleEditorHtml(cspSource: string): string {
    return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${cspSource} 'unsafe-inline'; script-src ${cspSource} 'unsafe-inline';">
    <title>Rewrite Rule Editor</title>
    <style>
        body {
            margin: 0;
            padding: 12px;
            font-family: var(--vscode-font-family);
            font-size: var(--vscode-font-size);
            color: var(--vscode-foreground);
            background-color: var(--vscode-editor-background);
        }
        h2, h3 {
            margin: 0 0 8px;
            color: var(--vscode-foreground);
        }
        .hidden {
            display: none;
        }
        .hint {
            color: var(--vscode-descriptionForeground);
            margin-bottom: 12px;
        }
        .row {
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .field {
            margin-bottom: 12px;
        }
        input[type="text"] {
            width: 100%;
            box-sizing: border-box;
            border: 1px solid var(--vscode-input-border);
            background: var(--vscode-input-background);
            color: var(--vscode-input-foreground);
            border-radius: 6px;
            padding: 8px;
        }
        .boxes {
            display: grid;
            grid-template-columns: 1fr auto 1fr;
            align-items: center;
            gap: 8px;
            margin-bottom: 12px;
        }
        .arrow {
            font-size: 20px;
            user-select: none;
            color: var(--vscode-descriptionForeground);
        }
        .slot {
            min-height: 84px;
            border: 1px dashed var(--vscode-descriptionForeground);
            border-radius: 4px;
            display: flex;
            flex-direction: column;
            justify-content: center;
            align-items: center;
            text-align: center;
            padding: 8px;
            transition: border-color .15s ease, background-color .15s ease;
            cursor: default;
        }
        .slot.empty {
            background: transparent;
        }
        .slot.filled {
            border-style: solid;
            border-color: var(--vscode-focusBorder);
            background: var(--vscode-editor-inactiveSelectionBackground);
            cursor: pointer;
        }
        .slot.dragover {
            border-color: var(--vscode-focusBorder);
            background: var(--vscode-list-hoverBackground);
        }
        .slot-title {
            font-size: 11px;
            text-transform: uppercase;
            letter-spacing: 0.03em;
            margin-bottom: 6px;
            color: var(--vscode-descriptionForeground);
        }
        .slot-value {
            font-weight: 600;
            word-break: break-word;
        }
        .drop-help {
            margin-top: 6px;
            font-size: 11px;
            color: var(--vscode-descriptionForeground);
        }
        button {
            width: 100%;
            border: 1px solid var(--vscode-button-border);
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            border-radius: 6px;
            padding: 8px 10px;
            cursor: pointer;
        }
        button:hover {
            background: var(--vscode-button-hoverBackground);
        }
        button:disabled {
            opacity: 0.55;
            cursor: default;
            background: var(--vscode-button-secondaryBackground);
            color: var(--vscode-disabledForeground);
        }
    </style>
</head>
<body>
    <div id="empty-state" class="hint">Click + on a rule set to create a rule.</div>
    <div id="editor" class="hidden">
        <h2 id="mode-title">New Rule</h2>
        <div class="field">
            <input id="rule-name" type="text" placeholder="Rule name" />
        </div>
        <div class="boxes">
            <div id="left-slot" class="slot empty">
                <div class="slot-title">Redex</div>
                <div class="slot-value">Drop bigraph here</div>
                <div class="drop-help">Drag from Bigraphs tree</div>
            </div>
            <div class="arrow">→</div>
            <div id="right-slot" class="slot empty">
                <div class="slot-title">Reactum</div>
                <div class="slot-value">Drop bigraph here</div>
                <div class="drop-help">Drag from Bigraphs tree</div>
            </div>
        </div>
        <button id="save-btn" type="button">Save</button>
    </div>
    <script>
        (function() {
            const vscode = acquireVsCodeApi();
            const state = {
                context: null,
                leftPath: '',
                rightPath: '',
                latestDragged: null,
                awaitingDropTarget: false,
                pendingDropSide: null
            };

            const emptyStateEl = document.getElementById('empty-state');
            const editorEl = document.getElementById('editor');
            const modeTitleEl = document.getElementById('mode-title');
            const ruleNameInput = document.getElementById('rule-name');
            const leftSlot = document.getElementById('left-slot');
            const rightSlot = document.getElementById('right-slot');
            const saveBtn = document.getElementById('save-btn');

            function getBaseName(filePath) {
                if (!filePath) return '';
                const normalized = filePath.replace(/\\\\/g, '/');
                const parts = normalized.split('/');
                const fileName = parts[parts.length - 1] || normalized;
                const lastDotIndex = fileName.lastIndexOf('.');
                if (lastDotIndex > 0) {
                    return fileName.slice(0, lastDotIndex);
                }
                return fileName;
            }

            function renderSlot(slotEl, side, path) {
                if (!slotEl) return;
                const valueEl = slotEl.querySelector('.slot-value');
                if (!valueEl) return;
                const hasPath = !!path;
                slotEl.classList.toggle('empty', !hasPath);
                slotEl.classList.toggle('filled', hasPath);
                valueEl.textContent = hasPath ? getBaseName(path) : 'Drop bigraph here';
                slotEl.title = hasPath ? path : '';
                slotEl.dataset.side = side;
            }

            function renderEditor() {
                const hasContext = !!state.context;
                emptyStateEl.classList.toggle('hidden', hasContext);
                editorEl.classList.toggle('hidden', !hasContext);
                if (!hasContext) {
                    saveBtn.disabled = true;
                    return;
                }

                modeTitleEl.textContent = state.context.mode === 'edit' ? 'Edit Rule' : 'New Rule';
                ruleNameInput.value = state.context.label || '';
                state.leftPath = state.context.leftPath || '';
                state.rightPath = state.context.rightPath || '';
                renderSlot(leftSlot, 'left', state.leftPath);
                renderSlot(rightSlot, 'right', state.rightPath);
                updateSaveButtonState();
            }

            function updateSaveButtonState() {
                const hasLabel = ruleNameInput.value.trim().length > 0;
                saveBtn.disabled = !(state.context && hasLabel && state.leftPath && state.rightPath);
            }

            function assignLatestDragged(side) {
                if (!state.latestDragged || !state.latestDragged.fsPath) {
                    return;
                }
                const relativePath = state.latestDragged.relativePath || state.latestDragged.fsPath.replace(/\\\\/g, '/').split('/').slice(-1)[0];
                if (side === 'left') {
                    state.leftPath = relativePath;
                    renderSlot(leftSlot, 'left', state.leftPath);
                } else {
                    state.rightPath = relativePath;
                    renderSlot(rightSlot, 'right', state.rightPath);
                }
                state.awaitingDropTarget = false;
                updateSaveButtonState();
            }

            function wireSlot(slotEl, side) {
                if (!slotEl) return;
                slotEl.addEventListener('dragover', function(event) {
                    event.preventDefault();
                    slotEl.classList.add('dragover');
                });
                slotEl.addEventListener('dragleave', function() {
                    slotEl.classList.remove('dragover');
                });
                slotEl.addEventListener('drop', function(event) {
                    event.preventDefault();
                    slotEl.classList.remove('dragover');
                    // Try push-model first, then fall back to pull
                    if (state.awaitingDropTarget) {
                        assignLatestDragged(side);
                    } else {
                        state.pendingDropSide = side;
                        vscode.postMessage({ type: 'queryPendingDrag' });
                    }
                });
                // mouseenter/mouseup: if push-model already set awaitingDropTarget, consume.
                // Otherwise ask the host for the stashed file (pull-model).
                slotEl.addEventListener('mouseup', function() {
                    if (state.awaitingDropTarget) {
                        assignLatestDragged(side);
                    } else {
                        state.pendingDropSide = side;
                        vscode.postMessage({ type: 'queryPendingDrag' });
                    }
                });
                slotEl.addEventListener('mouseenter', function() {
                    if (state.awaitingDropTarget) {
                        assignLatestDragged(side);
                    } else {
                        state.pendingDropSide = side;
                        vscode.postMessage({ type: 'queryPendingDrag' });
                    }
                });
                slotEl.addEventListener('click', function() {
                    const assignedPath = side === 'left' ? state.leftPath : state.rightPath;
                    if (assignedPath) {
                        vscode.postMessage({
                            type: 'openRuleSide',
                            payload: { path: assignedPath }
                        });
                        return;
                    }
                    assignLatestDragged(side);
                });
            }

            wireSlot(leftSlot, 'left');
            wireSlot(rightSlot, 'right');
            ruleNameInput.addEventListener('input', updateSaveButtonState);

            saveBtn.addEventListener('click', function() {
                if (!state.context) return;
                const label = ruleNameInput.value.trim();
                if (!label || !state.leftPath || !state.rightPath) {
                    return;
                }
                vscode.postMessage({
                    type: 'saveRewriteRule',
                    payload: {
                        mode: state.context.mode,
                        setLabel: state.context.setLabel,
                        setIndex: state.context.setIndex,
                        originalLabel: state.context.originalLabel,
                        label: label,
                        leftPath: state.leftPath,
                        rightPath: state.rightPath
                    }
                });
            });

            window.addEventListener('message', function(event) {
                const msg = event.data;
                if (!msg || !msg.type) return;

                if (msg.type === 'showRuleEditor' && msg.context) {
                    state.context = msg.context;
                    renderEditor();
                } else if (msg.type === 'bigraphDragStarted' && Array.isArray(msg.files) && msg.files.length > 0) {
                    state.latestDragged = msg.files[0];
                    state.awaitingDropTarget = true;
                } else if (msg.type === 'pendingDragFiles' && Array.isArray(msg.files) && msg.files.length > 0) {
                    // Reply from pull-model query — assign to the slot that triggered the request
                    const side = state.pendingDropSide;
                    state.pendingDropSide = null;
                    if (side && msg.files[0].fsPath) {
                        state.latestDragged = msg.files[0];
                        state.awaitingDropTarget = false;
                        assignLatestDragged(side);
                    }
                }
            });

            vscode.postMessage({ type: 'ready' });
        })();
    </script>
</body>
</html>`;
}
