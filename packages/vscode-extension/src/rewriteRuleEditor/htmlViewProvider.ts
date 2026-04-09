import * as vscode from 'vscode';
import { loadRewriteRuleEditorHtml } from './rewriteRuleEditorWebview.js';
import { getDragService } from '../dragging';
import type { DndZoneDescriptor } from '../dragging';

function logDnd(_scope: string, _event: string, _details?: unknown): void {
    // DnD debug channel removed intentionally.
}

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

export function getHtmlViewProvider(): HtmlViewProvider | null {
    return _instance;
}

export class HtmlViewProvider implements vscode.WebviewViewProvider {
    private _view: vscode.WebviewView | undefined;
    private currentContext: RuleEditorContext | null = null;
    private readonly _extensionUri: vscode.Uri;

    constructor(extensionUri: vscode.Uri) {
        this._extensionUri = extensionUri;
        _instance = this;
    }

    resolveWebviewView(
        webviewView: vscode.WebviewView,
        _context: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken
    ): void {
        this._view = webviewView;
        const dragService = getDragService();
        const webviewId = 'rewriteRuleEditor';
        const unsubscribeDnd = dragService.subscribe((event) => {
            if (event.type === 'sessionStarted') {
                this.postMessage({ type: 'dndSessionStarted', sessionId: event.sessionId, payloadType: event.payloadType });
            } else {
                this.postMessage({ type: 'dndSessionEnded', sessionId: event.sessionId, reason: event.reason });
            }
        });
        webviewView.onDidDispose(() => {
            this._view = undefined;
            unsubscribeDnd();
        });

        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: [this._extensionUri]
        };

        loadRewriteRuleEditorHtml(webviewView.webview, this._extensionUri).then((html: string) => {
            webviewView.webview.html = html;
        });

        webviewView.webview.onDidReceiveMessage((message: WebviewOutMessage) => {
            this.handleWebviewMessage(message);
        });
    }

    postMessage(message: Record<string, unknown>): void {
        this._view?.webview.postMessage(message);
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
            case 'dndRegisterZone': {
                const zone = message as unknown as DndZoneDescriptor;
                if (typeof zone.zoneId === 'string' && Array.isArray(zone.accepts)) {
                    getDragService().registerZone('rewriteRuleEditor', {
                        zoneId: zone.zoneId,
                        accepts: zone.accepts,
                        priority: typeof zone.priority === 'number' ? zone.priority : 0
                    });
                }
                break;
            }
            case 'dndUnregisterZone': {
                const zoneId = (message as unknown as { zoneId?: string }).zoneId;
                if (typeof zoneId === 'string') {
                    getDragService().unregisterZone('rewriteRuleEditor', zoneId);
                }
                break;
            }
            case 'dndHoverZone': {
                const zoneId = (message as unknown as { zoneId?: string | null }).zoneId ?? null;
                getDragService().setHover('rewriteRuleEditor', zoneId);
                break;
            }
            case 'dndFinalize': {
                const reason = (message as unknown as { reason?: string }).reason ?? 'webview';
                const result = getDragService().finalize('rewriteRuleEditor', reason);
                if (result) {
                    this.postMessage({
                        type: 'dndDropDelivered',
                        sessionId: result.sessionId,
                        zoneId: result.zoneId,
                        payloadType: result.payloadType,
                        payload: result.payload
                    });
                }
                break;
            }
            case 'dndDebugLog': {
                const source = typeof message.source === 'string' ? message.source : 'rewriteRuleEditor.webview';
                const event = typeof message.event === 'string' ? message.event : 'event';
                logDnd(`webview.${source}`, event, message.details);
                break;
            }
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
                const sidePath = payload?.path;
                if (typeof sidePath !== 'string' || sidePath.trim().length === 0) {
                    return;
                }
                vscode.commands.executeCommand('bigraph.openRewriteRuleEditorSide', sidePath);
                break;
            }
            default:
                break;
        }
    }
}
