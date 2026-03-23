import { GlspEditorProvider, GlspVscodeConnector } from '@eclipse-glsp/vscode-integration';
import * as path from 'path';
import * as vscode from 'vscode';
import { getEvolutionManagerViewProvider } from '../evolution/evolutionManagerViewProvider';
import type { SessionRegistry } from '../bigraph/session-registry';
import type { BigraphFacade } from '../bigraph/bigraph-facade';

/** Returns true when the given file lives inside a *.evolution folder and is not the
 *  active workspace-bigraph (which the user and the engine are allowed to modify). */
function isEvolutionReadOnly(fsPath: string): boolean {
    const parts = fsPath.split(path.sep);
    if (!parts.some(segment => segment.endsWith('.evolution'))) {
        return false;
    }
    return path.basename(fsPath) !== 'workspace-bigraph.xmi';
}

export default class EditorProvider extends GlspEditorProvider {
    diagramType = 'bigraph-xmi';

    private static panels: Set<vscode.WebviewPanel> = new Set();
    private static readOnlyMode = false;

    /** Maps clientId → absolute fsPath of the document opened in that panel. */
    private static clientIdToPath: Map<string, string> = new Map();
    /** Maps clientId → its WebviewPanel, so we can check .active at any time. */
    private static clientIdToPanel: Map<string, vscode.WebviewPanel> = new Map();
    /** clientId of the most recently focused bigraph panel. */
    private static activeClientId: string | undefined;

    /** Returns the GLSP clientId for the given absolute fsPath, or undefined if not open. */
    static getClientIdForFsPath(fsPath: string): string | undefined {
        for (const [cid, p] of EditorProvider.clientIdToPath) {
            if (p === fsPath) { return cid; }
        }
        return undefined;
    }

    /** Returns the absolute fsPath for the given GLSP clientId, or undefined if not tracked. */
    static getFsPathForClientId(clientId: string): string | undefined {
        return EditorProvider.clientIdToPath.get(clientId);
    }

    static getActiveClientInfo(): { clientId: string; fsPath: string } | undefined {
        // First try the explicitly tracked active client
        const id = EditorProvider.activeClientId;
        if (id) {
            const fsPath = EditorProvider.clientIdToPath.get(id);
            if (fsPath) { return { clientId: id, fsPath }; }
        }
        // Fall back: find any panel that is currently active
        for (const [cid, panel] of EditorProvider.clientIdToPanel) {
            if (panel.active) {
                const fsPath = EditorProvider.clientIdToPath.get(cid);
                if (fsPath) {
                    EditorProvider.activeClientId = cid;
                    return { clientId: cid, fsPath };
                }
            }
        }
        // Last resort: return the most recently registered panel
        const lastEntry = [...EditorProvider.clientIdToPath.entries()].at(-1);
        if (lastEntry) {
            return { clientId: lastEntry[0], fsPath: lastEntry[1] };
        }
        return undefined;
    }

    private static notifyEvolutionManagerActiveTab(): void {
        const info = EditorProvider.getActiveClientInfo();
        const evolutionManager = getEvolutionManagerViewProvider();
        if (!evolutionManager) { return; }
        const relativePath = info?.fsPath
            ? vscode.workspace.asRelativePath(info.fsPath)
            : null;
        evolutionManager.postMessage({
            type: 'activeTabChanged',
            clientId: info?.clientId ?? null,
            fsPath: info?.fsPath ?? null,
            relativePath: relativePath ?? null
        });
    }

    constructor(
        protected readonly extensionContext: vscode.ExtensionContext,
        protected override readonly glspVscodeConnector: GlspVscodeConnector,
        protected readonly sessionRegistry?: SessionRegistry,
        protected readonly bigraphFacade?: BigraphFacade
    ) {
        super(glspVscodeConnector);
    }

    static postMessageToAllPanels(message: any): void {
        for (const panel of EditorProvider.panels) {
            panel.webview.postMessage(message);
        }
    }

    static setReadOnlyMode(enabled: boolean): void {
        EditorProvider.readOnlyMode = enabled;
        EditorProvider.postMessageToAllPanels({ type: 'setReadonlyMode', readonly: enabled });
    }

    static isReadOnlyMode(): boolean {
        return EditorProvider.readOnlyMode;
    }

    setUpWebview(
        document: vscode.CustomDocument,
        webviewPanel: vscode.WebviewPanel,
        _token: vscode.CancellationToken,
        clientId: string
    ): void {
        EditorProvider.panels.add(webviewPanel);
        EditorProvider.clientIdToPath.set(clientId, document.uri.fsPath);
        EditorProvider.clientIdToPanel.set(clientId, webviewPanel);

        const filePath = document.uri.fsPath;
        const fileName = path.basename(filePath);
        if (this.sessionRegistry) {
            this.sessionRegistry.register({ clientId, filePath, fileName, panel: webviewPanel });
        }
        if (this.bigraphFacade) {
            this.bigraphFacade.registerPanel(webviewPanel);
            console.log('[bigraphide] Facade panel listener registered for', clientId);
        }

        const isReadOnly = EditorProvider.readOnlyMode || isEvolutionReadOnly(document.uri.fsPath);

        // If this panel is already the active one at registration time, track it immediately.
        if (webviewPanel.active) {
            EditorProvider.activeClientId = clientId;
            this.sessionRegistry?.setActive(clientId);
        }

        webviewPanel.onDidDispose(() => {
            EditorProvider.panels.delete(webviewPanel);
            EditorProvider.clientIdToPath.delete(clientId);
            EditorProvider.clientIdToPanel.delete(clientId);
            this.sessionRegistry?.unregister(clientId);
            if (EditorProvider.activeClientId === clientId) {
                EditorProvider.activeClientId = undefined;
                EditorProvider.notifyEvolutionManagerActiveTab();
            }
        });

        webviewPanel.onDidChangeViewState(() => {
            if (webviewPanel.active) {
                EditorProvider.activeClientId = clientId;
                this.sessionRegistry?.setActive(clientId);
                EditorProvider.notifyEvolutionManagerActiveTab();
            }
        });

        const webview = webviewPanel.webview;

        webview.onDidReceiveMessage((msg: { type?: string; evolutionLabel?: string; rewriteRules?: { setLabel: string; label: string }[] }) => {
            if (msg.type !== 'fillEvolutionFormFromCanvasDrop' || typeof msg.evolutionLabel !== 'string' || !Array.isArray(msg.rewriteRules)) {
                return;
            }
            const workspaceRoot = vscode.workspace.workspaceFolders?.[0];
            const bigraphPath = workspaceRoot
                ? vscode.workspace.asRelativePath(document.uri)
                : document.uri.fsPath;
            const evolutionManager = getEvolutionManagerViewProvider();
            if (evolutionManager) {
                evolutionManager.postMessage({
                    type: 'fillEvolutionForm',
                    evolutionLabel: msg.evolutionLabel,
                    bigraphPath,
                    rewriteRules: msg.rewriteRules
                });
                void vscode.commands.executeCommand('workbench.view.extension.bigraph-explorer-container');
                void vscode.commands.executeCommand('evolutionManagerView.focus');
            }
        });

        webviewPanel.webview.options = {
            enableScripts: true,
            localResourceRoots: [this.extensionContext.extensionUri]
        };

        const webviewPath = vscode.Uri.joinPath(this.extensionContext.extensionUri, 'webview', 'dist', 'webview.js');
        const webviewUri = webview.asWebviewUri(webviewPath);

        webviewPanel.webview.html = `
            <!DOCTYPE html>
            <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, height=device-height">
                    <meta http-equiv="Content-Security-Policy" content="
                        default-src 'none';
                        script-src ${webview.cspSource} 'unsafe-inline' 'unsafe-eval';
                        style-src ${webview.cspSource} 'unsafe-inline';
                        font-src ${webview.cspSource};
                        connect-src ws: wss: http: https:;
                    ">
                    <title>Bigraph Diagram Editor</title>
                    <style>
                        body {
                            margin: 0;
                            padding: 0;
                            font-family: Arial, sans-serif;
                            background-color: #f5f5f5;
                            overflow: hidden;
                        }
                        #${clientId}_container {
                            width: 100vw;
                            height: 100vh;
                            position: relative;
                        }
                        #readonly-overlay {
                            position: fixed;
                            top: 0;
                            left: 0;
                            right: 0;
                            z-index: 9999;
                            display: ${isReadOnly ? 'block' : 'none'};
                            pointer-events: none;
                        }
                        #readonly-banner {
                            width: 100%;
                            box-sizing: border-box;
                            padding: 8px 12px;
                            font-size: 12px;
                            font-weight: 600;
                            letter-spacing: 0.02em;
                            background: #f2cc60;
                            color: #3b2f00;
                            border-bottom: 1px solid #c49a1e;
                            user-select: none;
                            text-align: center;
                        }
                        body.readonly-active .tool-palette {
                            display: none !important;
                        }
                    </style>
                </head>
                <body>
                    <div id="${clientId}_container"></div>
                    <div id="readonly-overlay">
                        <div id="readonly-banner">Read-only mode enabled</div>
                    </div>
                    <script src="${webviewUri}"></script>
                    <script>
                        (function() {
                            const overlay = document.getElementById('readonly-overlay');

                            function applyReadOnly(enabled) {
                                if (overlay) {
                                    overlay.style.display = enabled ? 'block' : 'none';
                                }
                                document.body.classList.toggle('readonly-active', enabled);
                            }

                            // Apply initial state immediately (baked in from server side)
                            applyReadOnly(${isReadOnly});

                            // The tool-palette is rendered asynchronously by the GLSP bundle.
                            // Re-apply after a short delay so the class lands after the DOM is ready.
                            if (${isReadOnly}) {
                                setTimeout(function() {
                                    document.body.classList.add('readonly-active');
                                }, 500);
                            }

                            window.addEventListener('message', function(event) {
                                const msg = event.data;
                                if (!msg || msg.type !== 'setReadonlyMode') {
                                    return;
                                }
                                applyReadOnly(msg.readonly);
                            });
                        })();
                    </script>
                </body>
            </html>`;
    }
}