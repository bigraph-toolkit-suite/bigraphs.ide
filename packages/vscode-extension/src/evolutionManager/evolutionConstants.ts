/** Config filename inside a `*.evolution` folder. */
export const EVOLUTION_JSON = 'evolution.json';

/** Suffix of evolution project folders. */
export const EVOLUTION_FOLDER_SUFFIX = '.evolution';

/** Workspace bigraph filename (relative path stored in {@link EvolutionJsonKey.WorkspaceBigraph}). */
export const WORKSPACE_BIGRAPH_XMI = 'workspace-bigraph.xmi';

/** Basename of workspace bigraph artifacts without extension. */
export const WORKSPACE_BIGRAPH_BASENAME = 'workspace-bigraph';

/** Top-level keys in {@link EVOLUTION_JSON}. */
export const EvolutionJsonKey = {
	Label: 'label',
	Rules: 'rules',
	Verification: 'verification',
	RuleApplicationStrategy: 'ruleApplicationStrategy',
	WorkspaceBigraph: 'workspace-bigraph',
	Operations: 'operations',
	CheckpointCursor: 'checkpoint-cursor',
	MaxOperationsEnabled: 'maxOperationsEnabled',
	MaxOperations: 'maxOperations',
	CheckpointFileGeneration: 'checkpointFileGeneration',
	VisualizeIntermediateSteps: 'visualizeIntermediateSteps',
} as const;

/** Keys on operation entries inside {@link EvolutionJsonKey.Operations}. */
export const EvolutionOpKey = {
	Id: 'id',
	Type: 'type',
	Date: 'date',
	Predecessor: 'predecessor',
	Result: 'result',
	Rule: 'rule',
	Verification: 'verification',
} as const;

/** Keys on verification check entries attached to operations. */
export const EvolutionVerificationCheckKey = {
	Id: 'id',
	State: 'state',
} as const;

/** Keys on rewrite-rule entries inside {@link EvolutionJsonKey.Rules}. */
export const EvolutionRuleKey = {
	Id: 'id',
	Label: 'label',
	Redex: 'redex',
	Reactum: 'reactum',
	Active: 'active',
} as const;

/** Keys on verification-bigraph entries inside {@link EvolutionJsonKey.Verification}. */
export const EvolutionVerificationKey = {
	Id: 'id',
	Label: 'label',
	Path: 'path',
	Stop: 'stop',
} as const;

/** Operation `type` values stored in evolution.json. */
export enum EvolutionOperationType {
	Original = 'original',
	Rule = 'rule',
	Manual = 'manual',
}

/** Standard subdirectories inside a `*.evolution` folder. */
export const EvolutionSubdir = {
	Checkpoints: 'checkpoints',
	Rules: 'rules',
	Verification: 'verification',
} as const;

/** Well-known checkpoint paths (relative to evolution folder). */
export const EvolutionCheckpoint = {
	OriginalBase: 'original',
	OriginalXmiRel: 'checkpoints/original.xmi',
} as const;
