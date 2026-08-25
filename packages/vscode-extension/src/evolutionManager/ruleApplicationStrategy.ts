/**
 * Wire values for evolution.json and the GLSP {@code EvolutionRunAction}.
 * Display strings for the UI live in {@link RULE_APPLICATION_STRATEGY_LABELS};
 * rewriting modes may provide their own labels for the same wire values.
 */
export enum RuleApplicationStrategy {
	FirstFirst = 'first-first',
	RoundRobin = 'round-robin',
}

/** Labels for the default Evolution mode. */
export const RULE_APPLICATION_STRATEGY_LABELS: Record<RuleApplicationStrategy, string> = {
	[RuleApplicationStrategy.FirstFirst]: 'First-first (always try first rule next)',
	[RuleApplicationStrategy.RoundRobin]: 'Round-robin (rotate after each application)',
};

export function normalizeRuleApplicationStrategy(value: unknown): RuleApplicationStrategy {
	if (typeof value !== 'string') {
		return RuleApplicationStrategy.FirstFirst;
	}
	const t = value.trim();
	if (t === RuleApplicationStrategy.RoundRobin) {
		return RuleApplicationStrategy.RoundRobin;
	}
	return RuleApplicationStrategy.FirstFirst;
}
