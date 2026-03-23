import * as vscode from 'vscode';
import { type EvolutionActionPayload } from './runEvolution.js';
import { type EvolutionFormState } from './evolutionManagerState.js';
import { setEvolutionFolder, refreshTreeFromJson, handleVerifyResult } from './evolutionManagerJsonIO.js';
import { handleEvolutionAction, handleTreeNodeClicked, loadEvolutionManagerHtml } from './evolutionManagerActions.js';

export { type EvolutionFormState } from './evolutionManagerState.js';

let _instance: EvolutionManagerViewProvider | null = null;

export function getEvolutionManagerViewProvider(): EvolutionManagerViewProvider | null {
	return _instance;
}

/**
 * Webview view provider for the Evolution Manager sidebar section.
 */
export class EvolutionManagerViewProvider implements vscode.WebviewViewProvider {
	private _view: vscode.WebviewView | undefined;
	private _evolutionConfigPath: string | null = null;
	private readonly _extensionUri: vscode.Uri;
	_formState: EvolutionFormState | null = null;
	/** Maps verificationId → operationId for in-flight verify requests. */
	private readonly _pendingVerifyOperationId = new Map<string, string | null>();

	constructor(extensionUri: vscode.Uri) {
		this._extensionUri = extensionUri;
		_instance = this;
	}

	// ── Accessors ──────────────────────────────────────────────────────────────

	get formState(): EvolutionFormState | null { return this._formState; }
	get evolutionConfigPath(): string | null   { return this._evolutionConfigPath; }
	get isNewEvolution(): boolean              { return this._evolutionConfigPath === null; }

	// ── State setters ──────────────────────────────────────────────────────────

	setFormState(state: EvolutionFormState): void {
		this._evolutionConfigPath = state.evolutionConfigRelPath
			? /* the folder path is set by callers separately */ this._evolutionConfigPath
			: null;
		this._formState = state;
	}

	/** Merges a partial update into `_formState` (no-op if no state exists yet). */
	patchFormState(patch: Partial<EvolutionFormState>): void {
		if (this._formState) {
			this._formState = { ...this._formState, ...patch };
		}
	}

	/**
	 * Called when the user selects an existing evolution folder from the Evolutions list.
	 */
	setEvolutionFolder(folderPath: string): void {
		this._evolutionConfigPath = folderPath;
		setEvolutionFolder(folderPath, this);
	}

	/**
	 * Resets to a blank new-evolution state.
	 */
	setNewEvolution(): void {
		this._evolutionConfigPath = null;
		this._formState = {
			evolutionLabel: '',
			bigraphFsPath: '',
			bigraphLockedFromConfig: false,
			rewriteRules: [],
			verificationBigraphs: [],
			maxOperationsEnabled: true,
			maxOperations: 10,
			checkpointFileGeneration: true,
			visualizeIntermediateSteps: false,
			workspaceBigraph: '',
			operations: [],
			evolutionConfigRelPath: null,
			checkpointCursor: null
		};
		this.postMessage({ type: 'setConfigPath', configRelPath: null });
		this.postMessage({ type: 'updateTree', operations: [], checkpointCursor: null });
	}

	/**
	 * Re-reads evolution.json and pushes fresh operations + checkpointCursor to the webview.
	 */
	refreshTreeFromJson(): void {
		refreshTreeFromJson(this);
	}

	/** Called by the connector when the server responds with a verify result. */
	handleVerifyResult(verificationId: string, matched: boolean, message: string): void {
		const operationId = this._pendingVerifyOperationId.get(verificationId) ?? null;
		this._pendingVerifyOperationId.delete(verificationId);
		handleVerifyResult(verificationId, matched, message, operationId, this);
	}

	postMessage(message: Record<string, unknown>): void {
		this._view?.webview.postMessage(message);
	}

	// ── WebviewViewProvider ───────────────────────────────────────────────────

	resolveWebviewView(
		webviewView: vscode.WebviewView,
		_context: vscode.WebviewViewResolveContext,
		_token: vscode.CancellationToken
	): void {
		this._view = webviewView;
		webviewView.onDidDispose(() => { this._view = undefined; });

		webviewView.webview.options = {
			enableScripts: true,
			localResourceRoots: [this._extensionUri]
		};

		webviewView.webview.onDidReceiveMessage(
			(msg: { type?: string } & EvolutionActionPayload & { state?: EvolutionFormState }) => {
				switch (msg.type) {
					case 'webviewReady': {
						const { notifyEvolutionManagerActiveXmiTab } = require('./init') as typeof import('./init');
						notifyEvolutionManagerActiveXmiTab();
						if (this._formState !== null) {
							this.postMessage({ type: 'restoreFormState', state: this._formState });
						}
						if (this._evolutionConfigPath !== null) {
							this.postMessage({ type: 'evolutionFolderSelected', folderPath: this._evolutionConfigPath });
						}
						break;
					}
					case 'evolutionAction':
						handleEvolutionAction(msg, this);
						break;
					case 'formStateChanged':
						if (msg.state) {
							this._formState = {
								...msg.state,
								operations:             this._formState?.operations             ?? msg.state.operations            ?? [],
								checkpointCursor:       this._formState?.checkpointCursor       ?? msg.state.checkpointCursor      ?? null,
								workspaceBigraph:       msg.state.workspaceBigraph || this._formState?.workspaceBigraph || '',
								evolutionConfigRelPath: this._formState?.evolutionConfigRelPath ?? msg.state.evolutionConfigRelPath ?? null
							};
						}
						break;
				case 'verificationBigraphsChanged': {
						const vbs = (msg as unknown as { verificationBigraphs: EvolutionFormState['verificationBigraphs'] }).verificationBigraphs;
						if (this._formState && Array.isArray(vbs)) {
							this._formState = { ...this._formState, verificationBigraphs: vbs };
						}
						break;
					}
					case 'verifyBigraph': {
						const req = msg as unknown as {
							verificationId: string;
							verificationPath: string;
							operationId?: string;
							checkpointPath?: string;
						};
						this._pendingVerifyOperationId.set(req.verificationId, req.operationId ?? null);
						this._dispatchVerify(req).catch((err) =>
							console.error('[Evolution Manager] verifyBigraph dispatch failed:', err)
						);
						break;
					}
				case 'queryPendingDrag': {
					const { consumePendingDropFiles } = require('../explorer/bigraphFileExplorerProvider') as typeof import('../explorer/bigraphFileExplorerProvider');
					const pending = consumePendingDropFiles();
					this.postMessage({ type: 'pendingDragFiles', files: pending });
					break;
				}
				case 'treeNodeClicked':
					handleTreeNodeClicked(
						msg as unknown as { operationId: string; resultPath: string },
						this
					).catch((err) => console.error('[Evolution Manager] treeNodeClicked failed:', err));
					break;
				}
			}
		);

		loadEvolutionManagerHtml(webviewView.webview, this._extensionUri).then((html: string) => {
			webviewView.webview.html = html;
		});
	}

	private async _dispatchVerify(req: {
		verificationId: string;
		verificationPath: string;
		checkpointPath?: string;
	}): Promise<void> {
		const { getGlspConnector } = await import('../editor/init.js');
		const glspConnector = getGlspConnector();
		if (!glspConnector) {
			vscode.window.showErrorMessage('GLSP connector not available.');
			return;
		}
		glspConnector.dispatchAction({
			kind: 'bigraph.verifyBigraph',
			verificationId: req.verificationId,
			verificationPath: req.verificationPath,
			checkpointPath: req.checkpointPath ?? null
		});
	}
}
