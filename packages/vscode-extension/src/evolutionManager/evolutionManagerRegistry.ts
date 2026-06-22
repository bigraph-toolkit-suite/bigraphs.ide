import type { EvolutionManagerViewProvider } from './evolutionManagerViewProvider.js';

let instance: EvolutionManagerViewProvider | null = null;

export function registerEvolutionManagerViewProvider(provider: EvolutionManagerViewProvider): void {
	instance = provider;
}

export function getEvolutionManagerViewProvider(): EvolutionManagerViewProvider | null {
	return instance;
}
