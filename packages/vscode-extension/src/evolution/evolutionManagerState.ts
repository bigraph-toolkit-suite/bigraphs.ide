import * as path from 'path';

export interface VerificationCheckEntry {
	id: string;
	state: boolean;
}

export interface EvolutionOperation {
	id: string;
	type: string;
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
		id: String(op['id'] ?? ''),
		type: String(op['type'] ?? ''),
		date: String(op['date'] ?? ''),
		predecessor: String(op['predecessor'] ?? ''),
		result: op['result'] ? path.join(folderPath, String(op['result'])) : '',
		rule: op['rule'] !== null && op['rule'] !== undefined ? String(op['rule']) : null,
		verification: Array.isArray(op['verification'])
			? (op['verification'] as Record<string, unknown>[]).map((v) => ({
				id: String(v['id'] ?? ''),
				state: v['state'] === true
			}))
			: []
	}));
}
