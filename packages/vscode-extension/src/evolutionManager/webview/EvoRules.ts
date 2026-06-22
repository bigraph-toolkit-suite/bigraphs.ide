import type { EvoState } from './EvoState.js';
import type { RewriteRule, VsCodeApi } from './types.js';

const DROP_HINT = `<div class="rewrite-rule-drop-hint">Drop rewrite rules from the explorer here…</div>`;
const TRASH_SVG = `<svg viewBox="0 0 16 16"><path d="M6 2h4l1 1h3v1H2V3h3l1-1zm-2 4h1v8H4V6zm3 0h1v8H7V6zm3 0h1v8h-1V6z"/></svg>`;
const PLAY_SVG = `<svg viewBox="0 0 16 16"><path d="M3 2l11 6-11 6V2z"/></svg>`;

export class EvoRules {
	private readonly listEl: HTMLElement;
	private readonly state: EvoState;
	private readonly vscode: VsCodeApi;
	private onChanged: (() => void) | null = null;
	private onPlayRule: ((ruleId: string) => void) | null = null;
	private evolutionRunning = false;

	constructor(state: EvoState, vscode: VsCodeApi) {
		this.state  = state;
		this.vscode = vscode;
		this.listEl = document.getElementById('rewriteRulesList')!;
	}

	/** Register a callback invoked after a rule's active flag changes (for form-state reporting). */
	setOnChanged(cb: () => void): void {
		this.onChanged = cb;
	}

	/** Register a callback invoked when the per-rule play button is clicked. */
	setOnPlayRule(cb: (ruleId: string) => void): void {
		this.onPlayRule = cb;
	}

	setEvolutionRunning(running: boolean): void {
		this.evolutionRunning = running;
		this.listEl.querySelectorAll<HTMLButtonElement>('.rule-play-btn').forEach((btn) => {
			btn.disabled = running || btn.dataset.playDisabled === 'true';
		});
	}

	render(rules: RewriteRule[]): void {
		if (rules.length === 0) {
			this.listEl.innerHTML = DROP_HINT;
			return;
		}
		this.listEl.innerHTML = rules.map((r, i) => {
			const ruleName    = r.label ?? '';
			const redexFile   = r.redexPath  ? r.redexPath.split(/[\\/]/).pop()  ?? '' : '';
			const reactumFile = r.reactumPath ? r.reactumPath.split(/[\\/]/).pop() ?? '' : '';
			const subtitle    = redexFile && reactumFile
				? `${redexFile} → ${reactumFile}`
				: redexFile || reactumFile;
			const isActive  = r.active !== false;
			const tagClass  = `rule-tag${isActive ? ' active' : ''}`;
			const canDelete = !this.state.hasEvolutionJson || !this.isRuleReferencedInOperations(r.id);
			const canPlayRule = this.state.hasEvolutionJson && !!r.id;
			const playDisabled = !canPlayRule || this.evolutionRunning;
			return `<div class="item" data-rule-idx="${i}">`
				+ `<span class="rule-name">`
				+   (ruleName  ? `<span class="rule-label">${ruleName}</span>`       : '')
				+   (subtitle  ? `<span class="rule-subtitle">${subtitle}</span>` : '')
				+ `</span>`
				+ (canDelete
					? `<button class="rewrite-rule-del-btn" title="Delete" data-rule-idx="${i}">${TRASH_SVG}</button>`
					: '')
				+ `<span class="${tagClass}" data-rule-idx="${i}">active</span>`
				+ `<button type="button" class="rule-play-btn" title="Run this rule once at cursor" data-rule-idx="${i}" data-rule-id="${r.id ?? ''}" data-play-disabled="${canPlayRule ? 'false' : 'true'}"${playDisabled ? ' disabled' : ''}>${PLAY_SVG}</button>`
				+ `</div>`;
		}).join('');

		this.listEl.querySelectorAll<HTMLElement>('.rule-tag').forEach((tag) => {
			tag.addEventListener('click', () => {
				const idx = parseInt(tag.dataset.ruleIdx ?? '', 10);
				if (isNaN(idx) || idx < 0 || idx >= this.state.lastRewriteRules.length) { return; }
				const rule = this.state.lastRewriteRules[idx];
				this.state.lastRewriteRules[idx] = { ...rule, active: !rule.active };
				tag.classList.toggle('active', this.state.lastRewriteRules[idx].active);
				this.onChanged?.();
			});
		});

		this.listEl.querySelectorAll<HTMLButtonElement>('.rule-play-btn').forEach((btn) => {
			btn.addEventListener('click', (e) => {
				e.stopPropagation();
				if (btn.disabled) { return; }
				const ruleId = btn.dataset.ruleId ?? '';
				if (!ruleId) { return; }
				this.onPlayRule?.(ruleId);
			});
		});

		this.listEl.querySelectorAll<HTMLButtonElement>('.rewrite-rule-del-btn').forEach((btn) => {
			btn.addEventListener('click', (e) => {
				e.stopPropagation();
				const idx = parseInt(btn.dataset.ruleIdx ?? '', 10);
				if (isNaN(idx) || idx < 0 || idx >= this.state.lastRewriteRules.length) { return; }
				const rule = this.state.lastRewriteRules[idx];
				if (!rule?.id) { return; }
				if (this.state.hasEvolutionJson && this.isRuleReferencedInOperations(rule.id)) { return; }
				this.state.lastRewriteRules.splice(idx, 1);
				this.render(this.state.lastRewriteRules);
				this.onChanged?.();
				this.vscode.postMessage({
					type: 'deleteRewriteRule',
					ruleId: rule.id
				});
			});
		});
	}

	/** Merges rules into the list (dedupes by label + redex/reactum paths; extension filters by file content on import). */
	appendRewriteRules(rules: Array<{ label: string; redexPath: string; reactumPath: string }>): void {
		for (const r of rules) {
			if (
				typeof r.label !== 'string' ||
				typeof r.redexPath !== 'string' ||
				typeof r.reactumPath !== 'string'
			) {
				continue;
			}
			const exists = this.state.lastRewriteRules.some((existing) =>
				existing.label === r.label &&
				existing.redexPath === r.redexPath &&
				existing.reactumPath === r.reactumPath
			);
			if (!exists) {
				this.state.lastRewriteRules.push({
					id: `rr-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
					label: r.label,
					redexPath: r.redexPath,
					reactumPath: r.reactumPath,
					active: true
				});
			}
		}
		this.render(this.state.lastRewriteRules);
		this.onChanged?.();
	}

	addRewriteRulesFromDragPayload(payload: unknown): void {
		const rewriteRules = (payload && typeof payload === 'object' && Array.isArray((payload as any).rewriteRules))
			? (payload as any).rewriteRules as Array<{ setLabel: string; label: string; redexPath: string; reactumPath: string }>
			: [];
		if (rewriteRules.length === 0) {
			return;
		}
		this.appendRewriteRules(rewriteRules.map((r) => ({
			label: r.label,
			redexPath: r.redexPath,
			reactumPath: r.reactumPath
		})));
	}

	private isRuleReferencedInOperations(ruleId: string): boolean {
		if (!ruleId) { return false; }
		return this.state.referencedRewriteRuleIds.has(ruleId);
	}
}
