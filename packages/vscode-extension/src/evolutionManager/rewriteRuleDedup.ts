import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';

export interface RewriteRulePaths {
	label: string;
	redexPath: string;
	reactumPath: string;
}

function resolveAbsPath(filePath: string): string {
	if (path.isAbsolute(filePath)) {
		return filePath;
	}
	const ws = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
	return ws ? path.join(ws, filePath) : filePath;
}

function readFileUtf8(filePath: string): string | null {
	try {
		return fs.readFileSync(resolveAbsPath(filePath), 'utf8');
	} catch {
		return null;
	}
}

function rulePairContentKey(redexPath: string, reactumPath: string): string | null {
	const redex = readFileUtf8(redexPath);
	const reactum = readFileUtf8(reactumPath);
	if (redex === null || reactum === null) {
		return null;
	}
	return `${redex}\u0000${reactum}`;
}

/** True when label and redex/reactum file contents match (paths may differ). */
export function rewriteRulesMatchByLabelAndContent(
	a: RewriteRulePaths,
	b: RewriteRulePaths
): boolean {
	if (a.label !== b.label) {
		return false;
	}
	const keyA = rulePairContentKey(a.redexPath, a.reactumPath);
	const keyB = rulePairContentKey(b.redexPath, b.reactumPath);
	if (keyA !== null && keyB !== null) {
		return keyA === keyB;
	}
	return a.redexPath === b.redexPath && a.reactumPath === b.reactumPath;
}

/** Drops incoming rules that duplicate an existing rule by label + file content. */
export function filterNewRewriteRules(
	existing: RewriteRulePaths[],
	incoming: RewriteRulePaths[]
): RewriteRulePaths[] {
	const accepted: RewriteRulePaths[] = [];
	return incoming.filter((candidate) => {
		const isDuplicate = [...existing, ...accepted].some((rule) =>
			rewriteRulesMatchByLabelAndContent(rule, candidate)
		);
		if (!isDuplicate) {
			accepted.push(candidate);
		}
		return !isDuplicate;
	});
}
