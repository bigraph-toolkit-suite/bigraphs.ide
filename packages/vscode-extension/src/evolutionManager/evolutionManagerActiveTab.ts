import * as vscode from 'vscode';
import { getActiveClientInfo } from '../editor/glspClientRegistry.js';
import { getEvolutionManagerViewProvider } from './evolutionManagerRegistry.js';

function getActiveXmiFsPath(): string | undefined {
	for (const group of vscode.window.tabGroups.all) {
		for (const tab of group.tabs) {
			if (tab.isActive && tab.input instanceof vscode.TabInputCustom && tab.input.uri.fsPath.endsWith('.xmi')) {
				return tab.input.uri.fsPath;
			}
		}
	}
	return undefined;
}

/** Sends the currently active XMI tab (if any) to the Evolution Manager webview. */
export function notifyEvolutionManagerActiveXmiTab(): void {
	const provider = getEvolutionManagerViewProvider();
	if (!provider) { return; }
	const fsPath = getActiveXmiFsPath();
	const relativePath = fsPath ? vscode.workspace.asRelativePath(fsPath) : null;
	const activeClient = getActiveClientInfo();
	const clientId = fsPath && activeClient?.fsPath === fsPath ? activeClient.clientId : null;
	provider.postMessage({
		type: 'activeTabChanged',
		clientId,
		fsPath: fsPath ?? null,
		relativePath
	});
}
