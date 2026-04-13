import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import {
	parseOperations,
	normalizeRuleApplicationStrategy,
	type EvolutionFormState,
	type EvolutionOperation
} from './evolutionManagerState.js';
import type { EvolutionManagerViewProvider } from './evolutionManagerViewProvider.js';

// ── setEvolutionFolder ───────────────────────────────────────────────────────

/**
 * Reads evolution.json from `folderPath`, builds the full form state, pushes
 * `fillEvolutionForm` to the webview, and opens the workspace bigraph tab.
 */
export function setEvolutionFolder(
	folderPath: string,
	provider: EvolutionManagerViewProvider
): void {
	const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
	const configRelPath = path.join(
		vscode.workspace.asRelativePath(folderPath),
		'evolution.json'
	);

	provider.postMessage({ type: 'evolutionFolderSelected', folderPath, configRelPath });

	const configFile = path.join(folderPath, 'evolution.json');
	let config: Record<string, unknown>;
	try {
		config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
	} catch {
		vscode.window.showWarningMessage(`Could not read evolution.json in: ${folderPath}`);
		return;
	}

	const rawRules = Array.isArray(config['rules']) ? (config['rules'] as Record<string, unknown>[]) : [];
	const rewriteRules = rawRules.map((r) => ({
		id: String(r['id'] ?? ''),
		label: String(r['label'] ?? ''),
		redexPath: path.join(folderPath, String(r['redex'] ?? '')),
		reactumPath: path.join(folderPath, String(r['reactum'] ?? '')),
		active: r['active'] !== false
	}));

	const rawVerification = Array.isArray(config['verification'])
		? (config['verification'] as Record<string, unknown>[])
		: [];
	const verificationBigraphs = rawVerification.map((v) => ({
		id: String(v['id'] ?? ''),
		label: String(v['label'] ?? ''),
		path: path.join(folderPath, String(v['path'] ?? '')),
		stop: v['stop'] !== false
	}));

	const wsRel = String(config['workspace-bigraph'] ?? '');
	const bigraphAbsPath = wsRel ? path.join(folderPath, wsRel) : '';
	const bigraphRelPath = bigraphAbsPath && workspaceRoot
		? vscode.workspace.asRelativePath(bigraphAbsPath)
		: bigraphAbsPath;
	const workspaceBigraph = wsRel ? path.join(folderPath, wsRel) : '';

	const rawOps = Array.isArray(config['operations'])
		? (config['operations'] as Record<string, unknown>[])
		: [];
	const operations: EvolutionOperation[] = parseOperations(rawOps, folderPath);

	const evolutionLabel   = String(config['label'] ?? '');
	const checkpointCursor = typeof config['checkpoint-cursor'] === 'string'
		? config['checkpoint-cursor']
		: null;

	const newState: EvolutionFormState = {
		evolutionLabel,
		bigraphFsPath: bigraphAbsPath,
		bigraphLockedFromConfig: true,
		rewriteRules,
		verificationBigraphs,
		maxOperationsEnabled: config['maxOperationsEnabled'] !== false,
		maxOperations: typeof config['maxOperations'] === 'number' ? config['maxOperations'] : 10,
		checkpointFileGeneration: config['checkpointFileGeneration'] !== false,
		visualizeIntermediateSteps: !!config['visualizeIntermediateSteps'],
		ruleApplicationStrategy: normalizeRuleApplicationStrategy(config['ruleApplicationStrategy']),
		workspaceBigraph,
		operations,
		evolutionConfigRelPath: configRelPath,
		checkpointCursor
	};

	provider.setFormState(newState);

	provider.postMessage({
		type: 'fillEvolutionForm',
		evolutionLabel,
		bigraphFsPath: bigraphAbsPath,
		bigraphRelativePath: bigraphRelPath,
		rewriteRules,
		verificationBigraphs,
		operations,
		workspaceBigraph,
		evolutionConfigRelPath: configRelPath,
		checkpointCursor,
		ruleApplicationStrategy: normalizeRuleApplicationStrategy(config['ruleApplicationStrategy'])
	});

	const bigraphToOpen = workspaceBigraph || bigraphAbsPath;
	if (bigraphToOpen) {
		vscode.commands
			.executeCommand('vscode.openWith', vscode.Uri.file(bigraphToOpen), 'bigraph.glspDiagram')
			.then(undefined, (err) => {
				console.error('[Evolution Manager] openOrFocusBigraphTab failed:', err);
			});
	}
}

// ── refreshTreeFromJson ──────────────────────────────────────────────────────

/**
 * Re-reads evolution.json and pushes fresh operations + checkpointCursor to the
 * webview so the evolution tree reflects the latest state.
 */
export function refreshTreeFromJson(provider: EvolutionManagerViewProvider): void {
	const folderPath = provider.evolutionConfigPath;
	if (!folderPath) { return; }

	const evoJsonPath = path.join(folderPath, 'evolution.json');
	let config: Record<string, unknown>;
	try {
		config = JSON.parse(fs.readFileSync(evoJsonPath, 'utf8'));
	} catch {
		return;
	}

	const rawOps = Array.isArray(config['operations'])
		? (config['operations'] as Record<string, unknown>[])
		: [];
	const operations: EvolutionOperation[] = parseOperations(rawOps, folderPath);
	const checkpointCursor = typeof config['checkpoint-cursor'] === 'string'
		? config['checkpoint-cursor']
		: null;

	provider.patchFormState({ operations, checkpointCursor });
	provider.postMessage({ type: 'updateTree', operations, checkpointCursor });
}

// ── handleVerifyResult ───────────────────────────────────────────────────────

/**
 * Called by the connector when the server responds with a verify result.
 * Writes the result into the matching operation in evolution.json, then
 * forwards a `verificationCheckResult` message to the webview.
 */
export function handleVerifyResult(
	verificationId: string,
	matched: boolean,
	message: string,
	operationId: string | null,
	provider: EvolutionManagerViewProvider
): void {
	const folderPath = provider.evolutionConfigPath;

	if (folderPath && operationId) {
		const evoJsonPath = path.join(folderPath, 'evolution.json');
		try {
			const raw  = fs.readFileSync(evoJsonPath, 'utf8');
			const json = JSON.parse(raw) as Record<string, unknown>;
			const ops  = Array.isArray(json['operations'])
				? (json['operations'] as Record<string, unknown>[])
				: [];
			const op = ops.find((o) => o['id'] === operationId);
			if (op) {
				const verList = Array.isArray(op['verification'])
					? (op['verification'] as Record<string, unknown>[])
					: [];
				const existing = verList.findIndex((v) => v['id'] === verificationId);
				const entry = { id: verificationId, state: matched };
				if (existing >= 0) { verList[existing] = entry; } else { verList.push(entry); }
				op['verification'] = verList;
				fs.writeFileSync(evoJsonPath, JSON.stringify(json, null, 2) + '\n', 'utf8');
			}
		} catch {
			// non-fatal – the webview still gets the result
		}
	}

	provider.postMessage({ type: 'verificationCheckResult', verificationId, matched, message });
}
