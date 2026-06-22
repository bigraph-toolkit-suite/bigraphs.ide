import * as path from 'path';
import * as vscode from 'vscode';
import {
	prepareEvolutionRunFolder,
	dispatchEvolutionRunForExisting,
	type EvolutionActionPayload
} from './runEvolution.js';
import { normalizeRuleApplicationStrategy } from './evolutionManagerState.js';
import type { EvolutionManagerViewProvider } from './evolutionManagerViewProvider.js';
import { sendActionToServer, waitForClientSession } from '../editor/glspServerBridge.js';
import { getClientIdForFsPath } from '../editor/glspClientRegistry.js';
import {
	applyCheckpointWithMeta,
	guardUnsavedWorkspaceChanges,
	resetSessionDirtyAndBroadcast,
} from '../workspaceBigraph/workspaceBigraphService.js';
import { closeCustomEditorTab } from '../workspaceBigraph/bigraphArtifacts.js';
import { openManagedWorkspaceBigraph } from '../workspaceBigraph/openWorkspaceBigraph.js';
import { refreshTreeFromModel } from './evolutionManagerJsonIO.js';
import { EVOLUTION_JSON } from './evolutionConstants.js';
import { originalCheckpointPath } from './evolutionPaths.js';

// ── HTML loader ──────────────────────────────────────────────────────────────

export async function loadEvolutionManagerHtml(
	webview: vscode.Webview,
	extensionUri: vscode.Uri
): Promise<string> {
	const htmlUri = vscode.Uri.joinPath(extensionUri, 'src', 'evolutionManager', 'evolutionManager.html');
	const jsUri   = vscode.Uri.joinPath(extensionUri, 'dist', 'evolution-manager.js');

	const [htmlData, jsData] = await Promise.all([
		vscode.workspace.fs.readFile(htmlUri),
		vscode.workspace.fs.readFile(jsUri),
	]);

	const html          = Buffer.from(htmlData).toString('utf8');
	const scriptContent = Buffer.from(jsData).toString('utf8');

	return html
		.replace(/\{\{CSP_SOURCE\}\}/g, webview.cspSource)
		.replace(/\{\{SCRIPT_CONTENT\}\}/g, scriptContent);
}

// ── Pause ────────────────────────────────────────────────────────────────────

export async function dispatchPause(
	clientId: string | null,
	operationId: string
): Promise<void> {
	const sent = sendActionToServer(
		{ kind: 'bigraph.evolutionRun', actionType: 'pause', operationId },
		clientId ?? undefined
	);
	if (!sent) {
		vscode.window.showErrorMessage('GLSP server not available.');
	}
}

// ── Evolution action (play / step / pause) ───────────────────────────────────

export function handleEvolutionAction(
	msg: { type?: string } & EvolutionActionPayload,
	provider: EvolutionManagerViewProvider | null
): void {
	if (msg.actionType === 'pause') {
		const opId = msg.operationId;
		if (opId) {
			dispatchPause(msg.clientId ?? null, opId).catch((err) => {
				console.error('[Evolution Manager] dispatchPause failed:', err);
			});
		}
		return;
	}

	const basePayload: EvolutionActionPayload = {
		actionType: msg.actionType ?? 'play',
		clientId: msg.clientId ?? null,
		targetRuleId: typeof msg.targetRuleId === 'string' ? msg.targetRuleId : undefined,
		evolutionLabel: msg.evolutionLabel ?? '',
		bigraphPath: msg.bigraphPath ?? '',
		rewriteRules: Array.isArray(msg.rewriteRules)
			? (msg.rewriteRules as { label?: string; redexPath: string; reactumPath: string; active?: boolean }[])
			: [],
		verificationBigraphs: Array.isArray(msg.verificationBigraphs)
			? (msg.verificationBigraphs as { id?: string; label?: string; path: string; stop?: boolean }[])
			: (provider?.formState?.verificationBigraphs?.map((v) => ({
				id: v.id, label: v.label, path: v.path, stop: v.stop
			})) ?? []),
		maxOperationsEnabled: msg.maxOperationsEnabled !== false,
		maxOperations: typeof msg.maxOperations === 'number' ? msg.maxOperations : 10,
		checkpointFileGeneration: msg.checkpointFileGeneration !== false,
		visualizeIntermediateSteps: !!msg.visualizeIntermediateSteps,
		ruleApplicationStrategy: normalizeRuleApplicationStrategy(
			msg.ruleApplicationStrategy ?? provider?.formState?.ruleApplicationStrategy
		)
	};

	if (provider && !provider.isNewEvolution && provider.evolutionConfigPath && provider.formState?.workspaceBigraph) {
		dispatchEvolutionRunForExisting({
			...basePayload,
			evolutionFolder: provider.evolutionConfigPath,
			workspaceBigraphPath: provider.formState.workspaceBigraph
		}).catch((err) => {
			console.error('[Evolution Manager] dispatchEvolutionRunForExisting failed:', err);
		});
		return;
	}

	prepareEvolutionRunFolder(basePayload).catch((err) => {
		console.error('[Evolution Manager] prepareEvolutionRunFolder failed:', err);
	});
}

// ── Tree node click ──────────────────────────────────────────────────────────

export async function handleTreeNodeClicked(
	msg: { operationId: string; resultPath: string },
	provider: EvolutionManagerViewProvider
): Promise<void> {
	const workspaceBigraph = provider.formState?.workspaceBigraph;
	const doc = provider.evolutionDocument;
	const evolutionFolder = provider.evolutionConfigPath;

	if (!workspaceBigraph || !doc || !evolutionFolder) {
		vscode.window.showWarningMessage('workspace-bigraph path is not set.');
		return;
	}

	await openManagedWorkspaceBigraph(workspaceBigraph, evolutionFolder);
	const existingClientId = getClientIdForFsPath(workspaceBigraph);
	if (existingClientId) {
		await waitForClientSession(existingClientId);
	}

	if (msg.operationId === provider.formState?.checkpointCursor) {
		return;
	}

	const checkpointAbs = msg.resultPath;

	const guard = await guardUnsavedWorkspaceChanges(provider, 'treeNavigation');
	if (guard === 'cancel') { return; }

	const copied = await applyCheckpointWithMeta(
		provider,
		checkpointAbs,
		workspaceBigraph,
		msg.operationId
	);

	doc.setCheckpointCursor(msg.operationId);
	try {
		doc.persist();
	} catch {
		vscode.window.showWarningMessage(`Could not update checkpoint-cursor in ${EVOLUTION_JSON}.`);
	}

	provider.patchFormState({ checkpointCursor: msg.operationId });
	refreshTreeFromModel(provider);

	if (copied) {
		await closeCustomEditorTab(workspaceBigraph);
		await openManagedWorkspaceBigraph(workspaceBigraph, evolutionFolder);
	}
}

/**
 * Copies the checkpoint triplet for the current `checkpointCursor` into `workspace-bigraph`
 * and reopens the diagram so the editor matches evolution.json after the cursor moves.
 */
export async function syncWorkspaceBigraphToCursor(
	provider: EvolutionManagerViewProvider,
	options?: { force?: boolean }
): Promise<void> {
	const evolutionFolder = provider.evolutionConfigPath;
	const workspaceBigraph = provider.formState?.workspaceBigraph;
	if (!evolutionFolder || !workspaceBigraph) {
		return;
	}

	if (!options?.force) {
		const guard = await guardUnsavedWorkspaceChanges(provider, 'generic');
		if (guard === 'cancel') { return; }
	}

	const cursorId = provider.formState?.checkpointCursor ?? null;
	let checkpointAbs: string | null = null;

	if (cursorId) {
		const op = provider.formState?.operations?.find((o) => o.id === cursorId);
		if (!op?.result) { return; }
		checkpointAbs = op.result;
	} else {
		checkpointAbs = originalCheckpointPath(evolutionFolder);
	}

	const copied = await applyCheckpointWithMeta(
		provider,
		checkpointAbs,
		workspaceBigraph,
		cursorId
	);

	if (copied) {
		await closeCustomEditorTab(workspaceBigraph);
	}
	await openManagedWorkspaceBigraph(workspaceBigraph, evolutionFolder);

	const clientId = getClientIdForFsPath(workspaceBigraph);
	if (clientId) {
		resetSessionDirtyAndBroadcast(clientId);
	}
}
