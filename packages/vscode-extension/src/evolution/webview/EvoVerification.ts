import { EvoState } from './EvoState.js';
import type { EvolutionOperation, VerificationBigraph, VsCodeApi } from './types.js';

const CHECK_SVG = `<svg viewBox="0 0 16 16"><path d="M13.78 4.22a.75.75 0 0 1 0 1.06l-7.25 7.25a.75.75 0 0 1-1.06 0L2.22 9.28a.75.75 0 0 1 1.06-1.06L6 10.94l6.72-6.72a.75.75 0 0 1 1.06 0z"/></svg>`;
const CROSS_SVG = `<svg viewBox="0 0 16 16"><path d="M3.72 3.72a.75.75 0 0 1 1.06 0L8 6.94l3.22-3.22a.75.75 0 1 1 1.06 1.06L9.06 8l3.22 3.22a.75.75 0 1 1-1.06 1.06L8 9.06l-3.22 3.22a.75.75 0 0 1-1.06-1.06L6.94 8 3.72 4.78a.75.75 0 0 1 0-1.06z"/></svg>`;
const TRASH_SVG = `<svg viewBox="0 0 16 16"><path d="M6 2h4l1 1h3v1H2V3h3l1-1zm-2 4h1v8H4V6zm3 0h1v8H7V6zm3 0h1v8h-1V6z"/></svg>`;
const DROP_HINT = `<div class="verification-drop-hint">Drop bigraphs from the explorer here\u2026</div>`;

export class EvoVerification {
	private readonly listEl: HTMLElement;
	private readonly state: EvoState;
	private readonly vscode: VsCodeApi;

	constructor(state: EvoState, vscode: VsCodeApi) {
		this.state  = state;
		this.vscode = vscode;
		this.listEl = document.getElementById('verificationList')!;
		this.initDragDrop();
	}

	// ── Public API ──────────────────────────────────────────────────────────

	render(items: VerificationBigraph[]): void {
		if (items.length === 0) {
			this.listEl.innerHTML = DROP_HINT;
			return;
		}
		this.listEl.innerHTML = items.map((v, i) => this.renderItem(v, i)).join('');
		this.bindItemEvents();
	}

	applyStatesFromOp(op: EvolutionOperation | null): void {
		this.state.applyCheckStatesFromOperation(op);
		this.render(this.state.lastVerificationBigraphs);
	}

	// ── Rendering ───────────────────────────────────────────────────────────

	private renderItem(v: VerificationBigraph, i: number): string {
		const label      = v.label ?? '';
		const isStop     = v.stop !== false;
		const tagClass   = `rule-tag${isStop ? ' active' : ''}`;
		const checkState = (v.id && v.id in this.state.verificationCheckStates)
			? this.state.verificationCheckStates[v.id]
			: null;

		let stateIcon = `<span class="vb-state-icon${checkState === true ? ' matched' : checkState === false ? ' unmatched' : ''}" data-vb-idx="${i}">`;
		if (checkState === true)       { stateIcon += CHECK_SVG; }
		else if (checkState === false) { stateIcon += CROSS_SVG; }
		stateIcon += '</span>';

		const checkOrIcon = checkState === null
			? `<button class="vb-check-btn" data-vb-idx="${i}" title="Check if current bigraph matches this verification bigraph">Check</button>`
			: stateIcon;

		return `<div class="item" data-vb-idx="${i}">`
			+ `<span class="verification-item-name">`
			+   `<span class="verification-label" data-vb-idx="${i}">${EvoState.escapeHtml(label)}</span>`
			+   `<button class="verification-del-btn" title="Delete" data-vb-idx="${i}">${TRASH_SVG}</button>`
			+ `</span>`
			+ checkOrIcon
			+ `<span class="${tagClass}" data-vb-idx="${i}">stop</span>`
			+ `</div>`;
	}

	// ── Event binding ────────────────────────────────────────────────────────

	private bindItemEvents(): void {
		this.listEl.querySelectorAll<HTMLElement>('.rule-tag').forEach((tag) => {
			tag.addEventListener('click', () => {
				const idx = this.parseIdx(tag);
				if (idx < 0) { return; }
				const vb = this.state.lastVerificationBigraphs[idx];
				this.state.lastVerificationBigraphs[idx] = { ...vb, stop: vb.stop === false };
				tag.classList.toggle('active', this.state.lastVerificationBigraphs[idx].stop !== false);
				this.reportVerificationBigraphs();
			});
		});

		this.listEl.querySelectorAll<HTMLButtonElement>('.vb-check-btn').forEach((btn) => {
			btn.addEventListener('click', () => {
				const idx = this.parseIdx(btn);
				if (idx < 0) { return; }
				const vb = this.state.lastVerificationBigraphs[idx];
				if (!vb?.path) { return; }
				btn.disabled    = true;
				btn.textContent = '…';
				this.vscode.postMessage({
					type: 'verifyBigraph',
					verificationId: vb.id,
					verificationPath: vb.path,
					operationId: this.state.currentCursorOperationId ?? null,
				});
			});
		});

		this.listEl.querySelectorAll<HTMLButtonElement>('.verification-del-btn').forEach((btn) => {
			btn.addEventListener('click', (e) => {
				e.stopPropagation();
				const idx = this.parseIdx(btn);
				if (idx < 0) { return; }
				const vb = this.state.lastVerificationBigraphs[idx];
				if (!vb?.id) { return; }
				// Optimistic UI update: remove immediately so the UI stays responsive even if
				// the host-side cleanup fails or reload messages arrive later.
				this.state.lastVerificationBigraphs.splice(idx, 1);
				this.render(this.state.lastVerificationBigraphs);
				this.reportVerificationBigraphs();
				this.vscode.postMessage({ type: 'deleteVerificationBigraph', verificationId: vb.id });
			});
		});
	}

	// ── Drag-and-drop ────────────────────────────────────────────────────────

	private initDragDrop(): void {
		this.listEl.addEventListener('dragover', (e) => {
			e.preventDefault();
			if (e.dataTransfer) { e.dataTransfer.dropEffect = 'copy'; }
			this.listEl.classList.add('drag-over');
		});
		this.listEl.addEventListener('dragleave', () => {
			this.listEl.classList.remove('drag-over');
		});
		// Native drop (works with Shift held in VS Code webviews)
		this.listEl.addEventListener('drop', (e) => {
			e.preventDefault();
			this.tryConsume();
		});
		// When the pointer enters or releases over the zone, ask the extension host
		// if there's a pending drag stash. The host replies with 'pendingDragFiles'.
		// This pull-model is delay-free because the stash is set synchronously in
		// handleDrag() — no IPC round-trip needed for the write side.
		this.listEl.addEventListener('mouseenter', () => {
			if (this.state.awaitingDropTarget) {
				this.tryConsume();
			} else {
				this.vscode.postMessage({ type: 'queryPendingDrag' });
			}
		});
		this.listEl.addEventListener('mouseup', () => {
			if (this.state.awaitingDropTarget) {
				this.tryConsume();
			} else {
				this.vscode.postMessage({ type: 'queryPendingDrag' });
			}
		});
	}

	/** Called by EvoMessages when the host replies with 'pendingDragFiles'. */
	onPendingDragFiles(files: { fsPath: string; label?: string }[]): void {
		if (files.length === 0) { return; }
		const file = files[0];
		this.state.latestDragged      = { fsPath: file.fsPath, label: file.label };
		this.state.awaitingDropTarget = false;
		this.tryConsume();
	}

	/** Called by EvoMessages right after it stores latestDragged (push path, still kept for safety). */
	notifyDragStarted(): void {
		// If awaitingDropTarget was already set and mouse is over the zone, tryConsume.
		// In the pull-model this is a no-op most of the time, but acts as a safety net.
		if (this.state.awaitingDropTarget) {
			this.tryConsume();
		}
	}

	private tryConsume(): void {
		this.listEl.classList.remove('drag-over');
		const file = this.state.latestDragged;
		this.state.latestDragged      = null;
		this.state.awaitingDropTarget = false;
		if (!file?.fsPath) { return; }
		const bn    = file.fsPath.replace(/\\/g, '/').split('/').pop() ?? '';
		const label = file.label || bn.replace(/\.xmi$/i, '');
		const id    = `vb-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
		this.state.lastVerificationBigraphs.push({ id, label, path: file.fsPath, stop: true });
		this.render(this.state.lastVerificationBigraphs);
		this.reportVerificationBigraphs();
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private parseIdx(el: HTMLElement): number {
		const idx = parseInt(el.dataset.vbIdx ?? '', 10);
		return isNaN(idx) || idx < 0 || idx >= this.state.lastVerificationBigraphs.length ? -1 : idx;
	}

	private reportVerificationBigraphs(): void {
		this.vscode.postMessage({
			type: 'verificationBigraphsChanged',
			verificationBigraphs: this.state.lastVerificationBigraphs,
		});
	}
}
