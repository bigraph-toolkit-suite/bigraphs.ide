import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';

import { WORKSPACE_BIGRAPH_XMI, EVOLUTION_FOLDER_SUFFIX } from '../evolutionManager/evolutionConstants.js';

export const TRIPLET_EXTENSIONS = ['.xmi', '.signature.ecore', '.signature.xmi'] as const;
export const META_EXTENSION = '.bigraph-meta';

export function isWorkspaceBigraphPath(fsPath: string): boolean {
	return path.basename(fsPath) === WORKSPACE_BIGRAPH_XMI;
}

export function isInsideEvolutionFolder(fsPath: string): boolean {
	return fsPath.split(path.sep).some((seg) => seg.endsWith(EVOLUTION_FOLDER_SUFFIX));
}

export function getEvolutionFolderFromPath(fsPath: string): string | null {
	const parts = fsPath.split(path.sep);
	const idx = parts.findIndex((seg) => seg.endsWith(EVOLUTION_FOLDER_SUFFIX));
	if (idx < 0) { return null; }
	return parts.slice(0, idx + 1).join(path.sep);
}

export function basePathFromXmi(xmiPath: string): string {
	return xmiPath.replace(/\.xmi$/i, '');
}

export function metaPathFromXmi(xmiPath: string): string {
	return basePathFromXmi(xmiPath) + META_EXTENSION;
}

export function filesEqual(a: string, b: string): boolean {
	if (!fs.existsSync(a) || !fs.existsSync(b)) { return false; }
	return fs.readFileSync(a).equals(fs.readFileSync(b));
}

export function bigraphMetaFilesDiffer(srcBase: string, dstBase: string): boolean {
	const src = metaPathFromXmi(srcBase + '.xmi');
	const dst = metaPathFromXmi(dstBase + '.xmi');
	if (!fs.existsSync(src) && !fs.existsSync(dst)) { return false; }
	if (!fs.existsSync(src) || !fs.existsSync(dst)) { return true; }
	return !filesEqual(src, dst);
}

export function copyMetaFileIfExists(srcXmi: string, dstXmi: string): boolean {
	const srcMeta = metaPathFromXmi(srcXmi);
	const dstMeta = metaPathFromXmi(dstXmi);
	if (!fs.existsSync(srcMeta)) { return false; }
	fs.copyFileSync(srcMeta, dstMeta);
	return true;
}

export function deleteMetaForXmi(xmiPath: string): void {
	const meta = metaPathFromXmi(xmiPath);
	try {
		if (fs.existsSync(meta)) { fs.unlinkSync(meta); }
	} catch { /* ignore */ }
}

export function deleteArtifactSetForXmi(xmiPath: string): void {
	const base = basePathFromXmi(xmiPath);
	for (const ext of [...TRIPLET_EXTENSIONS, META_EXTENSION]) {
		const p = base + ext;
		try {
			if (fs.existsSync(p)) { fs.unlinkSync(p); }
		} catch { /* ignore */ }
	}
}

export function isEvolutionReadOnly(fsPath: string): boolean {
	return isInsideEvolutionFolder(fsPath) && !isWorkspaceBigraphPath(fsPath);
}

export function isManagedWorkspaceBigraphPath(fsPath: string): boolean {
	return isInsideEvolutionFolder(fsPath) && isWorkspaceBigraphPath(fsPath);
}

export async function closeCustomEditorTab(fsPath: string): Promise<void> {
	for (const group of vscode.window.tabGroups.all) {
		for (const tab of group.tabs) {
			if (tab.input instanceof vscode.TabInputCustom && tab.input.uri.fsPath === fsPath) {
				await vscode.window.tabGroups.close(tab);
			}
		}
	}
	await new Promise((resolve) => setTimeout(resolve, 100));
}
