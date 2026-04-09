import type { EvoState } from './EvoState.js';
import type { RewriteRule } from './types.js';

const DROP_HINT = `<div class="rewrite-rule-drop-hint">Drop rewrite rules from the explorer here…</div>`;

export class EvoRules {
	private readonly listEl: HTMLElement;
	private readonly state: EvoState;
	private onChanged: (() => void) | null = null;

	constructor(state: EvoState) {
		this.state  = state;
		this.listEl = document.getElementById('rewriteRulesList')!;
	}

	/** Register a callback invoked after a rule's active flag changes (for form-state reporting). */
	setOnChanged(cb: () => void): void {
		this.onChanged = cb;
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
			return `<div class="item" data-rule-idx="${i}">`
				+ `<span class="rule-name">`
				+   (ruleName  ? `<span class="rule-label">${ruleName}</span>`       : '')
				+   (subtitle  ? `<span class="rule-subtitle">${subtitle}</span>` : '')
				+ `</span>`
				+ `<span class="${tagClass}" data-rule-idx="${i}">active</span>`
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
	}

	addRewriteRulesFromDragPayload(payload: unknown): void {
		const rewriteRules = (payload && typeof payload === 'object' && Array.isArray((payload as any).rewriteRules))
			? (payload as any).rewriteRules as Array<{ setLabel: string; label: string; redexPath: string; reactumPath: string }>
			: [];
		if (rewriteRules.length === 0) {
			return;
		}
		for (const r of rewriteRules) {
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
}
