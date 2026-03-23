import type { EvoState } from './EvoState.js';
import type { RewriteRule } from './types.js';

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
}
