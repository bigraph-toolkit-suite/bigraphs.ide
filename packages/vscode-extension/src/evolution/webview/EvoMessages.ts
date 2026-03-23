import type { EvoState } from './EvoState.js';
import type { EvoHistory } from './EvoHistory.js';
import type { EvoRules } from './EvoRules.js';
import type { EvoVerification } from './EvoVerification.js';
import type { EvoTree } from './EvoTree.js';
import type { EvoForm } from './EvoForm.js';
import type { VsCodeApi, VerificationBigraph, RewriteRule, EvolutionOperation } from './types.js';

export class EvoMessages {
	private readonly state:    EvoState;
	private readonly history:  EvoHistory;
	private readonly rules:    EvoRules;
	private readonly verif:    EvoVerification;
	private readonly tree:     EvoTree;
	private readonly form:     EvoForm;
	private readonly vscode:   VsCodeApi;

	constructor(
		state:   EvoState,
		history: EvoHistory,
		rules:   EvoRules,
		verif:   EvoVerification,
		tree:    EvoTree,
		form:    EvoForm,
		vscode:  VsCodeApi,
	) {
		this.state   = state;
		this.history = history;
		this.rules   = rules;
		this.verif   = verif;
		this.tree    = tree;
		this.form    = form;
		this.vscode  = vscode;
	}

	init(): void {
		window.addEventListener('message', (e: MessageEvent) => this.handle(e.data as Record<string, unknown>));
	}

	// ── Dispatch ──────────────────────────────────────────────────────────────

	private handle(msg: Record<string, unknown>): void {
		const st = this.state;

		switch (msg.type) {

			case 'activeTabChanged': {
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

		case 'bigraphDragStarted': {
			const files = Array.isArray(msg.files) ? (msg.files as Array<{ fsPath?: string; label?: string; relativePath?: string }>) : [];
			if (files.length > 0 && files[0].fsPath) {
				st.latestDragged      = files[0] as { fsPath: string; label?: string; relativePath?: string };
				st.awaitingDropTarget = true;
				this.verif.notifyDragStarted();
			}
			break;
		}

		case 'pendingDragFiles': {
			// Reply from extension host to a 'queryPendingDrag' request
			const pendingFiles = Array.isArray(msg.files) ? (msg.files as Array<{ fsPath?: string; label?: string }>) : [];
			const valid = pendingFiles.filter((f): f is { fsPath: string; label?: string } => typeof f.fsPath === 'string');
			if (valid.length > 0) {
				this.verif.onPendingDragFiles(valid);
			}
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
				break;
			}

			case 'operationFinished': {
				if (msg.operationId === st.currentOperationId) {
					st.currentOperationId = null;
				}
				this.form.setRunningState(false);
				break;
			}

			case 'evolutionFolderSelected': {
				if (msg.folderPath) {
					this.form.showEvolutionForm(true);
					this.form.setConfigPathBar((msg.configRelPath as string | null) ?? null);
				} else {
					this.form.showEvolutionForm(false);
					this.form.setConfigPathBar(null);
					this.tree.update([], null);
				}
				break;
			}

			case 'setConfigPath': {
				this.form.setConfigPathBar((msg.configRelPath as string | null) ?? null);
				if (msg.workspaceBigraph) {
					st.currentWorkspaceBigraph = msg.workspaceBigraph as string;
					this.form.reportFormState();
				}
				break;
			}

			case 'setCursor': {
				st.currentCursorOperationId = (msg.cursorId as string | null) ?? null;
				this.tree.setCursor(st.currentCursorOperationId);
				break;
			}

			case 'updateTree': {
				const ops1     = Array.isArray(msg.operations) ? (msg.operations as EvolutionOperation[]) : [];
				const cursor1  = (msg.checkpointCursor as string | null) ?? null;
				this.history.render(ops1);
				this.tree.update(ops1, cursor1);
				st.currentCursorOperationId = cursor1;
				const curOp1 = ops1.find((o) => o.id === cursor1) ?? null;
				this.verif.applyStatesFromOp(curOp1);
				break;
			}

			case 'fillEvolutionForm': {
				const bigraphPathEl = document.getElementById('bigraphPath') as HTMLInputElement | null;

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
					st.lastRewriteRules = (msg.rewriteRules as RewriteRule[]).map((r) => ({ active: true, ...r }));
					this.rules.render(st.lastRewriteRules);
				}
				if (Array.isArray(msg.verificationBigraphs)) {
					st.lastVerificationBigraphs = (msg.verificationBigraphs as VerificationBigraph[]).map((v) => ({ stop: true, ...v }));
				}
				if (Array.isArray(msg.operations)) {
					const ops2   = msg.operations as EvolutionOperation[];
					const cursor2 = (msg.checkpointCursor as string | null) ?? null;
					this.history.render(ops2);
					this.tree.update(ops2, cursor2);
					st.currentCursorOperationId = cursor2;
					const curOp2 = ops2.find((o) => o.id === cursor2) ?? null;
					this.verif.applyStatesFromOp(curOp2);
				}
				this.verif.render(st.lastVerificationBigraphs);
				this.form.showEvolutionForm(true);
				this.form.setConfigPathBar((msg.evolutionConfigRelPath as string | null) ?? null);
				this.form.reportFormState();
				break;
			}

			case 'restoreFormState': {
				const s = msg.state as Record<string, unknown> | undefined;
				if (!s) { break; }

				this.form.showEvolutionForm(true);

				if (typeof s.evolutionLabel === 'string')          { this.form.setEvolutionLabel(s.evolutionLabel); }
				if (typeof s.bigraphFsPath === 'string' && s.bigraphFsPath) {
					st.activeFsPath            = s.bigraphFsPath;
					st.bigraphLockedFromConfig = !!s.bigraphLockedFromConfig;
					const bpEl = document.getElementById('bigraphPath') as HTMLInputElement | null;
					if (bpEl) { bpEl.value = (s.bigraphRelativePath as string | undefined) ?? s.bigraphFsPath; }
				}
				if (typeof s.maxOperationsEnabled === 'boolean') { this.form.setMaxOpsEnabled(s.maxOperationsEnabled); }
				if (typeof s.maxOperations === 'number')          { this.form.setMaxOps(s.maxOperations); }
				if (typeof s.checkpointFileGeneration === 'boolean') { this.form.setCheckpointFileGen(s.checkpointFileGeneration); }
				if (typeof s.visualizeIntermediateSteps === 'boolean') { this.form.setVisualizeSteps(s.visualizeIntermediateSteps); }
				if (Array.isArray(s.rewriteRules)) {
					st.lastRewriteRules = s.rewriteRules as RewriteRule[];
					this.rules.render(st.lastRewriteRules);
				}
				if (Array.isArray(s.verificationBigraphs)) {
					st.lastVerificationBigraphs = s.verificationBigraphs as VerificationBigraph[];
				}
				if (Array.isArray(s.operations)) {
					const rOps    = s.operations as EvolutionOperation[];
					const rCursor = (s.checkpointCursor as string | null) ?? null;
					this.history.render(rOps);
					this.tree.update(rOps, rCursor);
					st.currentCursorOperationId = rCursor;
					const rCurOp = rOps.find((o) => o.id === rCursor) ?? null;
					this.verif.applyStatesFromOp(rCurOp);
				}
				this.verif.render(st.lastVerificationBigraphs);
				if (typeof s.workspaceBigraph === 'string') {
					st.currentWorkspaceBigraph = s.workspaceBigraph;
				}
				this.form.setConfigPathBar((s.evolutionConfigRelPath as string | null) ?? null);
				break;
			}

			default:
				break;
		}
	}
}
