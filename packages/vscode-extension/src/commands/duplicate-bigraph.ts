import * as vscode from 'vscode';
import * as path from 'path';

/**
 * Filename suffixes for a bigraph instance, relative to the shared path prefix
 * (prefix = absolute path without trailing `.xmi` of the main instance file).
 */
const ARTIFACT_SUFFIXES = ['.xmi', '.signature.xmi', '.signature.ecore', '.bigraph-meta'] as const;

function sanitizeNewBaseName(input: string): string | undefined {
    let s = input.trim();
    if (s.toLowerCase().endsWith('.xmi')) {
        s = s.slice(0, -4);
    }
    if (s.length === 0) {
        return undefined;
    }
    if (/[/\\]/.test(s) || s === '.' || s === '..') {
        return undefined;
    }
    return s;
}

async function uriExists(uri: vscode.Uri): Promise<boolean> {
    try {
        await vscode.workspace.fs.stat(uri);
        return true;
    } catch {
        return false;
    }
}

/**
 * Duplicates all on-disk artifacts for a bigraph into the same folder with a new base name.
 * Copies only files that exist (main `.xmi` must exist).
 */
export async function duplicateBigraphArtifacts(sourceXmiUri: vscode.Uri, newBaseName: string): Promise<vscode.Uri | undefined> {
    const sanitized = sanitizeNewBaseName(newBaseName);
    if (!sanitized) {
        vscode.window.showErrorMessage('Invalid name.');
        return undefined;
    }

    const sourceFs = sourceXmiUri.fsPath;
    if (!sourceFs.toLowerCase().endsWith('.xmi') || sourceFs.endsWith('.signature.xmi')) {
        vscode.window.showErrorMessage('Not a bigraph instance file.');
        return undefined;
    }

    const dir = path.dirname(sourceFs);
    const oldPrefix = sourceFs.replace(/\.xmi$/i, '');
    const oldLabel = path.basename(oldPrefix);

    if (sanitized === oldLabel) {
        vscode.window.showErrorMessage('New name must differ from the current bigraph name.');
        return undefined;
    }

    const newPrefix = path.join(dir, sanitized);

    for (const suf of ARTIFACT_SUFFIXES) {
        const dest = vscode.Uri.file(newPrefix + suf);
        if (await uriExists(dest)) {
            vscode.window.showErrorMessage(`A file already exists: ${path.basename(dest.fsPath)}`);
            return undefined;
        }
    }

    let copiedMain = false;
    for (const suf of ARTIFACT_SUFFIXES) {
        const srcUri = vscode.Uri.file(oldPrefix + suf);
        if (!(await uriExists(srcUri))) {
            if (suf === '.xmi') {
                vscode.window.showErrorMessage('Source bigraph file is missing.');
                return undefined;
            }
            continue;
        }
        const destUri = vscode.Uri.file(newPrefix + suf);
        await vscode.workspace.fs.copy(srcUri, destUri, { overwrite: false });
        if (suf === '.xmi') {
            copiedMain = true;
        }
    }

    if (!copiedMain) {
        vscode.window.showErrorMessage('Could not copy the bigraph file.');
        return undefined;
    }

    return vscode.Uri.file(newPrefix + '.xmi');
}
