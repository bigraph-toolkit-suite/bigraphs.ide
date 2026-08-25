import * as vscode from 'vscode';
import { getBigraphExplorerCreateTargetFolder, getBigraphExplorerProvider } from '../bigraphsExplorer/init';
import { getModelVariants, ModelVariantDescriptor } from '../extension/extensions';
import { META_EXTENSION } from '../workspaceBigraph/bigraphArtifacts';

function delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function getCreatedBigraphArtifacts(bigraphUri: vscode.Uri, variant: ModelVariantDescriptor): vscode.Uri[] {
    const bigraphPath = bigraphUri.fsPath;
    const artifacts: vscode.Uri[] = [
        bigraphUri,
        vscode.Uri.file(bigraphPath.replace(/\.xmi$/i, '.signature.xmi')),
        vscode.Uri.file(bigraphPath.replace(/\.xmi$/i, '.signature.ecore'))
    ];
    if (variant.writesMetaFile) {
        artifacts.push(vscode.Uri.file(bigraphPath.replace(/\.xmi$/i, META_EXTENSION)));
    }
    return artifacts;
}

async function waitForBackendCreatedBigraph(
    bigraphUri: vscode.Uri,
    variant: ModelVariantDescriptor,
    timeoutMs = 5000
): Promise<boolean> {
    const artifacts = getCreatedBigraphArtifacts(bigraphUri, variant);
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

async function getSuggestedName(rootUri: vscode.Uri, variant: ModelVariantDescriptor): Promise<string> {
    let counter = 0;
    let name = variant.fileNameBase;
    while (await fileExists(vscode.Uri.joinPath(rootUri, `${name}.xmi`))) {
        counter++;
        name = `${variant.fileNameBase}${counter}`;
    }
    return name;
}

async function pickModelVariant(): Promise<ModelVariantDescriptor | undefined> {
    const variants = getModelVariants();
    if (variants.length === 0) {
        vscode.window.showErrorMessage('No model variants are registered.');
        return undefined;
    }
    // Skip the picker when only a single variant is available — no
    // value in asking the user to "choose" between one option.
    if (variants.length === 1) {
        return variants[0];
    }
    const choice = await vscode.window.showQuickPick(
        variants.map(variant => ({
            label: `$(${variant.iconCodicon}) ${variant.label}`,
            description: variant.description,
            variant,
        })),
        {
            title: 'Create new model',
            placeHolder: 'Choose model variant',
        }
    );
    return choice?.variant;
}

async function getNewBigraphUri(
    rootUri: vscode.Uri,
    variant: ModelVariantDescriptor
): Promise<vscode.Uri | undefined> {
    const suggestedName = await getSuggestedName(rootUri, variant);
    const newName = await vscode.window.showInputBox({
        value: suggestedName,
        prompt: `Enter name for new ${variant.label.toLowerCase()}`,
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

        const variant = await pickModelVariant();
        if (!variant) {
            return;
        }

        const rootUri = getBigraphExplorerCreateTargetFolder(workspaceFolders[0].uri);
        const newFileUri = await getNewBigraphUri(rootUri, variant);
        if (!newFileUri) {
            return;
        }

        const finalName = newFileUri.path.split('/').pop() ?? newFileUri.fsPath;

        try {
            await editorReady;

            const { dispatchActionInUtilitySession } = await import('../editor/init.js');
            const created = await dispatchActionInUtilitySession(
                {
                    kind: 'bigraph.create',
                    path: newFileUri.toString(),
                    // Canonical variant id — backend resolves it via
                    // ModelVariantRegistry. Unknown ids fall back to
                    // the built-in "bigraph" variant on the server.
                    modelType: variant.id
                },
                async () => {
                    const ready = await waitForBackendCreatedBigraph(newFileUri, variant);
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
