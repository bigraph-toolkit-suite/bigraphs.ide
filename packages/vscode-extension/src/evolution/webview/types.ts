// Shared type definitions for the Evolution Manager webview.
// These mirror src/evolution/evolutionManagerState.ts but are kept separate
// so this file has zero Node.js imports at runtime.

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

export interface RewriteRule {
	id: string;
	label: string;
	redexPath: string;
	reactumPath: string;
	active: boolean;
}

export interface VerificationBigraph {
	id: string;
	label: string;
	path: string;
	stop: boolean;
}

// ── VS Code webview API stub ────────────────────────────────────────────────

export interface VsCodeApi {
	postMessage(message: Record<string, unknown>): void;
	getState(): unknown;
	setState(state: unknown): void;
}

declare function acquireVsCodeApi(): VsCodeApi;

// ── Message payloads (extension → webview) ──────────────────────────────────

export interface ActiveTabChangedMsg {
	type: 'activeTabChanged';
	clientId: string | null;
	fsPath: string | null;
	relativePath: string | null;
}

export interface VerificationCheckResultMsg {
	type: 'verificationCheckResult';
	verificationId: string;
	matched: boolean;
	message: string;
}

export interface AddVerificationBigraphMsg {
	type: 'addVerificationBigraph';
	fsPath: string;
	label?: string;
}

export interface AddVerificationBigraphsMsg {
	type: 'addVerificationBigraphs';
	files: Array<{ fsPath: string; label?: string }>;
}

export interface OperationStartedMsg {
	type: 'operationStarted';
	operationId: string;
	runFolder?: string;
}

export interface OperationFinishedMsg {
	type: 'operationFinished';
	operationId: string;
}

export interface EvolutionFolderSelectedMsg {
	type: 'evolutionFolderSelected';
	folderPath?: string;
	configRelPath?: string;
}

export interface SetConfigPathMsg {
	type: 'setConfigPath';
	configRelPath: string | null;
	workspaceBigraph?: string;
}

export interface SetCursorMsg {
	type: 'setCursor';
	cursorId: string | null;
}

export interface UpdateTreeMsg {
	type: 'updateTree';
	operations: EvolutionOperation[];
	checkpointCursor: string | null;
}

export interface FillEvolutionFormMsg {
	type: 'fillEvolutionForm';
	evolutionLabel?: string;
	bigraphFsPath?: string;
	bigraphRelativePath?: string;
	workspaceBigraph?: string;
	rewriteRules?: RewriteRule[];
	verificationBigraphs?: VerificationBigraph[];
	operations?: EvolutionOperation[];
	checkpointCursor?: string | null;
	evolutionConfigRelPath?: string | null;
}

export interface RestoreFormStateMsg {
	type: 'restoreFormState';
	state: {
		evolutionLabel?: string;
		bigraphFsPath?: string;
		bigraphRelativePath?: string;
		bigraphLockedFromConfig?: boolean;
		maxOperationsEnabled?: boolean;
		maxOperations?: number;
		checkpointFileGeneration?: boolean;
		visualizeIntermediateSteps?: boolean;
		rewriteRules?: RewriteRule[];
		verificationBigraphs?: VerificationBigraph[];
		operations?: EvolutionOperation[];
		checkpointCursor?: string | null;
		workspaceBigraph?: string;
		evolutionConfigRelPath?: string | null;
	};
}

export type InboundMessage =
	| ActiveTabChangedMsg
	| VerificationCheckResultMsg
	| AddVerificationBigraphMsg
	| AddVerificationBigraphsMsg
	| OperationStartedMsg
	| OperationFinishedMsg
	| EvolutionFolderSelectedMsg
	| SetConfigPathMsg
	| SetCursorMsg
	| UpdateTreeMsg
	| FillEvolutionFormMsg
	| RestoreFormStateMsg;
