import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import {
	EvolutionJsonKey,
	EvolutionOpKey,
	EvolutionOperationType,
	EvolutionRuleKey,
	EvolutionVerificationCheckKey,
	EvolutionVerificationKey,
} from './evolutionConstants.js';
import { evolutionJsonPath } from './evolutionPaths.js';
import {
	normalizeRuleApplicationStrategy,
	parseOperations,
	type EvolutionFormState,
	type EvolutionOperation,
} from './evolutionManagerState.js';

/** In-memory representation of a loaded `evolution.json`. Disk is touched on load/persist only. */
export class EvolutionDocument {
	constructor(
		public readonly folderPath: string,
		private readonly data: Record<string, unknown>
	) {}

	static load(folderPath: string): EvolutionDocument {
		const configFile = evolutionJsonPath(folderPath);
		const raw = fs.readFileSync(configFile, 'utf8');
		return new EvolutionDocument(folderPath, JSON.parse(raw) as Record<string, unknown>);
	}

	static tryLoad(folderPath: string): EvolutionDocument | undefined {
		try {
			return EvolutionDocument.load(folderPath);
		} catch {
			return undefined;
		}
	}

	persist(): void {
		const evoJsonPath = evolutionJsonPath(this.folderPath);
		fs.writeFileSync(evoJsonPath, JSON.stringify(this.data, null, 2) + '\n', 'utf8');
	}

	getRaw(): Record<string, unknown> {
		return this.data;
	}

	/**
	 * Generic accessor for root-level keys, used by mode extensions to read
	 * their private data (e.g. goalPaths, operationHost) without the core
	 * having to know each key.
	 */
	getRootValue<T = unknown>(key: string): T | undefined {
		return this.data[key] as T | undefined;
	}

	/** Generic setter for root-level keys. `undefined` removes the key. */
	setRootValue(key: string, value: unknown): void {
		if (value === undefined) {
			delete this.data[key];
		} else {
			this.data[key] = value;
		}
	}

	getCheckpointCursor(): string | null {
		const cursor = this.data[EvolutionJsonKey.CheckpointCursor];
		return typeof cursor === 'string' ? cursor : null;
	}

	setCheckpointCursor(cursor: string | null): void {
		this.data[EvolutionJsonKey.CheckpointCursor] = cursor;
	}

	getRawOperations(): Record<string, unknown>[] {
		return Array.isArray(this.data[EvolutionJsonKey.Operations])
			? (this.data[EvolutionJsonKey.Operations] as Record<string, unknown>[])
			: [];
	}

	setRawOperations(ops: Record<string, unknown>[]): void {
		this.data[EvolutionJsonKey.Operations] = ops;
	}

	getOperations(): EvolutionOperation[] {
		return parseOperations(this.getRawOperations(), this.folderPath);
	}

	getRules(): Record<string, unknown>[] {
		return Array.isArray(this.data[EvolutionJsonKey.Rules])
			? (this.data[EvolutionJsonKey.Rules] as Record<string, unknown>[])
			: [];
	}

	setRules(rules: Record<string, unknown>[]): void {
		this.data[EvolutionJsonKey.Rules] = rules;
	}

	getVerification(): Record<string, unknown>[] {
		return Array.isArray(this.data[EvolutionJsonKey.Verification])
			? (this.data[EvolutionJsonKey.Verification] as Record<string, unknown>[])
			: [];
	}

	setVerification(verification: Record<string, unknown>[]): void {
		this.data[EvolutionJsonKey.Verification] = verification;
	}

	setRuleApplicationStrategy(strategy: EvolutionFormState['ruleApplicationStrategy']): void {
		this.data[EvolutionJsonKey.RuleApplicationStrategy] = strategy;
	}

	addManualOperation(params: {
		id: string;
		predecessorId: string | null;
		relResult: string;
	}): void {
		const ops = this.getRawOperations();
		ops.push({
			id: params.id,
			type: EvolutionOperationType.Manual,
			date: new Date().toISOString(),
			predecessor: params.predecessorId ?? null,
			result: params.relResult,
			rule: null,
			verification: [],
		});
		this.setRawOperations(ops);
		this.setCheckpointCursor(params.id);
	}

	setVerificationResult(operationId: string, verificationId: string, matched: boolean): void {
		const ops = this.getRawOperations();
		const op = ops.find((o) => o[EvolutionOpKey.Id] === operationId);
		if (!op) { return; }
		const verList = Array.isArray(op[EvolutionOpKey.Verification])
			? (op[EvolutionOpKey.Verification] as Record<string, unknown>[])
			: [];
		const existing = verList.findIndex((v) => v[EvolutionVerificationCheckKey.Id] === verificationId);
		const entry = { [EvolutionVerificationCheckKey.Id]: verificationId, [EvolutionVerificationCheckKey.State]: matched };
		if (existing >= 0) { verList[existing] = entry; } else { verList.push(entry); }
		op[EvolutionOpKey.Verification] = verList;
		this.setRawOperations(ops);
	}
}

export function buildFormStateFromDocument(
	doc: EvolutionDocument,
	configRelPath: string
): EvolutionFormState {
	const config = doc.getRaw();
	const folderPath = doc.folderPath;
	const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';

	const rawRules = doc.getRules();
	const rewriteRules = rawRules.map((r) => ({
		id: String(r[EvolutionRuleKey.Id] ?? ''),
		label: String(r[EvolutionRuleKey.Label] ?? ''),
		redexPath: path.join(folderPath, String(r[EvolutionRuleKey.Redex] ?? '')),
		reactumPath: path.join(folderPath, String(r[EvolutionRuleKey.Reactum] ?? '')),
		active: r[EvolutionRuleKey.Active] !== false,
	}));

	const rawVerification = doc.getVerification();
	const verificationBigraphs = rawVerification.map((v) => ({
		id: String(v[EvolutionVerificationKey.Id] ?? ''),
		label: String(v[EvolutionVerificationKey.Label] ?? ''),
		path: path.join(folderPath, String(v[EvolutionVerificationKey.Path] ?? '')),
		stop: v[EvolutionVerificationKey.Stop] !== false,
	}));

	const wsRel = String(config[EvolutionJsonKey.WorkspaceBigraph] ?? '');
	const bigraphAbsPath = wsRel ? path.join(folderPath, wsRel) : '';
	const workspaceBigraph = wsRel ? path.join(folderPath, wsRel) : '';
	const operations = doc.getOperations();

	// Root-level keys the core does not know (e.g. goalPaths, operationHost)
	// are forwarded opaquely so mode extensions in the webview can read them.
	const coreKeys = new Set<string>(Object.values(EvolutionJsonKey));
	const extensionJson = Object.fromEntries(
		Object.entries(config).filter(([key]) => !coreKeys.has(key))
	);

	return {
		evolutionLabel: String(config[EvolutionJsonKey.Label] ?? ''),
		bigraphFsPath: bigraphAbsPath,
		bigraphLockedFromConfig: true,
		rewriteRules,
		verificationBigraphs,
		maxOperationsEnabled: config[EvolutionJsonKey.MaxOperationsEnabled] !== false,
		maxOperations: typeof config[EvolutionJsonKey.MaxOperations] === 'number'
			? (config[EvolutionJsonKey.MaxOperations] as number)
			: 10,
		checkpointFileGeneration: config[EvolutionJsonKey.CheckpointFileGeneration] !== false,
		ruleApplicationStrategy: normalizeRuleApplicationStrategy(
			config[EvolutionJsonKey.RuleApplicationStrategy]
		),
		workspaceBigraph,
		operations,
		evolutionConfigRelPath: configRelPath,
		checkpointCursor: doc.getCheckpointCursor(),
		extensionJson,
	};
}

export function pushTreeUpdate(
	doc: EvolutionDocument,
	patchFormState: (patch: Partial<EvolutionFormState>) => void,
	postMessage: (message: Record<string, unknown>) => void
): void {
	const operations = doc.getOperations();
	const checkpointCursor = doc.getCheckpointCursor();
	patchFormState({ operations, checkpointCursor });
	postMessage({ type: 'updateTree', operations, checkpointCursor });
}
