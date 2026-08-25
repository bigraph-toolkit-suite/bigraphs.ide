import type { EvoState } from './EvoState.js';
import type { EvoRules } from './EvoRules.js';
import type { EvoVerification } from './EvoVerification.js';
import type { EvoTree } from './EvoTree.js';
import type { EvoForm } from './EvoForm.js';
import type { ModeController } from './modes/ModeController.js';
import type { VsCodeApi, VerificationBigraph, RewriteRule, EvolutionOperation } from './types.js';

export class EvoMessages {
	private readonly state:    EvoState;
	private readonly rules:    EvoRules;
	private readonly verif:    EvoVerification;
	private readonly tree:     EvoTree;
	private readonly form:     EvoForm;
	private readonly modes:    ModeController;
	private readonly vscode:   VsCodeApi;

	constructor(
		state:   EvoState,
		rules:   EvoRules,
		verif:   EvoVerification,
		tree:    EvoTree,
		form:    EvoForm,
		modes:   ModeController,
		vscode:  VsCodeApi,
	) {
		this.state  = state;
		this.rules  = rules;
		this.verif  = verif;
		this.tree   = tree;
		this.form   = form;
		this.modes  = modes;
		this.vscode = vscode;
	}

	init(): void {
		window.addEventListener('message', (e: MessageEvent) => this.handle(e.data as Record<string, unknown>));
	}

	// ── Dispatch ──────────────────────────────────────────────────────────────

	private handle(msg: Record<string, unknown>): void {
		const st = this.state;

		switch (msg.type) {

			case 'activeTabChanged': {
				st.activeEditorTabFsPath = (msg.fsPath as string | null) ?? null;
				st.activeClientId = (msg.clientId as string | null) ?? null;
				if (!st.bigraphLockedFromConfig) {
					st.activeFsPath = (msg.fsPath as string | null) ?? null;
					const pathEl = document.getElementById('bigraphPath') as HTMLInputElement | null;
					if (pathEl) {
						if (msg.relativePath) {
							pathEl.value = msg.relativePath as string;
						} else {
							pathEl.value       = '';
							pathEl.placeholder = 'Open an .xmi tab to auto-fill';
						}
					}
				}
				this.syncTreeCursorHighlight(st);
				break;
			}

			case 'verificationCheckResult': {
				const vid = msg.verificationId as string | undefined;
				if (typeof vid === 'string') {
					st.verificationCheckStates[vid] = msg.matched === true;
				}
				this.verif.render(st.lastVerificationBigraphs);
				break;
			}

		case 'addVerificationBigraph': {
			const fsPath1 = msg.fsPath as string | undefined;
			if (!fsPath1) { break; }
				const bn1  = fsPath1.replace(/\\/g, '/').split('/').pop() ?? '';
				const lbl1 = (msg.label as string | undefined) || bn1.replace(/\.xmi$/i, '');
				const id1  = `vb-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
				st.lastVerificationBigraphs.push({ id: id1, label: lbl1, path: fsPath1, stop: true });
				this.verif.render(st.lastVerificationBigraphs);
				this.vscode.postMessage({ type: 'verificationBigraphsChanged', verificationBigraphs: st.lastVerificationBigraphs });
				break;
			}

			case 'addVerificationBigraphs': {
				const files = Array.isArray(msg.files) ? (msg.files as Array<{ fsPath?: string; label?: string }>) : [];
				files.forEach((f) => {
					if (!f.fsPath) { return; }
					const bn2  = f.fsPath.replace(/\\/g, '/').split('/').pop() ?? '';
					const lbl2 = f.label || bn2.replace(/\.xmi$/i, '');
					const id2  = `vb-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
					st.lastVerificationBigraphs.push({ id: id2, label: lbl2, path: f.fsPath, stop: true });
				});
				if (files.length > 0) {
					this.verif.render(st.lastVerificationBigraphs);
					this.vscode.postMessage({ type: 'verificationBigraphsChanged', verificationBigraphs: st.lastVerificationBigraphs });
				}
				break;
			}

			case 'operationStarted': {
				const opId = msg.operationId as string | undefined;
				if (opId) {
					st.currentOperationId = opId;
					if (msg.runFolder) { st.operationMap[opId] = msg.runFolder as string; }
				}
				this.form.setRunningState(true);
				this.rules.setEvolutionRunning(true);
				this.tree.expand();
				break;
			}

			case 'operationFinished': {
				if (msg.operationId === st.currentOperationId) {
					st.currentOperationId = null;
				}
				this.form.setRunningState(false);
				this.rules.setEvolutionRunning(false);
				this.tree.expand();
				const results = msg.extensionResults;
				this.modes.notifyRunFinished(
					results && typeof results === 'object' ? results as Record<string, unknown> : null
				);
				this.verif.render(st.lastVerificationBigraphs);
				break;
			}

			case 'evolutionFolderSelected': {
				if (msg.folderPath) {
					st.hasEvolutionJson = true;
					this.form.showEvolutionForm(true);
					this.form.setConfigPathBar((msg.configRelPath as string | null) ?? null);
				} else {
					st.hasEvolutionJson = false;
					st.referencedRewriteRuleIds = new Set<string>();
					this.form.showEvolutionForm(false);
					this.form.setConfigPathBar(null);
					this.tree.update([], null, true);
				}
				break;
			}

			case 'setConfigPath': {
				this.form.setConfigPathBar((msg.configRelPath as string | null) ?? null);
				st.hasEvolutionJson = !!msg.configRelPath;
				if (msg.workspaceBigraph) {
					st.currentWorkspaceBigraph = msg.workspaceBigraph as string;
					this.form.reportFormState();
				}
				break;
			}

			case 'setCursor': {
				st.currentCursorOperationId = (msg.cursorId as string | null) ?? null;
				this.tree.setCursor(st.currentCursorOperationId);
				this.syncTreeCursorHighlight(st);
				break;
			}

			case 'updateTree': {
				const ops1     = Array.isArray(msg.operations) ? (msg.operations as EvolutionOperation[]) : [];
				st.setReferencedRewriteRuleIds(ops1);
				const cursor1  = (msg.checkpointCursor as string | null) ?? null;
				const lastOpId = ops1.length > 0 ? ops1[ops1.length - 1].id : null;
				const live     = this.form.isRunning();
				// Prefer the persisted cursor so a replay can walk an existing path.
				// Fall back to the newest appended op while a tree is still growing
				// and the backend has not written the cursor yet.
				const liveHead = live ? (cursor1 ?? lastOpId) : cursor1;
				this.tree.update(ops1, liveHead, false, { preserveView: live });
				this.rules.render(st.lastRewriteRules);
				st.currentCursorOperationId = liveHead;
				const curOp1 = ops1.find((o) => o.id === liveHead) ?? null;
				this.verif.applyStatesFromOp(curOp1);
				this.syncTreeCursorHighlight(st);
				break;
			}

			case 'fillEvolutionForm': {
				const bigraphPathEl = document.getElementById('bigraphPath') as HTMLInputElement | null;
				st.hasEvolutionJson = !!msg.evolutionConfigRelPath;
				this.tree.collapse();

				if (typeof msg.evolutionLabel === 'string') {
					this.form.setEvolutionLabel(msg.evolutionLabel);
				}
				if (msg.bigraphFsPath) {
					st.activeFsPath            = msg.bigraphFsPath as string;
					st.bigraphLockedFromConfig = true;
					if (bigraphPathEl) {
						bigraphPathEl.value = (msg.bigraphRelativePath as string | undefined) || (msg.bigraphFsPath as string);
					}
				} else {
					st.bigraphLockedFromConfig = false;
					st.activeFsPath            = null;
					st.currentWorkspaceBigraph = '';
					if (bigraphPathEl) { bigraphPathEl.value = ''; }
				}
				if (typeof msg.workspaceBigraph === 'string') {
					st.currentWorkspaceBigraph = msg.workspaceBigraph;
				}
				if (Array.isArray(msg.rewriteRules)) {
					st.lastRewriteRules = (msg.rewriteRules as RewriteRule[]).map((r) => ({ ...r, active: r.active !== false }));
				}
				if (Array.isArray(msg.verificationBigraphs)) {
					st.lastVerificationBigraphs = (msg.verificationBigraphs as VerificationBigraph[]).map((v) => ({ ...v, stop: v.stop !== false }));
				}
				if (typeof msg.ruleApplicationStrategy === 'string') {
					this.form.setRuleApplicationStrategy(msg.ruleApplicationStrategy);
				}
				st.extensionJson = (msg.extensionJson && typeof msg.extensionJson === 'object')
					? msg.extensionJson as Record<string, unknown>
					: {};
				this.modes.notifyDocumentLoaded();
				if (Array.isArray(msg.operations)) {
					const ops2   = msg.operations as EvolutionOperation[];
					st.setReferencedRewriteRuleIds(ops2);
					const cursor2 = (msg.checkpointCursor as string | null) ?? null;
					this.tree.update(ops2, cursor2);
					st.currentCursorOperationId = cursor2;
					const curOp2 = ops2.find((o) => o.id === cursor2) ?? null;
					this.verif.applyStatesFromOp(curOp2);
				} else {
					st.referencedRewriteRuleIds = new Set<string>();
					const cursorEmpty = (msg.checkpointCursor as string | null) ?? null;
					this.tree.update([], cursorEmpty);
					st.currentCursorOperationId = cursorEmpty;
					this.verif.applyStatesFromOp(null);
				}
				this.verif.render(st.lastVerificationBigraphs);
				this.form.showEvolutionForm(true);
				this.form.setConfigPathBar((msg.evolutionConfigRelPath as string | null) ?? null);
				this.rules.render(st.lastRewriteRules);
				this.form.reportFormState();
				this.syncTreeCursorHighlight(st);
				break;
			}

			case 'appendRewriteRulesToForm': {
				const raw = Array.isArray(msg.rewriteRules) ? msg.rewriteRules : [];
				const rules: { label: string; redexPath: string; reactumPath: string }[] = [];
				for (const item of raw) {
					if (!item || typeof item !== 'object') {
						continue;
					}
					const r = item as Record<string, unknown>;
					if (
						typeof r.label === 'string' &&
						typeof r.redexPath === 'string' &&
						typeof r.reactumPath === 'string'
					) {
						rules.push({ label: r.label, redexPath: r.redexPath, reactumPath: r.reactumPath });
					}
				}
				if (rules.length === 0) {
					break;
				}
				this.form.showEvolutionForm(true);
				this.rules.appendRewriteRules(rules);
				break;
			}

			case 'restoreFormState': {
				const s = msg.state as Record<string, unknown> | undefined;
				if (!s) { break; }
				st.hasEvolutionJson = !!s.evolutionConfigRelPath;
				this.tree.collapse();

				this.form.showEvolutionForm(true);

				if (typeof s.evolutionLabel === 'string')          { this.form.setEvolutionLabel(s.evolutionLabel); }
				if (typeof s.bigraphFsPath === 'string' && s.bigraphFsPath) {
					st.activeFsPath            = s.bigraphFsPath;
					st.bigraphLockedFromConfig = !!s.bigraphLockedFromConfig;
					const bpEl = document.getElementById('bigraphPath') as HTMLInputElement | null;
					if (bpEl) { bpEl.value = (s.bigraphRelativePath as string | undefined) ?? s.bigraphFsPath; }
				} else if (s.bigraphFsPath === '') {
					st.bigraphLockedFromConfig = false;
					st.activeFsPath = null;
					st.currentWorkspaceBigraph = '';
					const bpEl = document.getElementById('bigraphPath') as HTMLInputElement | null;
					if (bpEl) { bpEl.value = ''; }
				}
				if (typeof s.maxOperationsEnabled === 'boolean') { this.form.setMaxOpsEnabled(s.maxOperationsEnabled); }
				if (typeof s.maxOperations === 'number')          { this.form.setMaxOps(s.maxOperations); }
				if (typeof s.checkpointFileGeneration === 'boolean') { this.form.setCheckpointFileGen(s.checkpointFileGeneration); }
				if (typeof s.ruleApplicationStrategy === 'string') { this.form.setRuleApplicationStrategy(s.ruleApplicationStrategy); }
				if (Array.isArray(s.rewriteRules)) {
					st.lastRewriteRules = s.rewriteRules as RewriteRule[];
				}
				if (Array.isArray(s.verificationBigraphs)) {
					st.lastVerificationBigraphs = s.verificationBigraphs as VerificationBigraph[];
				}
				st.extensionJson = (s.extensionJson && typeof s.extensionJson === 'object')
					? s.extensionJson as Record<string, unknown>
					: {};
				this.modes.notifyDocumentLoaded();
				if (Array.isArray(s.operations)) {
					const rOps    = s.operations as EvolutionOperation[];
					st.setReferencedRewriteRuleIds(rOps);
					const rCursor = (s.checkpointCursor as string | null) ?? null;
					this.tree.update(rOps, rCursor);
					st.currentCursorOperationId = rCursor;
					const rCurOp = rOps.find((o) => o.id === rCursor) ?? null;
					this.verif.applyStatesFromOp(rCurOp);
				} else {
					st.referencedRewriteRuleIds = new Set<string>();
					const rCursorEmpty = (s.checkpointCursor as string | null) ?? null;
					this.tree.update([], rCursorEmpty);
					st.currentCursorOperationId = rCursorEmpty;
					this.verif.applyStatesFromOp(null);
				}
				this.verif.render(st.lastVerificationBigraphs);
				if (typeof s.workspaceBigraph === 'string') {
					st.currentWorkspaceBigraph = s.workspaceBigraph;
				}
				this.form.setConfigPathBar((s.evolutionConfigRelPath as string | null) ?? null);
				this.rules.render(st.lastRewriteRules);
				this.form.reportFormState();
				this.syncTreeCursorHighlight(st);
				break;
			}

			default:
				break;
		}
	}

	private syncTreeCursorHighlight(st: EvoState): void {
		const ws = st.currentWorkspaceBigraph;
		const active = st.activeEditorTabFsPath;
		const wsActive = !!(ws && active && ws === active);
		this.tree.setHighlightCursor(wsActive ? st.currentCursorOperationId : null);
	}
}
