import { GlspEditorProvider, GlspVscodeConnector } from '@eclipse-glsp/vscode-integration';
import * as path from 'path';
import * as vscode from 'vscode';
import { getEvolutionManagerViewProvider } from '../evolutionManager/evolutionManagerRegistry.js';
import type { SessionRegistry } from '../assistantIntegration/session-registry';
import type { BigraphFacade } from '../assistantIntegration/bigraph-facade';
import {
	handleManagedSave,
	handleWorkspaceBarAction,
	postWorkspaceBarState,
	postWorkspaceBarStateToAllPanels,
} from '../workspaceBigraph/workspaceBigraphService.js';
import {
	consumePendingManagedOpen,
	getSession,
	registerSession,
	unregisterSession,
} from '../workspaceBigraph/sessionRegistry.js';
import {
	getEvolutionFolderFromPath,
	isEvolutionReadOnly,
	isWorkspaceBigraphPath,
} from '../workspaceBigraph/bigraphArtifacts.js';
import { notifyEvolutionManagerActiveXmiTab } from '../evolutionManager/evolutionManagerActiveTab.js';
import { openManagedWorkspaceBigraph } from '../workspaceBigraph/openWorkspaceBigraph.js';
import {
	getActiveClientInfo as registryGetActiveClientInfo,
	getClientIdForFsPath as registryGetClientIdForFsPath,
	getFsPathForClientId as registryGetFsPathForClientId,
	postMessageToAllGlspPanels,
	registerGlspClient,
	setActiveGlspClientId,
	unregisterGlspClient,
} from './glspClientRegistry.js';
import { markSessionDisposed } from './glspServerBridge.js';

export default class EditorProvider extends GlspEditorProvider {
	diagramType = 'bigraph-xmi';

	private static readOnlyMode = false;

	static getClientIdForFsPath(fsPath: string): string | undefined {
		return registryGetClientIdForFsPath(fsPath);
	}

	static getFsPathForClientId(clientId: string): string | undefined {
		return registryGetFsPathForClientId(clientId);
	}

	static getActiveClientInfo(): { clientId: string; fsPath: string } | undefined {
		return registryGetActiveClientInfo();
	}

	constructor(
		protected readonly extensionContext: vscode.ExtensionContext,
		protected override readonly glspVscodeConnector: GlspVscodeConnector,
		protected readonly sessionRegistry?: SessionRegistry,
		protected readonly bigraphFacade?: BigraphFacade
	) {
		super(glspVscodeConnector);
	}

	static postMessageToAllPanels(message: unknown): void {
		postMessageToAllGlspPanels(message);
	}

	static postWorkspaceBarState(clientId: string): void {
		postWorkspaceBarState(clientId);
	}

	static postWorkspaceBarStateToAllPanels(): void {
		postWorkspaceBarStateToAllPanels();
	}

	static setReadOnlyMode(enabled: boolean): void {
		EditorProvider.readOnlyMode = enabled;
		EditorProvider.postMessageToAllPanels({ type: 'setReadonlyMode', readonly: enabled });
	}

	static isReadOnlyMode(): boolean {
		return EditorProvider.readOnlyMode;
	}

	override async saveCustomDocument(
		document: vscode.CustomDocument,
		cancellation: vscode.CancellationToken
	): Promise<void> {
		const handled = await handleManagedSave(document);
		if (handled) { return; }
		return super.saveCustomDocument(document, cancellation);
	}

	private async registerWorkspaceSession(clientId: string, filePath: string): Promise<void> {
		const pending = consumePendingManagedOpen(filePath);
		if (pending) {
			registerSession(clientId, filePath, { managed: true, evolutionFolder: pending.evolutionFolder });
			return;
		}

		if (isWorkspaceBigraphPath(filePath)) {
			const evoFolder = getEvolutionFolderFromPath(filePath);
			if (evoFolder) {
				const provider = getEvolutionManagerViewProvider();
				if (provider) {
					provider.setEvolutionFolder(evoFolder, { skipOpenBigraph: true });
				} else {
					await openManagedWorkspaceBigraph(filePath, evoFolder);
				}
				registerSession(clientId, filePath, { managed: true, evolutionFolder: evoFolder });
			}
			return;
		}

		if (isEvolutionReadOnly(filePath)) {
			registerSession(clientId, filePath, { managed: false, evolutionFolder: null });
		}
	}

	private syncEvolutionManagerForTab(clientId: string, filePath: string): void {
		const session = getSession(clientId);
		if (!session?.managed || !isWorkspaceBigraphPath(filePath) || !session.evolutionFolder) {
			return;
		}
		const provider = getEvolutionManagerViewProvider();
		if (!provider || provider.evolutionConfigPath === session.evolutionFolder) {
			return;
		}
		provider.setEvolutionFolder(session.evolutionFolder, { skipOpenBigraph: true });
	}

	setUpWebview(
		document: vscode.CustomDocument,
		webviewPanel: vscode.WebviewPanel,
		_token: vscode.CancellationToken,
		clientId: string
	): void {
		registerGlspClient(clientId, document.uri.fsPath, webviewPanel);

		const filePath = document.uri.fsPath;
		const fileName = path.basename(filePath);
		if (this.sessionRegistry) {
			this.sessionRegistry.register({ clientId, filePath, fileName, panel: webviewPanel });
		}
		if (this.bigraphFacade) {
			this.bigraphFacade.registerPanel(webviewPanel);
		}

		void this.registerWorkspaceSession(clientId, filePath).then(() => {
			EditorProvider.postWorkspaceBarState(clientId);
		});

		const isReadOnly = EditorProvider.readOnlyMode || isEvolutionReadOnly(filePath);

		if (webviewPanel.active) {
			setActiveGlspClientId(clientId);
			this.sessionRegistry?.setActive(clientId);
		}

		webviewPanel.onDidDispose(() => {
			unregisterGlspClient(clientId, webviewPanel);
			unregisterSession(clientId);
			this.sessionRegistry?.unregister(clientId);
			markSessionDisposed(clientId);
			if (registryGetActiveClientInfo()?.clientId === clientId) {
				setActiveGlspClientId(undefined);
			}
			notifyEvolutionManagerActiveXmiTab();
		});

		webviewPanel.onDidChangeViewState(() => {
			if (webviewPanel.active) {
				setActiveGlspClientId(clientId);
				this.sessionRegistry?.setActive(clientId);
				this.syncEvolutionManagerForTab(clientId, filePath);
				notifyEvolutionManagerActiveXmiTab();
				postWorkspaceBarState(clientId);
			}
		});

		webviewPanel.webview.onDidReceiveMessage((msg: { type?: string; action?: string }) => {
			if (msg?.type === 'workspaceBarAction' && msg.action) {
				void handleWorkspaceBarAction(clientId, msg.action);
			}
		});

		const webview = webviewPanel.webview;
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
                            font-family: var(--vscode-font-family, Arial, sans-serif);
                            font-size: var(--vscode-font-size, 13px);
                            background-color: #f5f5f5;
                            overflow: hidden;
                        }
                        #${clientId}_container {
                            width: 100vw;
                            height: 100vh;
                            position: relative;
                        }
                        .editor-banner {
                            position: fixed;
                            top: 0;
                            left: 0;
                            right: 0;
                            z-index: 9999;
                            pointer-events: auto;
                            user-select: none;
                        }
                        #readonly-overlay {
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
                            text-align: center;
                        }
                        #workspace-bar {
                            display: none;
                            background: var(--vscode-editorWidget-background, #252526);
                            color: var(--vscode-editorWidget-foreground, #ccc);
                            border-bottom: 1px solid var(--vscode-widget-border, #454545);
                            padding: 6px 10px;
                            box-sizing: border-box;
                        }
                        #workspace-bar.visible { display: block; }
                        #workspace-bar.loading { position: relative; overflow: hidden; }
                        #workspace-bar.loading::after {
                            content: '';
                            position: absolute;
                            inset: 0;
                            background: linear-gradient(90deg, transparent, rgba(255,255,255,0.08), transparent);
                            animation: ws-shimmer 1.2s infinite;
                        }
                        @keyframes ws-shimmer {
                            0% { transform: translateX(-100%); }
                            100% { transform: translateX(100%); }
                        }
                        .ws-bar-row {
                            display: flex;
                            align-items: center;
                            gap: 12px;
                            flex-wrap: wrap;
                        }
                        .ws-bar-title {
                            font-weight: 600;
                            font-size: 11px;
                            letter-spacing: 0.04em;
                            text-transform: uppercase;
                            margin-right: 4px;
                        }
                        .ws-bar-section {
                            display: flex;
                            align-items: center;
                            gap: 6px;
                        }
                        .ws-bar-label {
                            font-size: 11px;
                            opacity: 0.75;
                        }
                        .ws-bar-btn {
                            font: inherit;
                            font-size: 11px;
                            padding: 3px 8px;
                            border-radius: 3px;
                            border: 1px solid var(--vscode-button-border, #555);
                            background: var(--vscode-button-secondaryBackground, #3a3d41);
                            color: var(--vscode-button-secondaryForeground, #ccc);
                            cursor: pointer;
                        }
                        .ws-bar-btn:disabled {
                            opacity: 0.4;
                            cursor: default;
                        }
                        .ws-bar-btn:not(:disabled):hover {
                            background: var(--vscode-button-secondaryHoverBackground, #45494e);
                        }
                        body.readonly-active .bigraph-palette [data-section="place-graph"] .bp-card,
                        body.readonly-active .bigraph-palette [data-section="place-graph"] [data-action="add-control"],
                        body.readonly-active .bigraph-palette [data-section="link-graph"] [data-action="connect"],
                        body.readonly-active .bigraph-palette [data-section="link-graph"] .bp-link-item,
                        body.readonly-active .bigraph-palette [data-action="delete"] {
                            opacity: 0.45 !important;
                            filter: grayscale(1);
                            pointer-events: none !important;
                            cursor: not-allowed !important;
                        }
                    </style>
                </head>
                <body>
                    <div id="${clientId}_container"></div>
                    <div class="editor-banner">
                        <div id="readonly-overlay">
                            <div id="readonly-banner">Read-only mode enabled</div>
                        </div>
                        <div id="workspace-bar">
                            <div class="ws-bar-row">
                                <span class="ws-bar-title">Workspace Bigraph</span>
                                <div class="ws-bar-section">
                                    <span class="ws-bar-label">Alignment</span>
                                    <button type="button" class="ws-bar-btn" id="ws-btn-revert-alignment" disabled>Revert</button>
                                </div>
                                <div class="ws-bar-section">
                                    <span class="ws-bar-label">Structure</span>
                                    <button type="button" class="ws-bar-btn" id="ws-btn-commit" disabled>Commit</button>
                                    <button type="button" class="ws-bar-btn" id="ws-btn-revert-structure" disabled>Revert</button>
                                </div>
                            </div>
                        </div>
                    </div>
                    <script src="${webviewUri}"></script>
                    <script>
                        (function() {
                            const vscodeApi = typeof acquireVsCodeApi === 'function' ? acquireVsCodeApi() : null;
                            const overlay = document.getElementById('readonly-overlay');
                            const workspaceBar = document.getElementById('workspace-bar');
                            const btnRevertAlignment = document.getElementById('ws-btn-revert-alignment');
                            const btnCommit = document.getElementById('ws-btn-commit');
                            const btnRevertStructure = document.getElementById('ws-btn-revert-structure');

                            function applyReadOnly(enabled) {
                                if (overlay) { overlay.style.display = enabled ? 'block' : 'none'; }
                                document.body.classList.toggle('readonly-active', enabled);
                            }

                            function applyWorkspaceBar(state) {
                                if (!workspaceBar) { return; }
                                const visible = !!state.visible;
                                workspaceBar.classList.toggle('visible', visible);
                                workspaceBar.classList.toggle('loading', !!state.evolutionRunning);
                                if (!visible) { return; }
                                if (btnRevertAlignment) { btnRevertAlignment.disabled = !state.revertAlignmentEnabled; }
                                if (btnCommit) { btnCommit.disabled = !state.commitEnabled; }
                                if (btnRevertStructure) { btnRevertStructure.disabled = !state.revertStructureEnabled; }
                                const banners = visible ? (workspaceBar.offsetHeight || 0) : 0;
                                const readOnlyH = overlay && overlay.style.display !== 'none' ? (overlay.offsetHeight || 0) : 0;
                                document.body.style.paddingTop = (banners + readOnlyH) + 'px';
                            }

                            function postAction(action) {
                                if (!vscodeApi) { return; }
                                vscodeApi.postMessage({ type: 'workspaceBarAction', action: action });
                            }

                            if (btnRevertAlignment) { btnRevertAlignment.addEventListener('click', function() { postAction('revertAlignment'); }); }
                            if (btnCommit) { btnCommit.addEventListener('click', function() { postAction('commitStructure'); }); }
                            if (btnRevertStructure) { btnRevertStructure.addEventListener('click', function() { postAction('revertStructure'); }); }

                            applyReadOnly(${isReadOnly});
                            applyWorkspaceBar({ visible: false });
                            if (${isReadOnly}) {
                                setTimeout(function() { document.body.classList.add('readonly-active'); }, 500);
                            }

                            window.addEventListener('message', function(event) {
                                const msg = event.data;
                                if (!msg || !msg.type) { return; }
                                if (msg.type === 'setReadonlyMode') {
                                    applyReadOnly(msg.readonly);
                                    return;
                                }
                                if (msg.type === 'workspaceBarState') {
                                    applyWorkspaceBar(msg);
                                }
                            });
                        })();
                    </script>
                </body>
            </html>`;
	}
}
