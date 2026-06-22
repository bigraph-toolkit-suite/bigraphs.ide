import type { EvolutionsProvider } from './evolutionsProvider.js';

let provider: EvolutionsProvider | null = null;

export function registerEvolutionsProvider(p: EvolutionsProvider): void {
	provider = p;
}

export function refreshEvolutionsList(): void {
	provider?.refresh();
}
