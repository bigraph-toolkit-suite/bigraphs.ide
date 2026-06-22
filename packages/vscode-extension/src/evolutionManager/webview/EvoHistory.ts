import type { EvolutionOperation } from './types.js';
import { EvolutionOperationType } from '../evolutionConstants.js';

export class EvoHistory {
	private readonly listEl: HTMLElement;
	private readonly sectionEl: HTMLElement;

	constructor() {
		this.listEl    = document.getElementById('historyList')!;
		this.sectionEl = document.getElementById('historySection')!;
		this.setVisible(false);
	}

	render(operations: EvolutionOperation[]): void {
		if (operations.length === 0) {
			this.listEl.innerHTML = '<div class="empty-hint">No history yet.</div>';
			return;
		}
		this.listEl.innerHTML = operations.map((op) => {
			const label = op.type === EvolutionOperationType.Rule
				? 'Rule applied'
				: op.type === EvolutionOperationType.Manual ? 'Manual edit' : op.type || 'Operation';
			const date = op.date ? new Date(op.date).toLocaleString() : '';
			return `<div class="history-item">`
				+ `<span class="history-label">${label}</span>`
				+ (date ? `<span class="history-date">${date}</span>` : '')
				+ `</div>`;
		}).join('');
	}

	setVisible(visible: boolean): void {
		this.sectionEl.style.display = visible ? '' : 'none';
	}
}
