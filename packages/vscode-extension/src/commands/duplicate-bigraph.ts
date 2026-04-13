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

function randomSuffix(): string {
    return Math.random().toString(36).slice(2, 10);
}

/** True when `a` and `b` denote the same path ignoring ASCII case (for case-only renames on APFS/NTFS). */
function samePathIgnoreCase(a: string, b: string): boolean {
    return a.replace(/\\/g, '/').toLowerCase() === b.replace(/\\/g, '/').toLowerCase();
}

async function pickIntermediatePrefix(dir: string): Promise<string | undefined> {
    for (let i = 0; i < 32; i++) {
        const prefix = path.join(dir, `.__bigraph_rename_${randomSuffix()}`);
        let collision = false;
        for (const suf of ARTIFACT_SUFFIXES) {
            if (await uriExists(vscode.Uri.file(prefix + suf))) {
                collision = true;
                break;
            }
        }
        if (!collision) {
            return prefix;
        }
    }
    vscode.window.showErrorMessage('Could not pick a temporary name for rename.');
    return undefined;
}

async function renameArtifactPrefix(fromPrefix: string, toPrefix: string): Promise<boolean> {
    let renamedMain = false;
    for (const suf of ARTIFACT_SUFFIXES) {
        const from = vscode.Uri.file(fromPrefix + suf);
        if (!(await uriExists(from))) {
            continue;
        }
        await vscode.workspace.fs.rename(from, vscode.Uri.file(toPrefix + suf));
        if (suf === '.xmi') {
            renamedMain = true;
        }
    }
    return renamedMain;
}

/**
 * Renames all on-disk artifacts for a bigraph (same folder, new base name).
 * Uses a two-step rename when only letter case changes (case-insensitive volumes).
 */
export async function renameBigraphArtifacts(sourceXmiUri: vscode.Uri, newBaseName: string): Promise<vscode.Uri | undefined> {
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
        vscode.window.showErrorMessage('New name must differ from the current name.');
        return undefined;
    }

    const newPrefix = path.join(dir, sanitized);
    const mainSrc = vscode.Uri.file(oldPrefix + '.xmi');
    if (!(await uriExists(mainSrc))) {
        vscode.window.showErrorMessage('Source bigraph file is missing.');
        return undefined;
    }

    const caseOnlyRename = oldPrefix !== newPrefix && samePathIgnoreCase(oldPrefix, newPrefix);

    if (!caseOnlyRename) {
        for (const suf of ARTIFACT_SUFFIXES) {
            const dest = vscode.Uri.file(newPrefix + suf);
            if (await uriExists(dest)) {
                vscode.window.showErrorMessage(`A file already exists: ${path.basename(dest.fsPath)}`);
                return undefined;
            }
        }
    }

    try {
        if (caseOnlyRename) {
            const intermediate = await pickIntermediatePrefix(dir);
            if (!intermediate) {
                return undefined;
            }
            if (!(await renameArtifactPrefix(oldPrefix, intermediate))) {
                vscode.window.showErrorMessage('Could not rename the bigraph file.');
                return undefined;
            }
            if (!(await renameArtifactPrefix(intermediate, newPrefix))) {
                vscode.window.showErrorMessage('Could not finish renaming the bigraph file.');
                return undefined;
            }
        } else if (!(await renameArtifactPrefix(oldPrefix, newPrefix))) {
            vscode.window.showErrorMessage('Could not rename the bigraph file.');
            return undefined;
        }
    } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        vscode.window.showErrorMessage(`Rename failed: ${msg}`);
        return undefined;
    }

    return vscode.Uri.file(newPrefix + '.xmi');
}
