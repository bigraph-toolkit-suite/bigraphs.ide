import * as vscode from 'vscode';
import { getBigraphExplorerProvider } from '../bigraphsExplorer/init';

function delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function getCreatedBigraphArtifacts(bigraphUri: vscode.Uri): vscode.Uri[] {
    const bigraphPath = bigraphUri.fsPath;
    return [
        bigraphUri,
        vscode.Uri.file(bigraphPath.replace(/\.xmi$/i, '.signature.xmi')),
        vscode.Uri.file(bigraphPath.replace(/\.xmi$/i, '.signature.ecore'))
    ];
}

async function waitForBackendCreatedBigraph(bigraphUri: vscode.Uri, timeoutMs = 5000): Promise<boolean> {
    const artifacts = getCreatedBigraphArtifacts(bigraphUri);
    const deadline = Date.now() + timeoutMs;

    while (Date.now() <= deadline) {
        const ready = await Promise.all(artifacts.map(async artifact => {
            try {
                await vscode.workspace.fs.stat(artifact);
                return true;
            } catch {
                return false;
            }
        }));

        if (ready.every(Boolean)) {
            return true;
        }
        await delay(100);
    }

    return false;
}

async function fileExists(uri: vscode.Uri): Promise<boolean> {
    try {
        await vscode.workspace.fs.stat(uri);
        return true;
    } catch {
        return false;
    }
}

async function getSuggestedBigraphName(rootUri: vscode.Uri): Promise<string> {
    let counter = 0;
    const baseName = 'new-bigraph';
    let name = baseName;

    while (await fileExists(vscode.Uri.joinPath(rootUri, `${name}.xmi`))) {
        counter++;
        name = `${baseName}${counter}`;
    }

    return name;
}

async function getNewBigraphUri(rootUri: vscode.Uri): Promise<vscode.Uri | undefined> {
    const suggestedName = await getSuggestedBigraphName(rootUri);
    const newName = await vscode.window.showInputBox({
        value: suggestedName,
        prompt: 'Enter name for new bigraph',
        placeHolder: 'Name',
        valueSelection: [0, suggestedName.length]
    });

    if (!newName) {
        return undefined;
    }

    const finalName = newName.endsWith('.xmi') ? newName : `${newName}.xmi`;
    const newFileUri = vscode.Uri.joinPath(rootUri, finalName);
    if (await fileExists(newFileUri)) {
        vscode.window.showErrorMessage(`File '${finalName}' already exists.`);
        return undefined;
    }

    return newFileUri;
}

export function registerCreateBigraphCommand(editorReady: Promise<void>): vscode.Disposable {
    return vscode.commands.registerCommand('bigraph.create', async () => {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        if (!workspaceFolders) {
            vscode.window.showErrorMessage('No workspace open');
            return;
        }

        const rootUri = workspaceFolders[0].uri;
        const newFileUri = await getNewBigraphUri(rootUri);
        if (!newFileUri) {
            return;
        }

        const finalName = newFileUri.path.split('/').pop() ?? newFileUri.fsPath;

        try {
            await editorReady;

            const { dispatchActionInUtilitySession } = await import('../editor/init.js');
            const created = await dispatchActionInUtilitySession(
                { kind: 'bigraph.create', path: newFileUri.toString() },
                async () => {
                    const ready = await waitForBackendCreatedBigraph(newFileUri);
                    if (!ready) {
                        vscode.window.showErrorMessage(`Timed out waiting for backend to create '${finalName}'.`);
                    }
                    return ready;
                }
            );
            if (!created) {
                return;
            }

            getBigraphExplorerProvider()?.refresh();
            await vscode.commands.executeCommand('vscode.openWith', newFileUri, 'bigraph.glspDiagram');
        } catch (error) {
            vscode.window.showErrorMessage(`Failed to create bigraph: ${error}`);
        }
    });
}
