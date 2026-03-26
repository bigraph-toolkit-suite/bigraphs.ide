import * as vscode from 'vscode';
import { type EvolutionActionPayload } from './runEvolution.js';
import { type EvolutionFormState } from './evolutionManagerState.js';
import { setEvolutionFolder, refreshTreeFromJson, handleVerifyResult } from './evolutionManagerJsonIO.js';
import { handleEvolutionAction, handleTreeNodeClicked, loadEvolutionManagerHtml } from './evolutionManagerActions.js';
import * as fs from 'fs';
import * as path from 'path';

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
				case 'deleteVerificationBigraph': {
					const req = msg as unknown as { verificationId: string };
					this._deleteVerificationBigraph(req.verificationId).catch((err) =>
						console.error('[Evolution Manager] deleteVerificationBigraph failed:', err)
					);
					break;
				}
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

	/**
	 * Removes a verification rule from the evolution project: drops it from `evolution.json`
	 * (top-level list and any `operations[].verification` references), deletes the on-disk
	 * bigraph triplet next to that entry, then refreshes the webview from patched `_formState`
	 * without calling a full folder reload (keeps the panel stable).
	 */
	private async _deleteVerificationBigraph(verificationId: string): Promise<void> {
		try {
			const folderPath = this._evolutionConfigPath;
			if (!folderPath) { return; }
			if (!verificationId) { return; }

			const evoJsonPath = path.join(folderPath, 'evolution.json');
			let json: Record<string, unknown>;
			try {
				json = JSON.parse(fs.readFileSync(evoJsonPath, 'utf8')) as Record<string, unknown>;
			} catch {
				vscode.window.showWarningMessage('Could not read evolution.json.');
				return;
			}

			// Top-level `verification` array: remove the definition and keep the spliced row for file paths
			const verificationArr = Array.isArray(json['verification'])
				? (json['verification'] as Record<string, unknown>[])
				: [];
			const idx = verificationArr.findIndex((v) => String(v['id'] ?? '') === verificationId);
			if (idx < 0) { return; }

			const deleted = verificationArr.splice(idx, 1)[0] ?? {};
			json['verification'] = verificationArr;

			// History: each operation may list verification checks; strip this id so cursors stay consistent
			const ops = Array.isArray(json['operations'])
				? (json['operations'] as Record<string, unknown>[])
				: [];
			for (const op of ops) {
				const verList = Array.isArray(op['verification'])
					? (op['verification'] as Record<string, unknown>[])
					: [];
				const filtered = verList.filter((v) => String(v['id'] ?? '') !== verificationId);
				if (filtered.length !== verList.length) {
					op['verification'] = filtered;
				}
			}
			json['operations'] = ops;

			// On-disk cleanup: same basename as the `.xmi` stored in `deleted.path` (instance + signature pair)
			const relPath = typeof deleted['path'] === 'string' ? deleted['path'] : '';
			if (relPath) {
				const absXmi = path.isAbsolute(relPath) ? relPath : path.join(folderPath, relPath);
				const base = absXmi.replace(/\.xmi$/i, '');
				for (const ext of ['.xmi', '.signature.ecore', '.signature.xmi']) {
					const p = base + ext;
					try { if (fs.existsSync(p)) { fs.unlinkSync(p); } } catch { /* ignore */ }
				}
			}

			try {
				fs.writeFileSync(evoJsonPath, JSON.stringify(json, null, 2) + '\n', 'utf8');
			} catch {
				vscode.window.showWarningMessage('Could not write evolution.json.');
				return;
			}

			// Sync UI: push updated list into the webview directly (avoid `setEvolutionFolder` re-init)
			if (this._formState) {
				const newVbs = this._formState.verificationBigraphs.filter((v) => v.id !== verificationId);
				this._formState = { ...this._formState, verificationBigraphs: newVbs };
				this.postMessage({ type: 'restoreFormState', state: this._formState });
			} else {
				this.postMessage({ type: 'fillEvolutionForm', verificationBigraphs: [] });
			}
		} catch {
			// Broad catch: file/JSON edge cases must not take down the extension host
			vscode.window.showWarningMessage('Could not delete verification rule.');
		}
	}
}
