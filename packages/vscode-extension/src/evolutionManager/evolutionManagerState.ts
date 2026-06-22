import * as path from 'path';
import type { RuleApplicationStrategy } from './ruleApplicationStrategy.js';
import {
	EvolutionJsonKey,
	EvolutionOpKey,
	EvolutionOperationType,
	EvolutionVerificationCheckKey,
} from './evolutionConstants.js';

export {
	RuleApplicationStrategy,
	RULE_APPLICATION_STRATEGY_LABELS,
	normalizeRuleApplicationStrategy,
} from './ruleApplicationStrategy.js';
export { EvolutionOperationType } from './evolutionConstants.js';

export interface VerificationCheckEntry {
	id: string;
	state: boolean;
}

export interface EvolutionOperation {
	id: string;
	type: EvolutionOperationType | string;
	date: string;
	predecessor: string;
	result: string;
	rule: string | null;
	verification: VerificationCheckEntry[];
}

/** The subset of form fields owned by the user (excludes clientId which comes from the active tab). */
export interface EvolutionFormState {
	evolutionLabel: string;
	/** Absolute fs path to the bigraph – stored so it survives webview recreation. */
	bigraphFsPath: string;
	/** When true the bigraph path came from a loaded config and activeTabChanged must not overwrite it. */
	bigraphLockedFromConfig: boolean;
	rewriteRules: { id: string; label: string; redexPath: string; reactumPath: string; active: boolean }[];
	verificationBigraphs: { id: string; label: string; path: string; stop: boolean }[];
	maxOperationsEnabled: boolean;
	maxOperations: number;
	checkpointFileGeneration: boolean;
	visualizeIntermediateSteps: boolean;
	ruleApplicationStrategy: RuleApplicationStrategy;
	/** `workspace-bigraph` from the JSON – the current working bigraph of this evolution. */
	workspaceBigraph: string;
	/** All recorded operations from the evolution history. */
	operations: EvolutionOperation[];
	/** Relative path to the currently loaded evolution.json (e.g. "abc.evolution/evolution.json"). Null for a new evolution. */
	evolutionConfigRelPath: string | null;
	/** The id of the operation the checkpoint-cursor currently points to. */
	checkpointCursor: string | null;
}

/**
 * Maps a raw operations array from evolution.json to typed `EvolutionOperation[]`.
 * `folderPath` is used to resolve relative result paths to absolute ones.
 */
export function parseOperations(
	rawOps: Record<string, unknown>[],
	folderPath: string
): EvolutionOperation[] {
	return rawOps.map((op) => ({
		id: String(op[EvolutionOpKey.Id] ?? ''),
		type: String(op[EvolutionOpKey.Type] ?? ''),
		date: String(op[EvolutionOpKey.Date] ?? ''),
		predecessor: String(op[EvolutionOpKey.Predecessor] ?? ''),
		result: op[EvolutionOpKey.Result]
			? path.join(folderPath, String(op[EvolutionOpKey.Result]))
			: '',
		rule: op[EvolutionOpKey.Rule] !== null && op[EvolutionOpKey.Rule] !== undefined
			? String(op[EvolutionOpKey.Rule])
			: null,
		verification: Array.isArray(op[EvolutionOpKey.Verification])
			? (op[EvolutionOpKey.Verification] as Record<string, unknown>[]).map((v) => ({
				id: String(v[EvolutionVerificationCheckKey.Id] ?? ''),
				state: v[EvolutionVerificationCheckKey.State] === true
			}))
			: []
	}));
}
