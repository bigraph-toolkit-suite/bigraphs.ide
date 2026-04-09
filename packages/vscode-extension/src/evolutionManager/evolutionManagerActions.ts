import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import {
	prepareEvolutionRunFolder,
	dispatchEvolutionRunForExisting,
	bigraphTripletsDiffer,
	copyBigraphTripletIfDifferent,
	type EvolutionActionPayload
} from './runEvolution.js';
import type { EvolutionManagerViewProvider } from './evolutionManagerViewProvider.js';

// ── HTML loader ──────────────────────────────────────────────────────────────

export async function loadEvolutionManagerHtml(
	webview: vscode.Webview,
	extensionUri: vscode.Uri
): Promise<string> {
	// HTML lives next to this file; compiled JS lives in dist/
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
	const { getGlspConnector } = await import('../editor/init.js');
	const glspConnector = getGlspConnector();
	if (!glspConnector) {
		vscode.window.showErrorMessage('GLSP connector not available.');
		return;
	}
	glspConnector.dispatchAction(
		{ kind: 'bigraph.evolutionRun', actionType: 'pause', operationId },
		clientId ?? undefined
	);
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
		visualizeIntermediateSteps: !!msg.visualizeIntermediateSteps
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
	const evolutionFolder  = provider.evolutionConfigPath!;
	const evoJsonPath      = path.join(evolutionFolder, 'evolution.json');
	const workspaceBigraph = provider.formState?.workspaceBigraph;

	if (!workspaceBigraph) {
		vscode.window.showWarningMessage('workspace-bigraph path is not set.');
		return;
	}

	const checkpointAbs = msg.resultPath;
	if (!fs.existsSync(checkpointAbs)) {
		vscode.window.showWarningMessage(`Checkpoint file not found: ${checkpointAbs}`);
		return;
	}

	const wsBase    = workspaceBigraph.replace(/\.xmi$/, '');
	const newCpBase = checkpointAbs.replace(/\.xmi$/, '');

	// Warn if workspace-bigraph was manually edited since the current cursor checkpoint
	const currentCursorId = provider.formState?.checkpointCursor;
	const currentCursorOp = provider.formState?.operations?.find((op) => op.id === currentCursorId);
	const currentCursorResultAbs = currentCursorOp?.result ?? null;

	if (currentCursorResultAbs && fs.existsSync(currentCursorResultAbs)) {
		const curCpBase = currentCursorResultAbs.replace(/\.xmi$/, '');
		if (bigraphTripletsDiffer(curCpBase, wsBase)) {
			const answer = await vscode.window.showWarningMessage(
				'The workspace bigraph has been manually edited since the last checkpoint. Navigating to a different checkpoint will overwrite your changes. Continue?',
				{ modal: true },
				'Overwrite'
			);
			if (answer !== 'Overwrite') { return; }
		}
	}

	const copied = copyBigraphTripletIfDifferent(newCpBase, wsBase);

	try {
		const raw  = fs.readFileSync(evoJsonPath, 'utf8');
		const json = JSON.parse(raw) as Record<string, unknown>;
		json['checkpoint-cursor'] = msg.operationId;
		fs.writeFileSync(evoJsonPath, JSON.stringify(json, null, 2) + '\n', 'utf8');
	} catch {
		vscode.window.showWarningMessage('Could not update checkpoint-cursor in evolution.json.');
	}

	provider.patchFormState({ checkpointCursor: msg.operationId });
	provider.refreshTreeFromJson();

	if (copied) {
		for (const group of vscode.window.tabGroups.all) {
			for (const tab of group.tabs) {
				if (tab.input instanceof vscode.TabInputCustom && tab.input.uri.fsPath === workspaceBigraph) {
					await vscode.window.tabGroups.close(tab);
				}
			}
		}
		await new Promise((resolve) => setTimeout(resolve, 100));
	}
	await vscode.commands.executeCommand('vscode.openWith', vscode.Uri.file(workspaceBigraph), 'bigraph.glspDiagram');
}

/**
 * Copies the checkpoint triplet for the current `checkpointCursor` into `workspace-bigraph`
 * and reopens the diagram so the editor matches evolution.json after the cursor moves.
 * When the cursor is null, uses `checkpoints/original.xmi` when present.
 * With `force`, skips overwrite prompts (e.g. after checkpoint deletion).
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

	const cursorId = provider.formState?.checkpointCursor ?? null;
	let checkpointAbs: string | null = null;

	if (cursorId) {
		const op = provider.formState?.operations?.find((o) => o.id === cursorId);
		if (!op?.result) {
			return;
		}
		if (!fs.existsSync(op.result)) {
			vscode.window.showWarningMessage(`Checkpoint file not found: ${op.result}`);
			return;
		}
		checkpointAbs = op.result;
	} else {
		const original = path.join(evolutionFolder, 'checkpoints', 'original.xmi');
		if (!fs.existsSync(original)) {
			return;
		}
		checkpointAbs = original;
	}

	const wsBase = workspaceBigraph.replace(/\.xmi$/, '');
	const newCpBase = checkpointAbs.replace(/\.xmi$/, '');

	if (!options?.force) {
		const currentCursorId = provider.formState?.checkpointCursor;
		const currentCursorOp = provider.formState?.operations?.find((op) => op.id === currentCursorId);
		const currentCursorResultAbs = currentCursorOp?.result ?? null;

		if (currentCursorResultAbs && fs.existsSync(currentCursorResultAbs)) {
			const curCpBase = currentCursorResultAbs.replace(/\.xmi$/, '');
			if (bigraphTripletsDiffer(curCpBase, wsBase)) {
				const answer = await vscode.window.showWarningMessage(
					'The workspace bigraph differs from the checkpoint the cursor is pointing to. Overwrite workspace-bigraph with the checkpoint?',
					{ modal: true },
					'Overwrite'
				);
				if (answer !== 'Overwrite') {
					return;
				}
			}
		}
	}

	const copied = copyBigraphTripletIfDifferent(newCpBase, wsBase);

	if (copied) {
		for (const group of vscode.window.tabGroups.all) {
			for (const tab of group.tabs) {
				if (tab.input instanceof vscode.TabInputCustom && tab.input.uri.fsPath === workspaceBigraph) {
					await vscode.window.tabGroups.close(tab);
				}
			}
		}
		await new Promise((resolve) => setTimeout(resolve, 100));
	}
	await vscode.commands.executeCommand('vscode.openWith', vscode.Uri.file(workspaceBigraph), 'bigraph.glspDiagram');
}
