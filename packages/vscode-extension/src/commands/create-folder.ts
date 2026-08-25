import * as vscode from 'vscode';
import { getBigraphExplorerCreateTargetFolder, getBigraphExplorerProvider } from '../bigraphsExplorer/init';

function validateFolderName(value: string): string | undefined {
    const name = value.trim();
    if (!name) {
        return 'Folder name cannot be empty';
    }
    if (name === '.' || name === '..') {
        return 'Invalid folder name';
    }
    if (/[\\/]/.test(name)) {
        return 'Folder name cannot contain path separators';
    }
    return undefined;
}

export function registerCreateFolderCommand(): vscode.Disposable {
    return vscode.commands.registerCommand('bigraph.createFolder', async () => {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        if (!workspaceFolders) {
            vscode.window.showErrorMessage('No workspace open');
            return;
        }

        const parentUri = getBigraphExplorerCreateTargetFolder(workspaceFolders[0].uri);
        const name = await vscode.window.showInputBox({
            prompt: 'Enter name for new folder',
            placeHolder: 'Folder name',
            validateInput: validateFolderName
        });
        if (name === undefined) {
            return;
        }

        const folderName = name.trim();
        const folderUri = vscode.Uri.joinPath(parentUri, folderName);

        try {
            await vscode.workspace.fs.stat(folderUri);
            vscode.window.showErrorMessage(`Folder '${folderName}' already exists.`);
            return;
        } catch {
            // Does not exist yet — ok to create.
        }

        try {
            await vscode.workspace.fs.createDirectory(folderUri);
            getBigraphExplorerProvider()?.refresh();
        } catch (error) {
            vscode.window.showErrorMessage(`Failed to create folder: ${error}`);
        }
    });
}
