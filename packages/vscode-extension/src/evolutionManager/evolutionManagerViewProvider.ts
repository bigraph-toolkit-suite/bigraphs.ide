import * as vscode from 'vscode';
import { type EvolutionActionPayload } from './runEvolution.js';
import {
	RuleApplicationStrategy,
	normalizeRuleApplicationStrategy,
	type EvolutionFormState,
} from './evolutionManagerState.js';
import { setEvolutionFolder, refreshTreeFromJson, handleVerifyResult } from './evolutionManagerJsonIO.js';
import { handleEvolutionAction, handleTreeNodeClicked, loadEvolutionManagerHtml, syncWorkspaceBigraphToCursor } from './evolutionManagerActions.js';
import * as fs from 'fs';
import * as path from 'path';
import { getDragService } from '../dragging';
import type { DndZoneDescriptor } from '../dragging';

function logDnd(_scope: string, _event: string, _details?: unknown): void {
	// DnD debug channel removed intentionally.
}

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

	/**
	 * True when the user already has an evolution session: a folder-bound project on disk,
	 * or an in-progress form (label/bigraph/rules/…) before any run created `evolution.json`.
	 * Used to merge rewrite rules into the form instead of resetting it.
	 */
	hasActiveEvolutionSession(): boolean {
		if (this._evolutionConfigPath) {
			return true;
		}
		const s = this._formState;
		if (!s) {
			return false;
		}
		if (s.rewriteRules.length > 0) {
			return true;
		}
		if (s.bigraphFsPath?.trim()) {
			return true;
		}
		if (s.evolutionLabel?.trim()) {
			return true;
		}
		if (s.workspaceBigraph?.trim()) {
			return true;
		}
		if (s.operations.length > 0) {
			return true;
		}
		if (s.verificationBigraphs.length > 0) {
			return true;
		}
		return false;
	}

	// ── State setters ──────────────────────────────────────────────────────────

	setFormState(state: EvolutionFormState): void {
		if (state.evolutionConfigRelPath) {
			if (!this._evolutionConfigPath) {
				const ws = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
				if (ws) {
					const dir = path.dirname(path.join(ws, state.evolutionConfigRelPath));
					if (fs.existsSync(path.join(dir, 'evolution.json'))) {
						this._evolutionConfigPath = dir;
					}
				}
			}
		} else {
			this._evolutionConfigPath = null;
		}
		this._formState = state;
	}

	/** Merges a partial update into `_formState` (no-op if no state exists yet). */
	patchFormState(patch: Partial<EvolutionFormState>): void {
		if (this._formState) {
			this._formState = { ...this._formState, ...patch };
		}
	}

	private _persistRuleApplicationStrategy(strategy: EvolutionFormState['ruleApplicationStrategy']): void {
		const folderPath = this._evolutionConfigPath;
		if (!folderPath) {
			return;
		}
		const evoJsonPath = path.join(folderPath, 'evolution.json');
		try {
			const json = JSON.parse(fs.readFileSync(evoJsonPath, 'utf8')) as Record<string, unknown>;
			json['ruleApplicationStrategy'] = strategy;
			fs.writeFileSync(evoJsonPath, JSON.stringify(json, null, 2) + '\n', 'utf8');
		} catch {
			vscode.window.showWarningMessage('Could not save rule application strategy to evolution.json.');
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
			ruleApplicationStrategy: RuleApplicationStrategy.FirstFirst,
			workspaceBigraph: '',
			operations: [],
			evolutionConfigRelPath: null,
			checkpointCursor: null
		};
		this.postMessage({ type: 'restoreFormState', state: this._formState });
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
		const dragService = getDragService();
		const webviewId = 'evolutionManager';
		const unsubscribeDnd = dragService.subscribe((event) => {
			if (event.type === 'sessionStarted') {
				this.postMessage({ type: 'dndSessionStarted', sessionId: event.sessionId, payloadType: event.payloadType });
			} else {
				this.postMessage({ type: 'dndSessionEnded', sessionId: event.sessionId, reason: event.reason });
			}
		});
		webviewView.onDidDispose(() => unsubscribeDnd());

		webviewView.webview.options = {
			enableScripts: true,
			localResourceRoots: [this._extensionUri]
		};

		webviewView.webview.onDidReceiveMessage(
			(msg: { type?: string } & EvolutionActionPayload & { state?: EvolutionFormState }) => {
				switch (msg.type) {
				case 'dndRegisterZone': {
					const zone = msg as unknown as DndZoneDescriptor;
					if (typeof zone.zoneId === 'string' && Array.isArray(zone.accepts)) {
						dragService.registerZone(webviewId, {
							zoneId: zone.zoneId,
							accepts: zone.accepts,
							priority: typeof zone.priority === 'number' ? zone.priority : 0
						});
					}
					break;
				}
				case 'dndUnregisterZone': {
					const zoneId = (msg as unknown as { zoneId?: string }).zoneId;
					if (typeof zoneId === 'string') {
						dragService.unregisterZone(webviewId, zoneId);
					}
					break;
				}
				case 'dndHoverZone': {
					const zoneId = (msg as unknown as { zoneId?: string | null }).zoneId ?? null;
					dragService.setHover(webviewId, zoneId);
					break;
				}
				case 'dndFinalize': {
					const reason = (msg as unknown as { reason?: string }).reason ?? 'webview';
					const result = dragService.finalize(webviewId, reason);
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
							const prevStrategy = normalizeRuleApplicationStrategy(this._formState?.ruleApplicationStrategy);
							const merged: EvolutionFormState = {
								...msg.state,
								ruleApplicationStrategy: normalizeRuleApplicationStrategy(
									msg.state.ruleApplicationStrategy ?? this._formState?.ruleApplicationStrategy
								),
								operations:             this._formState?.operations             ?? msg.state.operations            ?? [],
								checkpointCursor:       this._formState?.checkpointCursor       ?? msg.state.checkpointCursor      ?? null,
								workspaceBigraph:       msg.state.workspaceBigraph || this._formState?.workspaceBigraph || '',
								evolutionConfigRelPath: this._formState?.evolutionConfigRelPath ?? msg.state.evolutionConfigRelPath ?? null
							};
							const strategyChanged = prevStrategy !== merged.ruleApplicationStrategy;
							this._formState = merged;
							if (this._evolutionConfigPath && strategyChanged) {
								this._persistRuleApplicationStrategy(merged.ruleApplicationStrategy);
							}
						}
						break;
				case 'verificationBigraphsChanged': {
						const vbs = (msg as unknown as { verificationBigraphs: EvolutionFormState['verificationBigraphs'] }).verificationBigraphs;
						if (this._formState && Array.isArray(vbs)) {
							this._formState = { ...this._formState, verificationBigraphs: vbs };
						}
						if (Array.isArray(vbs)) {
							this._persistVerificationBigraphs(vbs).catch((err) =>
								console.error('[Evolution Manager] persist verificationBigraphs failed:', err)
							);
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
				case 'dndDebugLog': {
					const dbg = msg as unknown as { source?: string; event?: string; details?: unknown };
					logDnd(
						`webview.${dbg.source ?? 'unknown'}`,
						dbg.event ?? 'event',
						dbg.details
					);
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
				case 'deleteCheckpoint': {
					const req = msg as unknown as { operationId: string };
					this._deleteCheckpoint(req.operationId).catch((err) =>
						console.error('[Evolution Manager] deleteCheckpoint failed:', err)
					);
					break;
				}
				case 'deleteRewriteRule': {
					const req = msg as unknown as { ruleId: string };
					this._deleteRewriteRule(req.ruleId).catch((err) =>
						console.error('[Evolution Manager] deleteRewriteRule failed:', err)
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
		const { sendActionToServer } = await import('../editor/glsp/connector.js');
		const sent = sendActionToServer({
			kind: 'bigraph.verifyBigraph',
			verificationId: req.verificationId,
			verificationPath: req.verificationPath,
			checkpointPath: req.checkpointPath ?? null
		});
		if (!sent) {
			vscode.window.showErrorMessage('GLSP server not available.');
		}
	}

	private async _deleteCheckpoint(operationId: string): Promise<void> {
		try {
			const folderPath = this._evolutionConfigPath;
			if (!folderPath) { return; }
			if (!operationId) { return; }

			const evoJsonPath = path.join(folderPath, 'evolution.json');
			let json: Record<string, unknown>;
			try {
				json = JSON.parse(fs.readFileSync(evoJsonPath, 'utf8')) as Record<string, unknown>;
			} catch {
				vscode.window.showWarningMessage('Could not read evolution.json.');
				return;
			}

			const ops = Array.isArray(json['operations'])
				? (json['operations'] as Record<string, unknown>[])
				: [];
			const byId = new Map<string, Record<string, unknown>>();
			for (const op of ops) {
				const id = String(op['id'] ?? '');
				if (id) { byId.set(id, op); }
			}
			const target = byId.get(operationId);
			if (!target) { return; }

			// Compute subtree (successors) rooted at operationId.
			const children = new Map<string, string[]>();
			for (const op of ops) {
				const id = String(op['id'] ?? '');
				const pred = String(op['predecessor'] ?? '');
				if (!id || !pred) { continue; }
				if (!children.has(pred)) { children.set(pred, []); }
				children.get(pred)!.push(id);
			}

			const toDelete = new Set<string>();
			const q: string[] = [operationId];
			while (q.length) {
				const cur = q.shift()!;
				if (toDelete.has(cur)) { continue; }
				toDelete.add(cur);
				for (const c of children.get(cur) ?? []) { q.push(c); }
			}

			if (toDelete.size > 1) {
				const choice = await vscode.window.showWarningMessage(
					`Delete this checkpoint and ${toDelete.size - 1} successor operation(s)?`,
					{ modal: true },
					'Delete'
				);
				if (choice !== 'Delete') { return; }
			}

			// Delete checkpoint triplet files for every removed operation that has a `result`.
			for (const id of toDelete) {
				const op = byId.get(id);
				if (!op) { continue; }
				const rel = typeof op['result'] === 'string' ? op['result'] : '';
				if (!rel) { continue; }
				const absXmi = path.isAbsolute(rel) ? rel : path.join(folderPath, rel);
				const base = absXmi.replace(/\.xmi$/i, '');
				for (const ext of ['.xmi', '.signature.ecore', '.signature.xmi']) {
					const p = base + ext;
					try { if (fs.existsSync(p)) { fs.unlinkSync(p); } } catch { /* ignore */ }
				}
			}

			const filteredOps = ops.filter((o) => !toDelete.has(String(o['id'] ?? '')));
			const remainingIds = new Set(filteredOps.map((o) => String(o['id'] ?? '')).filter(Boolean));

			const predMap = new Map<string, string | null>();
			for (const op of ops) {
				const id = String(op['id'] ?? '');
				if (!id) { continue; }
				const raw = op['predecessor'];
				let p: string | null = null;
				if (raw !== null && raw !== undefined) {
					const s = String(raw);
					if (s !== '' && s !== 'null') { p = s; }
				}
				predMap.set(id, p);
			}

			let newCursor: string | null = typeof json['checkpoint-cursor'] === 'string' ? json['checkpoint-cursor'] : null;
			if (newCursor && toDelete.has(newCursor)) {
				let walk: string | null = newCursor;
				while (walk !== null && toDelete.has(walk)) {
					walk = predMap.get(walk) ?? null;
				}
				newCursor = walk !== null && remainingIds.has(walk) ? walk : null;
			} else if (newCursor && !remainingIds.has(newCursor)) {
				newCursor = null;
			}

			json['operations'] = filteredOps;
			json['checkpoint-cursor'] = newCursor;

			try {
				fs.writeFileSync(evoJsonPath, JSON.stringify(json, null, 2) + '\n', 'utf8');
			} catch {
				vscode.window.showWarningMessage('Could not write evolution.json.');
				return;
			}

			refreshTreeFromJson(this);
			await syncWorkspaceBigraphToCursor(this, { force: true });
		} catch {
			vscode.window.showWarningMessage('Could not delete checkpoint.');
		}
	}

	private async _deleteRewriteRule(ruleId: string): Promise<void> {
		try {
			if (!ruleId) { return; }
			const folderPath = this._evolutionConfigPath;
			if (!folderPath) { return; }

			const evoJsonPath = path.join(folderPath, 'evolution.json');
			let json: Record<string, unknown>;
			try {
				json = JSON.parse(fs.readFileSync(evoJsonPath, 'utf8')) as Record<string, unknown>;
			} catch {
				vscode.window.showWarningMessage('Could not read evolution.json.');
				return;
			}

			const ops = Array.isArray(json['operations'])
				? (json['operations'] as Record<string, unknown>[])
				: [];
			const isReferenced = ops.some((op) => String(op['rule'] ?? '') === ruleId);
			if (isReferenced) {
				vscode.window.showWarningMessage('Cannot delete rewrite rule because it is referenced by an operation.');
				return;
			}

			const rules = Array.isArray(json['rules'])
				? (json['rules'] as Record<string, unknown>[])
				: [];
			const idx = rules.findIndex((r) => String(r['id'] ?? '') === ruleId);
			if (idx < 0) { return; }

			const removed = rules.splice(idx, 1)[0] ?? {};
			json['rules'] = rules;

			const cleanupRelPath = (rel: unknown): void => {
				const relPath = typeof rel === 'string' ? rel : '';
				if (!relPath) { return; }
				const absXmi = path.isAbsolute(relPath) ? relPath : path.join(folderPath, relPath);
				const base = absXmi.replace(/\.xmi$/i, '');
				for (const ext of ['.xmi', '.signature.ecore', '.signature.xmi']) {
					const p = base + ext;
					try { if (fs.existsSync(p)) { fs.unlinkSync(p); } } catch { /* ignore */ }
				}
			};

			cleanupRelPath(removed['redex']);
			cleanupRelPath(removed['reactum']);

			try {
				fs.writeFileSync(evoJsonPath, JSON.stringify(json, null, 2) + '\n', 'utf8');
			} catch {
				vscode.window.showWarningMessage('Could not write evolution.json.');
				return;
			}

			if (this._formState) {
				const newRules = this._formState.rewriteRules.filter((r) => r.id !== ruleId);
				this._formState = { ...this._formState, rewriteRules: newRules };
				const bigraphRelativePath = this._formState.bigraphFsPath
					? vscode.workspace.asRelativePath(this._formState.bigraphFsPath)
					: '';
				this.postMessage({
					type: 'restoreFormState',
					state: {
						...this._formState,
						bigraphRelativePath
					}
				});
			}
		} catch {
			vscode.window.showWarningMessage('Could not delete rewrite rule.');
		}
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

			// History: each operation may list verification checks; keep only checks that still
			// reference existing top-level verification entries.
			const remainingVerificationIds = new Set(
				verificationArr
					.map((v) => String(v['id'] ?? ''))
					.filter((id) => id.length > 0)
			);
			const ops = Array.isArray(json['operations'])
				? (json['operations'] as Record<string, unknown>[])
				: [];
			for (const op of ops) {
				const verList = Array.isArray(op['verification'])
					? (op['verification'] as Record<string, unknown>[])
					: [];
				const filtered = verList.filter((entry) =>
					remainingVerificationIds.has(String(entry['id'] ?? ''))
				);
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
				const bigraphRelativePath = this._formState.bigraphFsPath
					? vscode.workspace.asRelativePath(this._formState.bigraphFsPath)
					: '';
				this.postMessage({
					type: 'restoreFormState',
					state: {
						...this._formState,
						bigraphRelativePath
					}
				});
			} else {
				this.postMessage({ type: 'fillEvolutionForm', verificationBigraphs: [] });
			}
		} catch {
			// Broad catch: file/JSON edge cases must not take down the extension host
			vscode.window.showWarningMessage('Could not delete verification rule.');
		}
	}

	private _normalizeRel(relPath: string): string {
		return relPath.replace(/\\/g, '/');
	}

	private _copyBigraphTripletIntoDir(sourceXmiPath: string, destDir: string): string {
		const ext = path.extname(sourceXmiPath);
		const base = path.basename(sourceXmiPath, ext);
		const srcDir = path.dirname(sourceXmiPath);

		let destBase = base;
		let counter = 1;
		while (
			fs.existsSync(path.join(destDir, `${destBase}${ext}`)) ||
			fs.existsSync(path.join(destDir, `${destBase}.signature.ecore`)) ||
			fs.existsSync(path.join(destDir, `${destBase}.signature.xmi`))
		) {
			destBase = `${base}${counter}`;
			counter++;
		}

		fs.copyFileSync(sourceXmiPath, path.join(destDir, `${destBase}${ext}`));
		const ecoreSrc = path.join(srcDir, `${base}.signature.ecore`);
		const xmiSigSrc = path.join(srcDir, `${base}.signature.xmi`);
		if (fs.existsSync(ecoreSrc)) {
			fs.copyFileSync(ecoreSrc, path.join(destDir, `${destBase}.signature.ecore`));
		}
		if (fs.existsSync(xmiSigSrc)) {
			fs.copyFileSync(xmiSigSrc, path.join(destDir, `${destBase}.signature.xmi`));
		}
		return destBase;
	}

	private _tripletFilesEqual(baseA: string, baseB: string): boolean {
		for (const ext of ['.xmi', '.signature.ecore', '.signature.xmi']) {
			const a = `${baseA}${ext}`;
			const b = `${baseB}${ext}`;
			const aExists = fs.existsSync(a);
			const bExists = fs.existsSync(b);
			if (aExists !== bExists) {
				return false;
			}
			if (!aExists) {
				continue;
			}
			const aBuf = fs.readFileSync(a);
			const bBuf = fs.readFileSync(b);
			if (!aBuf.equals(bBuf)) {
				return false;
			}
		}
		return true;
	}

	private async _persistVerificationBigraphs(
		verificationBigraphs: EvolutionFormState['verificationBigraphs']
	): Promise<void> {
		const folderPath = this._evolutionConfigPath;
		if (!folderPath) {
			return;
		}

		const evoJsonPath = path.join(folderPath, 'evolution.json');
		let json: Record<string, unknown>;
		try {
			json = JSON.parse(fs.readFileSync(evoJsonPath, 'utf8')) as Record<string, unknown>;
		} catch {
			vscode.window.showWarningMessage('Could not read evolution.json.');
			return;
		}

		const verificationDir = path.join(folderPath, 'verification');
		try {
			fs.mkdirSync(verificationDir, { recursive: true });
		} catch {
			vscode.window.showWarningMessage('Could not prepare verification folder.');
			return;
		}

		const existingVerification = Array.isArray(json['verification'])
			? (json['verification'] as Record<string, unknown>[])
			: [];
		const existingById = new Map<string, Record<string, unknown>>();
		for (const entry of existingVerification) {
			const id = String(entry['id'] ?? '');
			if (id) {
				existingById.set(id, entry);
			}
		}

		const wsRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
		const nextVerification: Array<{ id: string; label: string; path: string; stop: boolean }> = [];
		const nextWebviewVbs: EvolutionFormState['verificationBigraphs'] = [];
		for (const vb of verificationBigraphs) {
			const id = String(vb.id ?? '');
			if (!id) {
				continue;
			}
			const label = String(vb.label ?? '');
			const stop = vb.stop !== false;
			const sourcePath = String(vb.path ?? '');
			if (!sourcePath) {
				continue;
			}

			let sourceAbs = sourcePath;
			if (!path.isAbsolute(sourceAbs)) {
				sourceAbs = wsRoot ? path.join(wsRoot, sourceAbs) : sourceAbs;
			}
			if (!fs.existsSync(sourceAbs)) {
				continue;
			}

			const sourceBase = sourceAbs.replace(/\.xmi$/i, '');
			let isDuplicate = false;
			for (const kept of nextVerification) {
				const keptAbsXmi = path.join(folderPath, kept.path);
				const keptBase = keptAbsXmi.replace(/\.xmi$/i, '');
				if (this._tripletFilesEqual(sourceBase, keptBase)) {
					isDuplicate = true;
					break;
				}
			}
			if (isDuplicate) {
				continue;
			}

			let relPath = '';
			const existing = existingById.get(id);
			const existingRelPath = existing && typeof existing['path'] === 'string'
				? this._normalizeRel(existing['path'])
				: '';
			const insideEvolution = path.relative(folderPath, sourceAbs);
			if (insideEvolution && !insideEvolution.startsWith('..') && !path.isAbsolute(insideEvolution)) {
				relPath = this._normalizeRel(insideEvolution);
			} else if (existingRelPath && path.join(folderPath, existingRelPath) === sourceAbs) {
				relPath = existingRelPath;
			} else {
				const destBase = this._copyBigraphTripletIntoDir(sourceAbs, verificationDir);
				relPath = `verification/${destBase}.xmi`;
			}

			nextVerification.push({
				id,
				label,
				path: relPath,
				stop
			});
			nextWebviewVbs.push({
				id,
				label,
				path: path.join(folderPath, relPath),
				stop
			});
		}

		json['verification'] = nextVerification;
		try {
			fs.writeFileSync(evoJsonPath, JSON.stringify(json, null, 2) + '\n', 'utf8');
		} catch {
			vscode.window.showWarningMessage('Could not write evolution.json.');
			return;
		}

		if (this._formState) {
			const hadFilteredOut = nextWebviewVbs.length !== verificationBigraphs.length;
			if (hadFilteredOut) {
				this._formState = { ...this._formState, verificationBigraphs: nextWebviewVbs };
				const bigraphRelativePath = this._formState.bigraphFsPath
					? vscode.workspace.asRelativePath(this._formState.bigraphFsPath)
					: '';
				this.postMessage({
					type: 'restoreFormState',
					state: {
						...this._formState,
						bigraphRelativePath
					}
				});
			}
		}
	}
}
