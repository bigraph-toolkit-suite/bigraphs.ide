import * as vscode from 'vscode';
import { getEvolutionManagerViewProvider } from '../evolutionManager/evolutionManagerRegistry.js';
import { markPendingManagedOpen } from './sessionRegistry.js';

export async function openManagedWorkspaceBigraph(
	fsPath: string,
	evolutionFolder: string
): Promise<void> {
	markPendingManagedOpen(fsPath, evolutionFolder);
	await vscode.commands.executeCommand('vscode.openWith', vscode.Uri.file(fsPath), 'bigraph.glspDiagram');
}

export async function openWorkspaceBigraphFromExplorer(fsPath: string, evolutionFolder: string): Promise<void> {
	const provider = getEvolutionManagerViewProvider();
	if (provider) {
		provider.setEvolutionFolder(evolutionFolder, { skipOpenBigraph: true });
		return;
	}
	await openManagedWorkspaceBigraph(fsPath, evolutionFolder);
}
