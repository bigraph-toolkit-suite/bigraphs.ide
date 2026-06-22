import * as path from 'path';
import * as vscode from 'vscode';
import type { EvolutionManagerViewProvider } from './evolutionManagerViewProvider.js';
import { openManagedWorkspaceBigraph } from '../workspaceBigraph/openWorkspaceBigraph.js';
import {
	EvolutionDocument,
	buildFormStateFromDocument,
	pushTreeUpdate,
} from './evolutionDocument.js';
import { EVOLUTION_JSON } from './evolutionConstants.js';
import { evolutionJsonPath } from './evolutionPaths.js';

// ── setEvolutionFolder ───────────────────────────────────────────────────────

/**
 * Loads evolution.json once into the in-memory model, builds form state, pushes
 * `fillEvolutionForm` to the webview, and opens the workspace bigraph tab.
 */
export function setEvolutionFolder(
	folderPath: string,
	provider: EvolutionManagerViewProvider,
	options?: { skipOpenBigraph?: boolean }
): void {
	const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
	const configRelPath = path.join(
		vscode.workspace.asRelativePath(folderPath),
		EVOLUTION_JSON
	);

	provider.postMessage({ type: 'evolutionFolderSelected', folderPath, configRelPath });

	const doc = EvolutionDocument.tryLoad(folderPath);
	if (!doc) {
		vscode.window.showWarningMessage(`Could not read ${EVOLUTION_JSON} in: ${folderPath}`);
		return;
	}

	provider.setEvolutionDocument(doc);
	const newState = buildFormStateFromDocument(doc, configRelPath);
	provider.setFormState(newState);

	const bigraphAbsPath = newState.bigraphFsPath;
	const bigraphRelPath = bigraphAbsPath && workspaceRoot
		? vscode.workspace.asRelativePath(bigraphAbsPath)
		: bigraphAbsPath;

	provider.postMessage({
		type: 'fillEvolutionForm',
		evolutionLabel: newState.evolutionLabel,
		bigraphFsPath: bigraphAbsPath,
		bigraphRelativePath: bigraphRelPath,
		rewriteRules: newState.rewriteRules,
		verificationBigraphs: newState.verificationBigraphs,
		operations: newState.operations,
		workspaceBigraph: newState.workspaceBigraph,
		evolutionConfigRelPath: configRelPath,
		checkpointCursor: newState.checkpointCursor,
		ruleApplicationStrategy: newState.ruleApplicationStrategy,
	});

	const bigraphToOpen = newState.workspaceBigraph || bigraphAbsPath;
	if (bigraphToOpen && !options?.skipOpenBigraph) {
		void openManagedWorkspaceBigraph(bigraphToOpen, folderPath).catch((err) => {
			console.error('[Evolution Manager] openOrFocusBigraphTab failed:', err);
		});
	}
}

// ── refreshTreeFromModel ─────────────────────────────────────────────────────

/** Pushes operations + checkpointCursor from the in-memory model to the webview. */
export function refreshTreeFromModel(provider: EvolutionManagerViewProvider): void {
	const doc = provider.evolutionDocument;
	if (!doc) { return; }
	pushTreeUpdate(doc, (patch) => provider.patchFormState(patch), (msg) => provider.postMessage(msg));
}

/** @deprecated Use {@link refreshTreeFromModel}. Kept as alias for call sites. */
export function refreshTreeFromJson(provider: EvolutionManagerViewProvider): void {
	refreshTreeFromModel(provider);
}

/** Reloads evolution.json from disk after the GLSP backend mutates it (e.g. evolution run finished). */
export function reloadEvolutionDocumentFromDisk(provider: EvolutionManagerViewProvider): void {
	const folderPath = provider.evolutionConfigPath;
	if (!folderPath) { return; }
	const doc = EvolutionDocument.tryLoad(folderPath);
	if (!doc) { return; }
	provider.setEvolutionDocument(doc);
	const configRelPath = provider.formState?.evolutionConfigRelPath
		?? path.join(vscode.workspace.asRelativePath(folderPath), EVOLUTION_JSON);
	provider.patchFormState(buildFormStateFromDocument(doc, configRelPath));
	refreshTreeFromModel(provider);
}

// ── handleVerifyResult ───────────────────────────────────────────────────────

export function handleVerifyResult(
	verificationId: string,
	matched: boolean,
	message: string,
	operationId: string | null,
	provider: EvolutionManagerViewProvider
): void {
	const doc = provider.evolutionDocument;
	if (doc && operationId) {
		doc.setVerificationResult(operationId, verificationId, matched);
		doc.persist();
		refreshTreeFromModel(provider);
	}

	provider.postMessage({ type: 'verificationCheckResult', verificationId, matched, message });
}
