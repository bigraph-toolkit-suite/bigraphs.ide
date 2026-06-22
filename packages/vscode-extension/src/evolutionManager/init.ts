import * as vscode from 'vscode';
import { EvolutionsProvider } from '../evolutionsExplorer/evolutionsProvider';
import { EvolutionManagerViewProvider } from './evolutionManagerViewProvider';
import { getEvolutionManagerViewProvider } from './evolutionManagerRegistry.js';
import { notifyEvolutionManagerActiveXmiTab } from './evolutionManagerActiveTab.js';
import { registerEvolutionsProvider } from '../evolutionsExplorer/evolutionsListRegistry.js';

export { notifyEvolutionManagerActiveXmiTab } from './evolutionManagerActiveTab.js';
export { refreshEvolutionsList } from '../evolutionsExplorer/evolutionsListRegistry.js';

export function initEvolutionSidebar(context: vscode.ExtensionContext): vscode.Disposable[] {
    const disposables: vscode.Disposable[] = [];

    const evolutionsProvider = new EvolutionsProvider();
    registerEvolutionsProvider(evolutionsProvider);
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

    disposables.push(
        vscode.commands.registerCommand('bigraph.newEvolutionProject', async () => {
            const provider = getEvolutionManagerViewProvider();
            if (!provider) {
                vscode.window.showWarningMessage('Evolution Manager is not ready yet.');
                return;
            }
            provider.setNewEvolution();
            await vscode.commands.executeCommand('workbench.view.extension.bigraph-explorer-container');
            await vscode.commands.executeCommand('evolutionManagerView.focus');
            notifyEvolutionManagerActiveXmiTab();
        })
    );

    disposables.push(
        vscode.window.tabGroups.onDidChangeTabGroups(() => notifyEvolutionManagerActiveXmiTab())
    );

    return disposables;
}
