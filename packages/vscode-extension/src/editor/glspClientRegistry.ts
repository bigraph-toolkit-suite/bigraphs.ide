import * as vscode from 'vscode';

const clientIdToPath = new Map<string, string>();
const clientIdToPanel = new Map<string, vscode.WebviewPanel>();
const panels = new Set<vscode.WebviewPanel>();
let activeClientId: string | undefined;

export function registerGlspClient(
	clientId: string,
	fsPath: string,
	panel: vscode.WebviewPanel
): void {
	clientIdToPath.set(clientId, fsPath);
	clientIdToPanel.set(clientId, panel);
	panels.add(panel);
}

export function unregisterGlspClient(clientId: string, panel: vscode.WebviewPanel): void {
	clientIdToPath.delete(clientId);
	clientIdToPanel.delete(clientId);
	panels.delete(panel);
}

export function setActiveGlspClientId(clientId: string | undefined): void {
	activeClientId = clientId;
}

export function getClientIdForFsPath(fsPath: string): string | undefined {
	for (const [cid, p] of clientIdToPath) {
		if (p === fsPath) { return cid; }
	}
	return undefined;
}

export function getFsPathForClientId(clientId: string): string | undefined {
	return clientIdToPath.get(clientId);
}

export function getPanelForClientId(clientId: string): vscode.WebviewPanel | undefined {
	return clientIdToPanel.get(clientId);
}

export function getAllGlspClientIds(): IterableIterator<string> {
	return clientIdToPath.keys();
}

export function getActiveClientInfo(): { clientId: string; fsPath: string } | undefined {
	if (activeClientId) {
		const fsPath = clientIdToPath.get(activeClientId);
		if (fsPath) { return { clientId: activeClientId, fsPath }; }
	}
	for (const [cid, panel] of clientIdToPanel) {
		if (panel.active) {
			const fsPath = clientIdToPath.get(cid);
			if (fsPath) {
				activeClientId = cid;
				return { clientId: cid, fsPath };
			}
		}
	}
	const lastEntry = [...clientIdToPath.entries()].at(-1);
	if (lastEntry) {
		return { clientId: lastEntry[0], fsPath: lastEntry[1] };
	}
	return undefined;
}

export function postMessageToAllGlspPanels(message: unknown): void {
	for (const panel of panels) {
		panel.webview.postMessage(message);
	}
}
