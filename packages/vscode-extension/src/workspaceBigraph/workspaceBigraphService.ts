import * as path from 'path';
import * as vscode from 'vscode';
import { getEvolutionManagerViewProvider } from '../evolutionManager/evolutionManagerRegistry.js';
import type { EvolutionManagerViewProvider } from '../evolutionManager/evolutionManagerViewProvider.js';
import { refreshTreeFromModel } from '../evolutionManager/evolutionManagerJsonIO.js';
import { originalCheckpointPath } from '../evolutionManager/evolutionPaths.js';
import {
	bigraphTripletsDiffer,
	copyBigraphTripletIfDifferent,
} from '../evolutionManager/runEvolution.js';
import { sendActionToServer } from '../editor/glspServerBridge.js';
import {
	getAllGlspClientIds,
	getClientIdForFsPath,
	getFsPathForClientId,
	getPanelForClientId,
} from '../editor/glspClientRegistry.js';
import {
	applyMetaToWorkspace,
	copyWorkspaceMetaToCheckpoint,
	resolveInheritedMetaPath,
} from './metaInheritance.js';
import {
	clearSessionDirty,
	getSession,
	getSessionForFsPath,
} from './sessionRegistry.js';
import {
	basePathFromXmi,
	closeCustomEditorTab,
	copyMetaFileIfExists,
	isWorkspaceBigraphPath,
} from './bigraphArtifacts.js';
import { openManagedWorkspaceBigraph } from './openWorkspaceBigraph.js';

export type UnsavedDialogChoice =
	| 'commitStructure'
	| 'ignore'
	| 'keepEditing';

export interface WorkspaceBarStatePayload {
	visible: boolean;
	alignmentDirty?: boolean;
	structureDirty?: boolean;
	evolutionRunning?: boolean;
	revertAlignmentEnabled?: boolean;
	commitEnabled?: boolean;
	revertStructureEnabled?: boolean;
}

export function postWorkspaceBarState(clientId: string): void {
	const panel = getPanelForClientId(clientId);
	if (!panel) { return; }
	panel.webview.postMessage({ type: 'workspaceBarState', ...buildWorkspaceBarState(clientId) });
}

export function postWorkspaceBarStateToAllPanels(): void {
	for (const clientId of getAllGlspClientIds()) {
		postWorkspaceBarState(clientId);
	}
}

export function resetSessionDirtyAndBroadcast(clientId: string): void {
	clearSessionDirty(clientId);
	postWorkspaceBarState(clientId);
}

export async function showUnsavedChangesDialog(
	context: 'save' | 'treeNavigation' | 'generic',
	structureDirty: boolean
): Promise<UnsavedDialogChoice | undefined> {
	if (!structureDirty) { return undefined; }

	const items: vscode.QuickPickItem[] = [
		{
			label: 'Commit structure',
			description: 'Create a new manual checkpoint',
			alwaysShow: true,
		},
		{ label: 'Ignore', description: 'Discard changes and continue', alwaysShow: true },
		{ label: 'Keep editing', description: 'Cancel and return to the editor', alwaysShow: true },
	];

	const title = context === 'treeNavigation'
		? 'Unsaved workspace bigraph changes'
		: 'Save workspace bigraph';

	const picked = await vscode.window.showQuickPick(items, {
		title,
		placeHolder: 'Choose how to handle your changes',
		ignoreFocusOut: true,
	});
	if (!picked) { return 'keepEditing'; }

	switch (picked.label) {
		case 'Commit structure': return 'commitStructure';
		case 'Ignore': return 'ignore';
		default: return 'keepEditing';
	}
}

export async function flushGlspModelToDisk(clientId: string, fsPath: string): Promise<void> {
	const fileUri = vscode.Uri.file(fsPath).toString();
	sendActionToServer({ kind: 'saveModel', fileUri }, clientId);
	// TODO: replace blind sleep with SetDirtyStateAction(reason:'save') acknowledgment
	await new Promise((resolve) => setTimeout(resolve, 200));
}

function randomId(length: number): string {
	const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
	let result = '';
	for (let i = 0; i < length; i++) {
		result += chars[Math.floor(Math.random() * chars.length)];
	}
	return result;
}

export function getCursorCheckpointXmi(provider: EvolutionManagerViewProvider): string | null {
	const cursorId = provider.formState?.checkpointCursor ?? null;
	if (!cursorId) {
		const original = provider.evolutionConfigPath
			? originalCheckpointPath(provider.evolutionConfigPath)
			: null;
		return original ? original : null;
	}
	const op = provider.formState?.operations?.find((o) => o.id === cursorId);
	return op?.result || null;
}

export function buildWorkspaceBarState(clientId: string): WorkspaceBarStatePayload {
	const session = getSession(clientId);
	const fsPath = getFsPathForClientId(clientId);
	if (!session || !fsPath || !session.managed || !isWorkspaceBigraphPath(fsPath)) {
		return { visible: false };
	}

	const provider = getEvolutionManagerViewProvider();
	const hasCheckpoint = provider ? !!getCursorCheckpointXmi(provider) : false;

	return {
		visible:                true,
		alignmentDirty:         session.alignmentDirty,
		structureDirty:         session.structureDirty,
		evolutionRunning:       session.evolutionRunning,
		revertAlignmentEnabled: hasCheckpoint && session.alignmentDirty,
		commitEnabled:          session.structureDirty,
		revertStructureEnabled: session.structureDirty,
	};
}

export async function saveAlignment(
	provider: EvolutionManagerViewProvider,
	clientId: string
): Promise<void> {
	const workspaceBigraph = provider.formState?.workspaceBigraph;
	const checkpointXmi = getCursorCheckpointXmi(provider);
	if (!workspaceBigraph || !checkpointXmi) {
		vscode.window.showWarningMessage('No checkpoint is selected for alignment save.');
		return;
	}

	await flushGlspModelToDisk(clientId, workspaceBigraph);
	copyWorkspaceMetaToCheckpoint(workspaceBigraph, checkpointXmi);
	resetSessionDirtyAndBroadcast(clientId);
	vscode.window.showInformationMessage('Alignment saved to checkpoint.');
}

export async function revertAlignment(
	provider: EvolutionManagerViewProvider,
	clientId: string
): Promise<void> {
	const workspaceBigraph = provider.formState?.workspaceBigraph;
	if (!workspaceBigraph) { return; }

	const cursorId = provider.formState?.checkpointCursor ?? null;
	const checkpointXmi = getCursorCheckpointXmi(provider);
	const operations = provider.formState?.operations ?? [];

	const metaSrc = resolveInheritedMetaPath(operations, cursorId, checkpointXmi);
	applyMetaToWorkspace(metaSrc, workspaceBigraph);

	await reloadWorkspaceBigraphTab(workspaceBigraph);
	resetSessionDirtyAndBroadcast(clientId);
}

export async function revertStructure(
	provider: EvolutionManagerViewProvider,
	clientId: string
): Promise<void> {
	const workspaceBigraph = provider.formState?.workspaceBigraph;
	if (!workspaceBigraph) { return; }

	const cursorId = provider.formState?.checkpointCursor ?? null;
	const checkpointXmi = getCursorCheckpointXmi(provider);
	if (!checkpointXmi) {
		vscode.window.showWarningMessage('No checkpoint to revert to.');
		return;
	}

	const cpBase = basePathFromXmi(checkpointXmi);
	const wsBase = basePathFromXmi(workspaceBigraph);
	copyBigraphTripletIfDifferent(cpBase, wsBase);

	const operations = provider.formState?.operations ?? [];
	const metaSrc = resolveInheritedMetaPath(operations, cursorId, checkpointXmi);
	applyMetaToWorkspace(metaSrc, workspaceBigraph);

	await reloadWorkspaceBigraphTab(workspaceBigraph);
	resetSessionDirtyAndBroadcast(clientId);
}

export async function commitStructure(
	provider: EvolutionManagerViewProvider,
	clientId: string,
	includeAlignment: boolean
): Promise<void> {
	const evolutionFolder = provider.evolutionConfigPath;
	const workspaceBigraph = provider.formState?.workspaceBigraph;
	if (!evolutionFolder || !workspaceBigraph) { return; }

	await flushGlspModelToDisk(clientId, workspaceBigraph);

	const doc = provider.evolutionDocument;
	if (!doc) { return; }

	const operationId = randomId(10);
	const predecessorId = provider.formState?.checkpointCursor;
	const checkpointsDir = path.join(evolutionFolder, 'checkpoints');
	const checkpointXmiAbs = path.join(checkpointsDir, `checkpoint-${operationId}.xmi`);
	const cpBase = basePathFromXmi(checkpointXmiAbs);
	const wsBase = basePathFromXmi(workspaceBigraph);

	copyBigraphTripletIfDifferent(wsBase, cpBase);
	if (includeAlignment) {
		copyWorkspaceMetaToCheckpoint(workspaceBigraph, checkpointXmiAbs);
	}

	const relResult = `checkpoints/checkpoint-${operationId}.xmi`;
	doc.addManualOperation({ id: operationId, predecessorId: predecessorId ?? null, relResult });
	doc.persist();

	const operations = doc.getOperations();
	provider.patchFormState({ checkpointCursor: operationId, operations });
	refreshTreeFromModel(provider);
	resetSessionDirtyAndBroadcast(clientId);
	vscode.window.showInformationMessage('Structural changes committed.');
}

async function reloadWorkspaceBigraphTab(workspaceBigraph: string): Promise<void> {
	await closeCustomEditorTab(workspaceBigraph);
	const provider = getEvolutionManagerViewProvider();
	if (provider?.evolutionConfigPath) {
		await openManagedWorkspaceBigraph(workspaceBigraph, provider.evolutionConfigPath);
	} else {
		await vscode.commands.executeCommand('vscode.openWith', vscode.Uri.file(workspaceBigraph), 'bigraph.glspDiagram');
	}
}

export async function applyCheckpointWithMeta(
	provider: EvolutionManagerViewProvider,
	checkpointXmiAbs: string,
	workspaceBigraph: string,
	cursorOperationId: string | null
): Promise<boolean> {
	const cpBase = basePathFromXmi(checkpointXmiAbs);
	const wsBase = basePathFromXmi(workspaceBigraph);
	const copiedTriplet = copyBigraphTripletIfDifferent(cpBase, wsBase);

	const operations = provider.formState?.operations ?? [];
	const metaSrc = resolveInheritedMetaPath(operations, cursorOperationId, checkpointXmiAbs);
	const hadMeta = !!metaSrc;
	applyMetaToWorkspace(metaSrc, workspaceBigraph);

	return copiedTriplet || hadMeta;
}

export async function handleUnsavedChoice(
	choice: UnsavedDialogChoice,
	provider: EvolutionManagerViewProvider,
	clientId: string
): Promise<'proceed' | 'cancel'> {
	const session = getSession(clientId);
	const includeAlignment = !!session?.alignmentDirty;

	switch (choice) {
		case 'commitStructure':
			await commitStructure(provider, clientId, includeAlignment);
			return 'proceed';
		case 'ignore':
			await revertStructure(provider, clientId);
			return 'proceed';
		case 'keepEditing':
		default:
			return 'cancel';
	}
}

async function autoSaveAlignmentIfNeeded(
	provider: EvolutionManagerViewProvider,
	clientId: string,
	session: { alignmentDirty: boolean; structureDirty: boolean }
): Promise<boolean> {
	if (!session.alignmentDirty || session.structureDirty) {
		return false;
	}
	await saveAlignment(provider, clientId);
	return true;
}

export async function guardUnsavedWorkspaceChanges(
	provider: EvolutionManagerViewProvider,
	context: 'save' | 'treeNavigation' | 'generic'
): Promise<'proceed' | 'cancel'> {
	const workspaceBigraph = provider.formState?.workspaceBigraph;
	if (!workspaceBigraph) { return 'proceed'; }

	const clientId = getClientIdForFsPath(workspaceBigraph);
	const session = clientId ? getSession(clientId) : getSessionForFsPath(workspaceBigraph);
	if (!session?.managed) { return 'proceed'; }
	if (!session.alignmentDirty && !session.structureDirty) { return 'proceed'; }
	if (!clientId) { return 'cancel'; }

	if (await autoSaveAlignmentIfNeeded(provider, clientId, session)) {
		return 'proceed';
	}

	const choice = await showUnsavedChangesDialog(context, session.structureDirty);
	if (!choice || choice === 'keepEditing') { return 'cancel'; }
	return handleUnsavedChoice(choice, provider, clientId);
}

export async function handleManagedSave(document: vscode.CustomDocument): Promise<boolean> {
	const fsPath = document.uri.fsPath;
	if (!isWorkspaceBigraphPath(fsPath)) { return false; }

	const session = getSessionForFsPath(fsPath);
	if (!session?.managed) { return false; }

	const provider = getEvolutionManagerViewProvider();
	if (!provider) { return false; }

	if (!session.alignmentDirty && !session.structureDirty) {
		return false;
	}

	if (await autoSaveAlignmentIfNeeded(provider, session.clientId, session)) {
		return true;
	}

	const choice = await showUnsavedChangesDialog('save', session.structureDirty);
	if (!choice || choice === 'keepEditing') { return true; }

	await handleUnsavedChoice(choice, provider, session.clientId);
	resetSessionDirtyAndBroadcast(session.clientId);
	return true;
}

export async function handleWorkspaceBarAction(
	clientId: string,
	action: string
): Promise<void> {
	const provider = getEvolutionManagerViewProvider();
	if (!provider?.evolutionConfigPath) { return; }

	const session = getSession(clientId);

	switch (action) {
		case 'revertAlignment':
			await revertAlignment(provider, clientId);
			break;
		case 'commitStructure':
			await commitStructure(provider, clientId, !!session?.alignmentDirty);
			break;
		case 'revertStructure':
			await revertStructure(provider, clientId);
			break;
	}
}
