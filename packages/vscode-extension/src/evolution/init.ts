import * as vscode from 'vscode';
import { EvolutionsProvider } from '../evolutionsList/evolutionsProvider';
import { EvolutionManagerViewProvider } from './evolutionManagerViewProvider';

/** Returns the fsPath of the currently active XMI tab, or undefined. */
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
    const { getEvolutionManagerViewProvider } = require('./evolutionManagerViewProvider') as typeof import('./evolutionManagerViewProvider');
    const provider = getEvolutionManagerViewProvider();
    if (!provider) { return; }
    const fsPath = getActiveXmiFsPath();
    const relativePath = fsPath ? vscode.workspace.asRelativePath(fsPath) : null;
    provider.postMessage({
        type: 'activeTabChanged',
        clientId: null,
        fsPath: fsPath ?? null,
        relativePath
    });
}

let _evolutionsProvider: EvolutionsProvider | null = null;

/** Refreshes the Evolutions list panel (e.g. after a new evolution folder is created). */
export function refreshEvolutionsList(): void {
    _evolutionsProvider?.refresh();
}

export function initEvolutionSidebar(context: vscode.ExtensionContext): vscode.Disposable[] {
    const disposables: vscode.Disposable[] = [];

    const evolutionsProvider = new EvolutionsProvider();
    _evolutionsProvider = evolutionsProvider;
    const evolutionsTreeView = vscode.window.createTreeView('evolutions', {
        treeDataProvider: evolutionsProvider
    });
    disposables.push(evolutionsTreeView);

    const evolutionManagerProvider = new EvolutionManagerViewProvider(context.extensionUri);
    disposables.push(
        vscode.window.registerWebviewViewProvider('evolutionManagerView', evolutionManagerProvider)
    );

    disposables.push(
        vscode.commands.registerCommand('bigraph.selectEvolutionFolder', async (folderPath: string) => {
            evolutionManagerProvider.setEvolutionFolder(folderPath);
            await vscode.commands.executeCommand('workbench.view.extension.bigraph-explorer-container');
            await vscode.commands.executeCommand('evolutionManagerView.focus');
        })
    );

    // Track active XMI tab changes and push to the Evolution Manager
    disposables.push(
        vscode.window.tabGroups.onDidChangeTabGroups(() => notifyEvolutionManagerActiveXmiTab())
    );

    return disposables;
}